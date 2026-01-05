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

#ifndef FUTON_INFERENCE_PPOCRV5_TEXT_DETECTOR_H
#define FUTON_INFERENCE_PPOCRV5_TEXT_DETECTOR_H

#include <memory>
#include <string>
#include <vector>

#include "ppocrv5_types.h"

namespace futon::inference::ppocrv5 {

    class TextDetector {
    public:
        static std::unique_ptr<TextDetector> Create(
                const std::string &model_path,
                AcceleratorType accelerator_type);

        std::vector<RotatedRect> Detect(const uint8_t *image_data,
                                        int width, int height, int stride,
                                        float *detection_time_ms);

        ~TextDetector();

    private:
        TextDetector() = default;

        class Impl;

        std::unique_ptr<Impl> impl_;
    };

}  // namespace futon::inference::ppocrv5

#endif  // FUTON_INFERENCE_PPOCRV5_TEXT_DETECTOR_H
