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

#ifndef FUTON_INFERENCE_PPOCRV5_POSTPROCESS_H
#define FUTON_INFERENCE_PPOCRV5_POSTPROCESS_H

#include <cstdint>
#include <vector>

#include "ppocrv5_types.h"

namespace futon::inference::ppocrv5::postprocess {

    struct Point {
        float x = 0.0f;
        float y = 0.0f;
    };

    std::vector<std::vector<Point>> FindContours(const uint8_t *binary_map,
                                                 int width, int height);

    RotatedRect MinAreaRect(const std::vector<Point> &contour);

    std::vector<RotatedRect> FilterAndSortBoxes(
            const std::vector<RotatedRect> &boxes,
            float min_confidence, float min_area);

}  // namespace futon::inference::ppocrv5::postprocess

#endif  // FUTON_INFERENCE_PPOCRV5_POSTPROCESS_H
