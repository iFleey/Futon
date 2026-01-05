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

#ifndef FUTON_INFERENCE_PPOCRV5_TEXT_RECOGNIZER_H
#define FUTON_INFERENCE_PPOCRV5_TEXT_RECOGNIZER_H

#include <memory>
#include <string>

#include "ppocrv5_types.h"

namespace futon::inference::ppocrv5 {

/**
 * Text recognizer using LiteRT C++ API.
 * Crops and rotates text regions, then runs recognition model.
 */
    class TextRecognizer {
    public:
        ~TextRecognizer();

        /**
         * Create a text recognizer.
         * @param model_path Path to recognition TFLite model
         * @param keys_path Path to character dictionary file
         * @param accelerator_type Hardware accelerator to use
         * @return Recognizer instance or nullptr on failure
         */
        static std::unique_ptr<TextRecognizer> Create(
                const std::string &model_path,
                const std::string &keys_path,
                AcceleratorType accelerator_type);

        /**
         * Recognize text in a rotated region.
         * @param image_data RGBA image data
         * @param width Image width
         * @param height Image height
         * @param stride Row stride in bytes
         * @param box Rotated bounding box from detector
         * @param recognition_time_ms Output: recognition time in milliseconds
         * @return Recognition result with text and confidence
         */
        RecognitionResult Recognize(const uint8_t *image_data,
                                    int width, int height, int stride,
                                    const RotatedRect &box,
                                    float *recognition_time_ms = nullptr);

    private:
        TextRecognizer() = default;

        class Impl;

        std::unique_ptr<Impl> impl_;
    };

}  // namespace futon::inference::ppocrv5

#endif  // FUTON_INFERENCE_PPOCRV5_TEXT_RECOGNIZER_H
