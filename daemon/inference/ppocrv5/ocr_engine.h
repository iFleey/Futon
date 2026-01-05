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

#ifndef FUTON_INFERENCE_PPOCRV5_OCR_ENGINE_H
#define FUTON_INFERENCE_PPOCRV5_OCR_ENGINE_H

#include <memory>
#include <string>
#include <vector>

#include "ppocrv5_types.h"
#include "text_detector.h"
#include "text_recognizer.h"

namespace futon::inference::ppocrv5 {

/**
 * Complete OCR engine combining detection and recognition.
 * Handles accelerator fallback chain: GPU -> CPU.
 */
    class OcrEngine {
    public:
        ~OcrEngine() = default;

        /**
         * Create an OCR engine with automatic accelerator fallback.
         * @param det_model_path Path to detection model
         * @param rec_model_path Path to recognition model
         * @param keys_path Path to character dictionary
         * @param accelerator_type Preferred accelerator (will fallback if unavailable)
         * @return Engine instance or nullptr on failure
         */
        static std::unique_ptr<OcrEngine> Create(
                const std::string &det_model_path,
                const std::string &rec_model_path,
                const std::string &keys_path,
                AcceleratorType accelerator_type = AcceleratorType::kGpu);

        /**
         * Process an image and return OCR results.
         * @param image_data RGBA image data
         * @param width Image width
         * @param height Image height
         * @param stride Row stride in bytes
         * @return Vector of OCR results with text, confidence, and bounding boxes
         */
        std::vector<OcrResult> Process(const uint8_t *image_data,
                                       int width, int height, int stride);

        /**
         * Get benchmark timing from last Process() call.
         */
        Benchmark GetBenchmark() const;

        /**
         * Get the active accelerator type.
         */
        AcceleratorType GetActiveAccelerator() const;

    private:
        OcrEngine() = default;

        void WarmUp();

        std::unique_ptr<TextDetector> detector_;
        std::unique_ptr<TextRecognizer> recognizer_;
        AcceleratorType active_accelerator_ = AcceleratorType::kCpu;
        Benchmark benchmark_;
    };

}  // namespace futon::inference::ppocrv5

#endif  // FUTON_INFERENCE_PPOCRV5_OCR_ENGINE_H
