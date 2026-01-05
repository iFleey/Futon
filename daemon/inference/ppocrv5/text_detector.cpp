/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "text_detector.h"

#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

#include "image_utils.h"
#include "litert_config.h"
#include "postprocess.h"
#include "core/error.h"

// LiteRT C++ API headers
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_tensor_buffer.h"

// LiteRT C API headers for direct buffer creation
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

#if defined(__ARM_NEON) || defined(__ARM_NEON__)

#include <arm_neon.h>

#endif

#define TAG "TextDetector"

namespace futon::inference::ppocrv5 {

    namespace {

        constexpr int kDetInputSize = 640;
        constexpr float kBinaryThreshold = 0.1f;
        constexpr float kBoxThreshold = 0.3f;
        constexpr float kMinBoxArea = 50.0f;
        constexpr float kUnclipRatio = 1.5f;

        litert::HwAccelerators ToLiteRtAccelerator(AcceleratorType type) {
            switch (type) {
                case AcceleratorType::kGpu:
                    return litert::HwAccelerators::kGpu;
                case AcceleratorType::kNpu:
                    return litert::HwAccelerators::kNpu;
                case AcceleratorType::kCpu:
                default:
                    return litert::HwAccelerators::kCpu;
            }
        }

        void UnclipBox(RotatedRect &box, float unclip_ratio) {
            float area = box.width * box.height;
            float perimeter = 2.0f * (box.width + box.height);
            if (perimeter < 1e-6f) return;

            float distance = area * unclip_ratio / perimeter;
            box.width += 2.0f * distance;
            box.height += 2.0f * distance;
        }

    }  // namespace

    class TextDetector::Impl {
    public:
        std::optional<litert::Environment> env_;
        std::optional<litert::CompiledModel> compiled_model_;
        std::vector<litert::TensorBuffer> input_buffers_;
        std::vector<litert::TensorBuffer> output_buffers_;

        float scale_x_ = 1.0f;
        float scale_y_ = 1.0f;

        alignas(64) std::vector<uint8_t> resized_buffer_;
        alignas(64) std::vector<float> normalized_buffer_;
        alignas(64) std::vector<uint8_t> binary_map_;
        alignas(64) std::vector<float> prob_map_;

        bool input_is_int8_ = false;
        bool input_is_uint8_ = false;
        bool input_is_quantized_ = false;
        float input_scale_ = 1.0f / 255.0f;
        int input_zero_point_ = 0;

        bool output_is_int8_ = false;
        bool output_is_uint8_ = false;
        bool output_is_quantized_ = false;
        float output_scale_ = 1.0f / 255.0f;
        int output_zero_point_ = 0;

        ~Impl() = default;

