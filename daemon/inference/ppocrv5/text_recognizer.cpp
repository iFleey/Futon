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

#include "text_recognizer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <vector>

#include "image_utils.h"
#include "litert_config.h"
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

#define USE_NEON 1
#else
#define USE_NEON 0
#endif

#define TAG "TextRecognizer"

namespace futon::inference::ppocrv5 {

    namespace {

        constexpr int kRecInputHeight = 48;
        constexpr int kRecInputWidth = 320;
        constexpr int kBlankIndex = 0;

        constexpr float kRecMean = 127.5f;
        constexpr float kRecInvStd = 1.0f / 127.5f;

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

#if USE_NEON

        inline void ArgmaxNeon8(const float *__restrict__ data, int size,
                                int &max_idx, float &max_val) {
            if (size < 16) {
                max_idx = 0;
                max_val = data[0];
                for (int i = 1; i < size; ++i) {
                    if (data[i] > max_val) {
                        max_val = data[i];
                        max_idx = i;
                    }
                }
                return;
            }

            float32x4_t v_max = vld1q_f32(data);
            int32x4_t v_idx = {0, 1, 2, 3};
            int32x4_t v_max_idx = v_idx;
            const int32x4_t v_four = vdupq_n_s32(4);

            int i = 4;
            for (; i + 4 <= size; i += 4) {
                float32x4_t v_curr = vld1q_f32(data + i);
                v_idx = vaddq_s32(v_idx, v_four);

                uint32x4_t cmp = vcgtq_f32(v_curr, v_max);
                v_max = vbslq_f32(cmp, v_curr, v_max);
                v_max_idx = vbslq_s32(cmp, v_idx, v_max_idx);
            }

            float max_vals[4];
            int32_t max_idxs[4];
            vst1q_f32(max_vals, v_max);
            vst1q_s32(max_idxs, v_max_idx);

            max_val = max_vals[0];
            max_idx = max_idxs[0];
            for (int j = 1; j < 4; ++j) {
                if (max_vals[j] > max_val) {
                    max_val = max_vals[j];
                    max_idx = max_idxs[j];
                }
            }

            for (; i < size; ++i) {
                if (data[i] > max_val) {
                    max_val = data[i];
                    max_idx = i;
                }
            }
        }

        inline void BilinearSampleNeon(const uint8_t *__restrict__ src, int stride,
                                       float sx, float sy, float *__restrict__ dst) {
            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            const float dx = sx - x0;
            const float dy = sy - y0;

            const uint8_t *row0 = src + y0 * stride + x0 * 4;
            const uint8_t *row1 = row0 + stride;

            uint8x8_t p00_01 = vld1_u8(row0);
            uint8x8_t p10_11 = vld1_u8(row1);

            uint16x8_t p00_01_16 = vmovl_u8(p00_01);
            uint16x8_t p10_11_16 = vmovl_u8(p10_11);

            float32x4_t p00 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(p00_01_16)));
            float32x4_t p01 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(p00_01_16)));
            float32x4_t p10 = vcvtq_f32_u32(vmovl_u16(vget_low_u16(p10_11_16)));
            float32x4_t p11 = vcvtq_f32_u32(vmovl_u16(vget_high_u16(p10_11_16)));

            float w00 = (1.0f - dx) * (1.0f - dy);
            float w01 = dx * (1.0f - dy);
            float w10 = (1.0f - dx) * dy;
            float w11 = dx * dy;

            float32x4_t vw00 = vdupq_n_f32(w00);
            float32x4_t vw01 = vdupq_n_f32(w01);
            float32x4_t vw10 = vdupq_n_f32(w10);
            float32x4_t vw11 = vdupq_n_f32(w11);

            float32x4_t result = vmulq_f32(p00, vw00);
            result = vmlaq_f32(result, p01, vw01);
            result = vmlaq_f32(result, p10, vw10);
            result = vmlaq_f32(result, p11, vw11);

            float32x4_t vmean = vdupq_n_f32(kRecMean);
            float32x4_t vinvstd = vdupq_n_f32(kRecInvStd);
            result = vmulq_f32(vsubq_f32(result, vmean), vinvstd);

            float rgba[4];
            vst1q_f32(rgba, result);
            dst[0] = rgba[0];
            dst[1] = rgba[1];
            dst[2] = rgba[2];
        }

