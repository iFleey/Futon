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

#ifndef FUTON_INFERENCE_PPOCRV5_TYPES_H
#define FUTON_INFERENCE_PPOCRV5_TYPES_H

#include <string>
#include <vector>

namespace futon::inference::ppocrv5 {

/**
 * Hardware accelerator types for OCR inference.
 * FP16 models: GPU is primary, NPU doesn't benefit from FP16 weights.
 */
    enum class AcceleratorType {
        kGpu = 0,  // GPU via OpenCL - recommended for FP16
        kCpu = 1,  // CPU fallback
        kNpu = 2,  // NPU - not recommended for FP16
    };

/**
 * Rotated rectangle for text detection.
 */
    struct RotatedRect {
        float center_x = 0.0f;
        float center_y = 0.0f;
        float width = 0.0f;
        float height = 0.0f;
        float angle = 0.0f;
        float confidence = 0.0f;
    };

/**
 * Benchmark timing information.
 */
    struct Benchmark {
        float detection_time_ms = 0.0f;
        float recognition_time_ms = 0.0f;
        float total_time_ms = 0.0f;
        float fps = 0.0f;
    };

/**
 * OCR result for a single text region.
 */
    struct OcrResult {
        std::string text;
        float confidence;
        RotatedRect box;
    };

/**
 * Recognition result (text only, no box).
 */
    struct RecognitionResult {
        std::string text;
        float confidence = 0.0f;
    };

}  // namespace futon::inference::ppocrv5

#endif  // FUTON_INFERENCE_PPOCRV5_TYPES_H