        bool Initialize(const std::string &model_path, AcceleratorType accelerator_type) {
            auto env_result = litert::Environment::Create({});
            if (!env_result) {
                FUTON_LOGE("Failed to create LiteRT environment: %s",
                           env_result.Error().Message().c_str());
                return false;
            }
            env_ = std::move(*env_result);
            FUTON_LOGD("LiteRT Environment created successfully");

            auto options_result = litert::Options::Create();
            if (!options_result) {
                FUTON_LOGE("Failed to create options: %s",
                           options_result.Error().Message().c_str());
                return false;
            }
            auto &options = *options_result;

            auto hw_accelerator = ToLiteRtAccelerator(accelerator_type);
            auto set_result = options.SetHardwareAccelerators(hw_accelerator);
            if (!set_result) {
                FUTON_LOGE("Failed to set hardware accelerators: %s",
                           set_result.Error().Message().c_str());
                return false;
            }

            auto model_result = litert::CompiledModel::Create(*env_, model_path, options);
            if (!model_result) {
                FUTON_LOGW("Failed to create CompiledModel with accelerator %d: %s",
                           static_cast<int>(accelerator_type),
                           model_result.Error().Message().c_str());
                return false;
            }
            compiled_model_ = std::move(*model_result);
            FUTON_LOGD("CompiledModel created successfully");

            std::vector<int> input_dims = {1, kDetInputSize, kDetInputSize, 3};
            auto resize_result = compiled_model_->ResizeInputTensor(0, absl::MakeConstSpan(input_dims));
            if (!resize_result) {
                FUTON_LOGE("Failed to resize input tensor: %s",
                           resize_result.Error().Message().c_str());
                return false;
            }

            auto input_type_result = compiled_model_->GetInputTensorType(0, 0);
            if (input_type_result) {
                auto &input_type = *input_type_result;
                auto element_type = input_type.ElementType();
                input_is_int8_ = (element_type == litert::ElementType::Int8);
                input_is_uint8_ = (element_type == litert::ElementType::UInt8);
                input_is_quantized_ = (input_is_int8_ || input_is_uint8_);
                FUTON_LOGD("Input tensor type: %s",
                           input_is_int8_ ? "INT8" : input_is_uint8_ ? "UINT8" : "FLOAT32");
            }

            auto output_type_result = compiled_model_->GetOutputTensorType(0, 0);
            if (output_type_result) {
                auto &output_type = *output_type_result;
                auto element_type = output_type.ElementType();
                output_is_int8_ = (element_type == litert::ElementType::Int8);
                output_is_uint8_ = (element_type == litert::ElementType::UInt8);
                output_is_quantized_ = (output_is_int8_ || output_is_uint8_);
                FUTON_LOGD("Output tensor type: %s",
                           output_is_int8_ ? "INT8" : output_is_uint8_ ? "UINT8" : "FLOAT32");
            }

            if (!CreateBuffersWithCApi()) {
                FUTON_LOGE("Failed to create input/output buffers");
                return false;
            }
            FUTON_LOGD("Created %zu input buffers, %zu output buffers",
                       input_buffers_.size(), output_buffers_.size());

            resized_buffer_.resize(kDetInputSize * kDetInputSize * 4);
            normalized_buffer_.resize(kDetInputSize * kDetInputSize * 3);
            binary_map_.resize(kDetInputSize * kDetInputSize);
            prob_map_.resize(kDetInputSize * kDetInputSize);

            FUTON_LOGD("TextDetector initialized successfully");
            return true;
        }

        bool CreateBuffersWithCApi() {
            if (!compiled_model_ || !env_) {
                FUTON_LOGE("CompiledModel or Environment not initialized");
                return false;
            }

            LiteRtCompiledModel c_model = compiled_model_->Get();
            LiteRtEnvironment c_env = env_->Get();

            LiteRtTensorBufferRequirements input_requirements = nullptr;
            auto status = LiteRtGetCompiledModelInputBufferRequirements(
                    c_model, 0, 0, &input_requirements);
            if (status != kLiteRtStatusOk || input_requirements == nullptr) {
                FUTON_LOGE("Failed to get input buffer requirements: %d", status);
                return false;
            }

            auto input_type_result = compiled_model_->GetInputTensorType(0, 0);
            if (!input_type_result) {
                FUTON_LOGE("Failed to get input tensor type");
                return false;
            }
            LiteRtRankedTensorType input_tensor_type =
                    static_cast<LiteRtRankedTensorType>(*input_type_result);

            LiteRtTensorBuffer input_buffer = nullptr;
            status = LiteRtCreateManagedTensorBufferFromRequirements(
                    c_env, &input_tensor_type, input_requirements, &input_buffer);
            if (status != kLiteRtStatusOk || input_buffer == nullptr) {
                FUTON_LOGE("Failed to create input tensor buffer: %d", status);
                return false;
            }
            input_buffers_.push_back(litert::TensorBuffer::WrapCObject(input_buffer, litert::OwnHandle::kYes));
            FUTON_LOGD("Created input buffer successfully");

            LiteRtTensorBufferRequirements output_requirements = nullptr;
            status = LiteRtGetCompiledModelOutputBufferRequirements(
                    c_model, 0, 0, &output_requirements);
            if (status != kLiteRtStatusOk || output_requirements == nullptr) {
                FUTON_LOGE("Failed to get output buffer requirements: %d", status);
                return false;
            }

            auto output_type_result = compiled_model_->GetOutputTensorType(0, 0);
            if (!output_type_result) {
                FUTON_LOGE("Failed to get output tensor type");
                return false;
            }
            LiteRtRankedTensorType output_tensor_type =
                    static_cast<LiteRtRankedTensorType>(*output_type_result);

            LiteRtTensorBuffer output_buffer = nullptr;
            status = LiteRtCreateManagedTensorBufferFromRequirements(
                    c_env, &output_tensor_type, output_requirements, &output_buffer);
            if (status != kLiteRtStatusOk || output_buffer == nullptr) {
                FUTON_LOGE("Failed to create output tensor buffer: %d", status);
                return false;
            }
            output_buffers_.push_back(litert::TensorBuffer::WrapCObject(output_buffer, litert::OwnHandle::kYes));
            FUTON_LOGD("Created output buffer successfully");

            return true;
        }