#endif

        inline void BilinearSampleScalar(const uint8_t *__restrict__ src, int stride,
                                         float sx, float sy, float *__restrict__ dst) {
            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            const float dx = sx - x0;
            const float dy = sy - y0;

            const uint8_t *row0 = src + y0 * stride + x0 * 4;
            const uint8_t *row1 = row0 + stride;

            const float w00 = (1.0f - dx) * (1.0f - dy);
            const float w01 = dx * (1.0f - dy);
            const float w10 = (1.0f - dx) * dy;
            const float w11 = dx * dy;

            for (int c = 0; c < 3; ++c) {
                float v = row0[c] * w00 + row0[4 + c] * w01 +
                          row1[c] * w10 + row1[4 + c] * w11;
                dst[c] = (v - kRecMean) * kRecInvStd;
            }
        }

    }  // namespace

    class TextRecognizer::Impl {
    public:
        std::optional<litert::Environment> env_;
        std::optional<litert::CompiledModel> compiled_model_;
        std::vector<litert::TensorBuffer> input_buffers_;
        std::vector<litert::TensorBuffer> output_buffers_;

        std::vector<std::string> dictionary_;

        alignas(64) std::vector<float> input_buffer_;
        alignas(64) std::vector<float> output_buffer_;

        bool input_is_float32_ = true;
        bool output_is_float32_ = true;
        float input_scale_ = 1.0f;
        int input_zero_point_ = 0;
        float output_scale_ = 1.0f;
        int output_zero_point_ = 0;

        int num_classes_ = 0;
        int time_steps_ = 0;

        ~Impl() = default;

        bool LoadDictionary(const std::string &keys_path) {
            std::ifstream file(keys_path);
            if (!file.is_open()) {
                FUTON_LOGE("Failed to open dictionary file: %s", keys_path.c_str());
                return false;
            }

            dictionary_.clear();
            dictionary_.reserve(20000);
            std::string line;
            while (std::getline(file, line)) {
                if (!line.empty() && line.back() == '\r') {
                    line.pop_back();
                }
                dictionary_.push_back(std::move(line));
            }

            FUTON_LOGD("Loaded dictionary with %zu characters", dictionary_.size());
            return true;
        }

        bool Initialize(const std::string &model_path,
                        const std::string &keys_path,
                        AcceleratorType accelerator_type) {
            if (!LoadDictionary(keys_path)) {
                return false;
            }

            auto env_result = litert::Environment::Create({});
            if (!env_result) {
                FUTON_LOGE("Failed to create LiteRT environment: %s",
                           env_result.Error().Message().c_str());
                return false;
            }
            env_ = std::move(*env_result);

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

            std::vector<int> input_dims = {1, kRecInputHeight, kRecInputWidth, 3};
            auto resize_result = compiled_model_->ResizeInputTensor(0, absl::MakeConstSpan(input_dims));
            if (!resize_result) {
                FUTON_LOGE("Failed to resize input tensor: %s",
                           resize_result.Error().Message().c_str());
                return false;
            }

            auto input_type_result = compiled_model_->GetInputTensorType(0, 0);
            if (input_type_result) {
                auto element_type = input_type_result->ElementType();
                input_is_float32_ = (element_type == litert::ElementType::Float32);
                FUTON_LOGD("Input tensor type: %s", input_is_float32_ ? "FLOAT32" : "QUANTIZED");
            }

            auto output_type_result = compiled_model_->GetOutputTensorType(0, 0);
            if (output_type_result) {
                auto element_type = output_type_result->ElementType();
                output_is_float32_ = (element_type == litert::ElementType::Float32);

                const auto &layout = output_type_result->Layout();
                auto dims = layout.Dimensions();
                if (dims.size() >= 3) {
                    time_steps_ = static_cast<int>(dims[1]);
                    num_classes_ = static_cast<int>(dims[2]);
                } else if (dims.size() == 2) {
                    time_steps_ = static_cast<int>(dims[0]);
                    num_classes_ = static_cast<int>(dims[1]);
                }

                if (time_steps_ <= 0) time_steps_ = kRecInputWidth / 8;
                if (num_classes_ <= 0) num_classes_ = static_cast<int>(dictionary_.size()) + 1;
                FUTON_LOGD("Output: time_steps=%d, num_classes=%d", time_steps_, num_classes_);
            }

            if (!CreateBuffersWithCApi()) {
                FUTON_LOGE("Failed to create input/output buffers");
                return false;
            }

            input_buffer_.resize(kRecInputHeight * kRecInputWidth * 3, 0.0f);
            output_buffer_.resize(time_steps_ * num_classes_);

            FUTON_LOGD("TextRecognizer initialized successfully");
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

            FUTON_LOGD("Created input/output buffers successfully");
            return true;
        }

        void CropAndRotate(const uint8_t *__restrict__ image_data, int width, int height, int stride,
                           const RotatedRect &box, int &target_width) {
            const float cos_angle = std::cos(box.angle * M_PI / 180.0f);
            const float sin_angle = std::sin(box.angle * M_PI / 180.0f);
            const float half_w = box.width / 2.0f;
            const float half_h = box.height / 2.0f;

            float corners[8];
            corners[0] = box.center_x + (-half_w * cos_angle - (-half_h) * sin_angle);
            corners[1] = box.center_y + (-half_w * sin_angle + (-half_h) * cos_angle);
            corners[2] = box.center_x + (half_w * cos_angle - (-half_h) * sin_angle);
            corners[3] = box.center_y + (half_w * sin_angle + (-half_h) * cos_angle);
            corners[4] = box.center_x + (half_w * cos_angle - half_h * sin_angle);
            corners[5] = box.center_y + (half_w * sin_angle + half_h * cos_angle);
            corners[6] = box.center_x + (-half_w * cos_angle - half_h * sin_angle);
            corners[7] = box.center_y + (-half_w * sin_angle + half_h * cos_angle);

            float src_width = box.width;
            float src_height = box.height;

            if (src_width < src_height) {
                std::swap(src_width, src_height);
                float temp[8];
                temp[0] = corners[2];
                temp[1] = corners[3];
                temp[2] = corners[4];
                temp[3] = corners[5];
                temp[4] = corners[6];
                temp[5] = corners[7];
                temp[6] = corners[0];
                temp[7] = corners[1];
                std::memcpy(corners, temp, sizeof(corners));
            }

            const float aspect_ratio = src_width / std::max(src_height, 1.0f);
            target_width = static_cast<int>(kRecInputHeight * aspect_ratio);
            target_width = std::clamp(target_width, 1, kRecInputWidth);

            const float x0 = corners[0], y0 = corners[1];
            const float x1 = corners[2], y1 = corners[3];
            const float x3 = corners[6], y3 = corners[7];

            const float inv_dst_w = 1.0f / std::max(target_width - 1, 1);
            const float inv_dst_h = 1.0f / std::max(kRecInputHeight - 1, 1);

            const float a00 = (x1 - x0) * inv_dst_w;
            const float a01 = (x3 - x0) * inv_dst_h;
            const float a10 = (y1 - y0) * inv_dst_w;
            const float a11 = (y3 - y0) * inv_dst_h;

            float *__restrict__ dst = input_buffer_.data();
            std::fill(input_buffer_.begin(), input_buffer_.end(), 0.0f);

            const int max_x = width - 2;
            const int max_y = height - 2;

            for (int dy = 0; dy < kRecInputHeight; ++dy) {
                float *__restrict__ dst_row = dst + dy * kRecInputWidth * 3;
                const float base_sx = x0 + a01 * dy;
                const float base_sy = y0 + a11 * dy;

#if USE_NEON
                for (int dx = 0; dx < target_width; ++dx) {
                    const float sx = base_sx + a00 * dx;
                    const float sy = base_sy + a10 * dx;
                    if (sx >= 0 && sx < max_x && sy >= 0 && sy < max_y) {
                        BilinearSampleNeon(image_data, stride, sx, sy, dst_row + dx * 3);
                    }
                }
#else
                for (int dx = 0; dx < target_width; ++dx) {
                    const float sx = base_sx + a00 * dx;
                    const float sy = base_sy + a10 * dx;
                    if (sx >= 0 && sx < max_x && sy >= 0 && sy < max_y) {
                        BilinearSampleScalar(image_data, stride, sx, sy, dst_row + dx * 3);
                    }
                }
#endif
            }
        }

        std::string CtcDecode(const float *__restrict__ logits, float *confidence) {
            std::string result;
            result.reserve(64);
            float total_confidence = 0.0f;
            int char_count = 0;
            int prev_index = kBlankIndex;
            const int dict_size = static_cast<int>(dictionary_.size());

            for (int t = 0; t < time_steps_; ++t) {
                const float *step_logits = logits + t * num_classes_;

                int max_index = 0;
                float max_value = 0.0f;

#if USE_NEON
                ArgmaxNeon8(step_logits, num_classes_, max_index, max_value);
#else
                max_value = step_logits[0];
                for (int c = 1; c < num_classes_; ++c) {
                    if (step_logits[c] > max_value) {
                        max_value = step_logits[c];
                        max_index = c;
                    }
                }
#endif

                if (max_index == kBlankIndex || max_index == prev_index) {
                    prev_index = max_index;
                    continue;
                }
                prev_index = max_index;

                const int dict_idx = max_index - 1;
                if (dict_idx >= 0 && dict_idx < dict_size) {
                    result += dictionary_[dict_idx];
                    total_confidence += max_value;
                    ++char_count;
                }
            }

            if (confidence) {
                *confidence = (char_count > 0) ? (total_confidence / char_count) : 0.0f;
            }
            return result;
        }

        RecognitionResult Recognize(const uint8_t *image_data, int width, int height, int stride,
                                    const RotatedRect &box, float *recognition_time_ms) {
            auto start_time = std::chrono::high_resolution_clock::now();

            int target_width = kRecInputWidth;
            CropAndRotate(image_data, width, height, stride, box, target_width);

            auto write_result = input_buffers_[0].Write<float>(
                    absl::MakeConstSpan(input_buffer_.data(), input_buffer_.size()));
            if (!write_result) {
                FUTON_LOGE("Failed to write input buffer");
                if (recognition_time_ms) *recognition_time_ms = 0.0f;
                return {};
            }

            auto run_result = compiled_model_->Run(input_buffers_, output_buffers_);
            if (!run_result) {
                FUTON_LOGE("Inference failed: %s", run_result.Error().Message().c_str());
                if (recognition_time_ms) *recognition_time_ms = 0.0f;
                return {};
            }

            auto read_result = output_buffers_[0].Read<float>(
                    absl::MakeSpan(output_buffer_.data(), output_buffer_.size()));
            if (!read_result) {
                FUTON_LOGE("Failed to read output buffer");
                if (recognition_time_ms) *recognition_time_ms = 0.0f;
                return {};
            }

            RecognitionResult result;
            result.text = CtcDecode(output_buffer_.data(), &result.confidence);

            auto end_time = std::chrono::high_resolution_clock::now();
            if (recognition_time_ms) {
                *recognition_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                        end_time - start_time).count() / 1000.0f;
            }

            return result;
        }
    };

    TextRecognizer::~TextRecognizer() = default;

    std::unique_ptr<TextRecognizer> TextRecognizer::Create(
            const std::string &model_path,
            const std::string &keys_path,
            AcceleratorType accelerator_type) {
        auto recognizer = std::unique_ptr<TextRecognizer>(new TextRecognizer());
        recognizer->impl_ = std::make_unique<Impl>();

        if (!recognizer->impl_->Initialize(model_path, keys_path, accelerator_type)) {
            FUTON_LOGE("Failed to initialize TextRecognizer");
            return nullptr;
        }

        return recognizer;
    }

    RecognitionResult TextRecognizer::Recognize(const uint8_t *image_data,
                                                int width, int height, int stride,
                                                const RotatedRect &box,
                                                float *recognition_time_ms) {
        if (!impl_) {
            FUTON_LOGE("TextRecognizer not initialized");
            if (recognition_time_ms) *recognition_time_ms = 0.0f;
            return {};
        }

        return impl_->Recognize(image_data, width, height, stride, box, recognition_time_ms);
    }

}  // namespace futon::inference::ppocrv5
