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

#ifndef FUTON_INFERENCE_PPOCRV5_IMAGE_UTILS_H
#define FUTON_INFERENCE_PPOCRV5_IMAGE_UTILS_H

#include <cstdint>

namespace futon::inference::ppocrv5::image_utils {

    void ResizeBilinear(const uint8_t *src, int src_w, int src_h, int src_stride,
                        uint8_t *dst, int dst_w, int dst_h);

    void NormalizeImageNet(const uint8_t *src, int w, int h, int stride, float *dst);

    void NormalizeRecognition(const uint8_t *src, int w, int h, int stride, float *dst);

    void PerspectiveTransform(const uint8_t *src, int src_w, int src_h, int stride,
                              const float *src_points, float *dst, int dst_w, int dst_h);

    void PerspectiveTransformFloat32Raw(const uint8_t *src, int src_w, int src_h, int stride,
                                        const float *src_points, float *dst, int dst_w, int dst_h);

    void PerspectiveTransformUint8(const uint8_t *src, int src_w, int src_h, int stride,
                                   const float *src_points, uint8_t *dst, int dst_w, int dst_h);

}  // namespace futon::inference::ppocrv5::image_utils

#endif  // FUTON_INFERENCE_PPOCRV5_IMAGE_UTILS_H