        std::vector<RotatedRect> Detect(const uint8_t *image_data,
                                        int width, int height, int stride,
                                        float *detection_time_ms) {
            auto start_time = std::chrono::high_resolution_clock::now();

            scale_x_ = static_cast<float>(width) / kDetInputSize;
            scale_y_ = static_cast<float>(height) / kDetInputSize;

            image_utils::ResizeBilinear(image_data, width, height, stride,
                                        resized_buffer_.data(), kDetInputSize, kDetInputSize);

            if (input_is_quantized_) {
                PrepareQuantizedInput();
            } else {
                PrepareFloatInput();
            }

            auto write_result = WriteInputBuffer();
            if (!write_result) {
                FUTON_LOGE("Failed to write input buffer: %s",
                           write_result.Error().Message().c_str());
                if (detection_time_ms) *detection_time_ms = 0.0f;
                return {};
            }

            FUTON_LOGD("Running inference...");
            auto run_result = compiled_model_->Run(input_buffers_, output_buffers_);
            if (!run_result) {
                FUTON_LOGE("Failed to run inference: %s",
                           run_result.Error().Message().c_str());
                if (detection_time_ms) *detection_time_ms = 0.0f;
                return {};
            }

            auto read_result = ReadOutputBuffer();
            if (!read_result) {
                FUTON_LOGE("Failed to read output buffer: %s",
                           read_result.Error().Message().c_str());
                if (detection_time_ms) *detection_time_ms = 0.0f;
                return {};
            }

            const int total_pixels = kDetInputSize * kDetInputSize;
            float *prob_map = prob_map_.data();

            BinarizeOutput(prob_map, total_pixels);

            auto contours = postprocess::FindContours(binary_map_.data(),
                                                      kDetInputSize, kDetInputSize);

            std::vector<RotatedRect> boxes;
            boxes.reserve(contours.size());

            for (const auto &contour: contours) {
                if (contour.size() < 4) continue;

                RotatedRect rect = postprocess::MinAreaRect(contour);
                if (rect.width < 1.0f || rect.height < 1.0f) continue;

                float box_score = CalculateBoxScore(contour, prob_map);
                if (box_score < kBoxThreshold) continue;

                UnclipBox(rect, kUnclipRatio);

                rect.center_x *= scale_x_;
                rect.center_y *= scale_y_;
                rect.width *= scale_x_;
                rect.height *= scale_y_;
                rect.confidence = box_score;

                boxes.push_back(rect);
            }

            auto filtered_boxes = postprocess::FilterAndSortBoxes(boxes, kBoxThreshold, kMinBoxArea);

            auto end_time = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::microseconds>(
                    end_time - start_time);
            if (detection_time_ms) {
                *detection_time_ms = duration.count() / 1000.0f;
            }

            FUTON_LOGD("Detection completed: %zu boxes in %.2f ms",
                       filtered_boxes.size(), duration.count() / 1000.0f);

            return filtered_boxes;
        }

    private:
        void PrepareQuantizedInput() {
            constexpr float kMeanR = 0.485f, kMeanG = 0.456f, kMeanB = 0.406f;
            constexpr float kStdR = 0.229f, kStdG = 0.224f, kStdB = 0.225f;

            const uint8_t *src = resized_buffer_.data();
            const float inv_scale = 1.0f / input_scale_;

            if (input_is_int8_) {
                int8_t *dst = reinterpret_cast<int8_t *>(normalized_buffer_.data());
                for (int i = 0; i < kDetInputSize * kDetInputSize; ++i) {
                    float r = src[i * 4 + 0] / 255.0f;
                    float g = src[i * 4 + 1] / 255.0f;
                    float b = src[i * 4 + 2] / 255.0f;

                    float norm_r = (r - kMeanR) / kStdR;
                    float norm_g = (g - kMeanG) / kStdG;
                    float norm_b = (b - kMeanB) / kStdB;

                    int q_r = static_cast<int>(std::round(norm_r * inv_scale)) + input_zero_point_;
                    int q_g = static_cast<int>(std::round(norm_g * inv_scale)) + input_zero_point_;
                    int q_b = static_cast<int>(std::round(norm_b * inv_scale)) + input_zero_point_;

                    dst[i * 3 + 0] = static_cast<int8_t>(std::max(-128, std::min(127, q_r)));
                    dst[i * 3 + 1] = static_cast<int8_t>(std::max(-128, std::min(127, q_g)));
                    dst[i * 3 + 2] = static_cast<int8_t>(std::max(-128, std::min(127, q_b)));
                }
            } else {
                uint8_t *dst = reinterpret_cast<uint8_t *>(normalized_buffer_.data());
                for (int i = 0; i < kDetInputSize * kDetInputSize; ++i) {
                    float r = src[i * 4 + 0] / 255.0f;
                    float g = src[i * 4 + 1] / 255.0f;
                    float b = src[i * 4 + 2] / 255.0f;

                    float norm_r = (r - kMeanR) / kStdR;
                    float norm_g = (g - kMeanG) / kStdG;
                    float norm_b = (b - kMeanB) / kStdB;

                    int q_r = static_cast<int>(std::round(norm_r * inv_scale)) + input_zero_point_;
                    int q_g = static_cast<int>(std::round(norm_g * inv_scale)) + input_zero_point_;
                    int q_b = static_cast<int>(std::round(norm_b * inv_scale)) + input_zero_point_;

                    dst[i * 3 + 0] = static_cast<uint8_t>(std::max(0, std::min(255, q_r)));
                    dst[i * 3 + 1] = static_cast<uint8_t>(std::max(0, std::min(255, q_g)));
                    dst[i * 3 + 2] = static_cast<uint8_t>(std::max(0, std::min(255, q_b)));
                }
            }
        }

        void PrepareFloatInput() {
            image_utils::NormalizeImageNet(resized_buffer_.data(),
                                           kDetInputSize, kDetInputSize,
                                           kDetInputSize * 4,
                                           normalized_buffer_.data());
        }

        litert::Expected<void> WriteInputBuffer() {
            if (input_buffers_.empty()) {
                return litert::Unexpected(kLiteRtStatusErrorInvalidArgument,
                                          "No input buffers available");
            }

            if (input_is_quantized_) {
                size_t data_size = kDetInputSize * kDetInputSize * 3;
                if (input_is_int8_) {
                    return input_buffers_[0].Write<int8_t>(
                            absl::MakeConstSpan(reinterpret_cast<const int8_t *>(normalized_buffer_.data()),
                                                data_size));
                } else {
                    return input_buffers_[0].Write<uint8_t>(
                            absl::MakeConstSpan(reinterpret_cast<const uint8_t *>(normalized_buffer_.data()),
                                                data_size));
                }
            } else {
                return input_buffers_[0].Write<float>(
                        absl::MakeConstSpan(normalized_buffer_.data(), normalized_buffer_.size()));
            }
        }

        litert::Expected<void> ReadOutputBuffer() {
            if (output_buffers_.empty()) {
                return litert::Unexpected(kLiteRtStatusErrorInvalidArgument,
                                          "No output buffers available");
            }

            const int total_pixels = kDetInputSize * kDetInputSize;
            float *prob_map = prob_map_.data();

            if (output_is_quantized_) {
                if (output_is_int8_) {
                    std::vector<int8_t> int8_output(total_pixels);
                    auto read_result = output_buffers_[0].Read<int8_t>(
                            absl::MakeSpan(int8_output.data(), total_pixels));
                    if (!read_result) return read_result;

                    for (int i = 0; i < total_pixels; ++i) {
                        prob_map[i] = (static_cast<float>(int8_output[i]) - output_zero_point_) * output_scale_;
                    }
                } else {
                    std::vector<uint8_t> uint8_output(total_pixels);
                    auto read_result = output_buffers_[0].Read<uint8_t>(
                            absl::MakeSpan(uint8_output.data(), total_pixels));
                    if (!read_result) return read_result;

                    for (int i = 0; i < total_pixels; ++i) {
                        prob_map[i] = (static_cast<float>(uint8_output[i]) - output_zero_point_) * output_scale_;
                    }
                }
            } else {
                auto read_result = output_buffers_[0].Read<float>(
                        absl::MakeSpan(prob_map, total_pixels));
                if (!read_result) return read_result;

                float raw_min = prob_map[0], raw_max = prob_map[0];
                for (int i = 1; i < total_pixels; ++i) {
                    raw_min = std::min(raw_min, prob_map[i]);
                    raw_max = std::max(raw_max, prob_map[i]);
                }
                FUTON_LOGD("Raw FLOAT32 output range: min=%.4f, max=%.4f", raw_min, raw_max);

                bool need_sigmoid = (raw_min < -0.5f || raw_max > 1.5f);
                if (need_sigmoid) {
                    FUTON_LOGD("Applying sigmoid activation");
                    ApplySigmoid(prob_map, total_pixels);
                }
            }

            return {};
        }

        void ApplySigmoid(float *data, int size) {
            for (int i = 0; i < size; ++i) {
                data[i] = 1.0f / (1.0f + std::exp(-data[i]));
            }
        }

        void BinarizeOutput(const float *prob_map, int total_pixels) {
            for (int i = 0; i < total_pixels; ++i) {
                binary_map_[i] = (prob_map[i] > kBinaryThreshold) ? 255 : 0;
            }
        }

        float CalculateBoxScore(const std::vector<postprocess::Point> &contour,
                                const float *prob_map) {
            float min_x = contour[0].x, max_x = contour[0].x;
            float min_y = contour[0].y, max_y = contour[0].y;
            for (const auto &pt: contour) {
                min_x = std::min(min_x, pt.x);
                max_x = std::max(max_x, pt.x);
                min_y = std::min(min_y, pt.y);
                max_y = std::max(max_y, pt.y);
            }

            int x_start = std::max(0, static_cast<int>(min_x));
            int x_end = std::min(kDetInputSize - 1, static_cast<int>(max_x));
            int y_start = std::max(0, static_cast<int>(min_y));
            int y_end = std::min(kDetInputSize - 1, static_cast<int>(max_y));

            float box_score = 0.0f;
            int count = 0;
            for (int py = y_start; py <= y_end; ++py) {
                for (int px = x_start; px <= x_end; ++px) {
                    if (binary_map_[py * kDetInputSize + px] > 0) {
                        box_score += prob_map[py * kDetInputSize + px];
                        ++count;
                    }
                }
            }

            return (count > 0) ? (box_score / count) : 0.0f;
        }
    };

    TextDetector::~TextDetector() = default;

    std::unique_ptr<TextDetector> TextDetector::Create(
            const std::string &model_path,
            AcceleratorType accelerator_type) {
        auto detector = std::unique_ptr<TextDetector>(new TextDetector());
        detector->impl_ = std::make_unique<Impl>();

        if (!detector->impl_->Initialize(model_path, accelerator_type)) {
            FUTON_LOGE("Failed to initialize TextDetector");
            return nullptr;
        }

        return detector;
    }

    std::vector<RotatedRect> TextDetector::Detect(const uint8_t *image_data,
                                                  int width, int height, int stride,
                                                  float *detection_time_ms) {
        if (!impl_) {
            FUTON_LOGE("TextDetector not initialized");
            if (detection_time_ms) *detection_time_ms = 0.0f;
            return {};
        }

        return impl_->Detect(image_data, width, height, stride, detection_time_ms);
    }

}  // namespace futon::inference::ppocrv5
