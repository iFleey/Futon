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

#ifndef FUTON_INFERENCE_BOUNDING_BOX_H
#define FUTON_INFERENCE_BOUNDING_BOX_H

#include <vector>
#include <cstdint>
#include <algorithm>
#include <cmath>

namespace futon::inference {

/**
 * BoundingBox - Detection result with normalized coordinates
 *
 * All coordinates are normalized to [0, 1] range.
 * (0, 0) is top-left, (1, 1) is bottom-right.
 */
    struct BoundingBox {
        float x1, y1, x2, y2;   // Normalized coordinates [0, 1]
        float confidence;        // Confidence score [0, 1]
        int class_id;           // Class ID from model (>= 0)

        /**
         * Validate that all values are in valid ranges
         */
        bool is_valid() const {
            return x1 >= 0.0f && x1 <= 1.0f &&
                   y1 >= 0.0f && y1 <= 1.0f &&
                   x2 >= 0.0f && x2 <= 1.0f &&
                   y2 >= 0.0f && y2 <= 1.0f &&
                   x1 <= x2 && y1 <= y2 &&
                   confidence >= 0.0f && confidence <= 1.0f &&
                   class_id >= 0;
        }

        /**
         * Get center point
         */
        void get_center(float *cx, float *cy) const {
            *cx = (x1 + x2) / 2.0f;
            *cy = (y1 + y2) / 2.0f;
        }

        /**
         * Get width and height
         */
        float width() const { return x2 - x1; }

        float height() const { return y2 - y1; }

        /**
         * Get area
         */
        float area() const { return width() * height(); }

        /**
         * Convert to pixel coordinates
         */
        void to_pixels(int screen_width, int screen_height,
                       int *px1, int *py1, int *px2, int *py2) const {
            *px1 = static_cast<int>(x1 * screen_width);
            *py1 = static_cast<int>(y1 * screen_height);
            *px2 = static_cast<int>(x2 * screen_width);
            *py2 = static_cast<int>(y2 * screen_height);
        }

        /**
         * Get center in pixel coordinates
         */
        void get_center_pixels(int screen_width, int screen_height,
                               int *cx, int *cy) const {
            float fcx, fcy;
            get_center(&fcx, &fcy);
            *cx = static_cast<int>(fcx * screen_width);
            *cy = static_cast<int>(fcy * screen_height);
        }

        /**
         * Calculate IoU (Intersection over Union) with another box
         */
        float iou(const BoundingBox &other) const {
            float inter_x1 = std::max(x1, other.x1);
            float inter_y1 = std::max(y1, other.y1);
            float inter_x2 = std::min(x2, other.x2);
            float inter_y2 = std::min(y2, other.y2);

            if (inter_x1 >= inter_x2 || inter_y1 >= inter_y2) {
                return 0.0f;
            }

            float inter_area = (inter_x2 - inter_x1) * (inter_y2 - inter_y1);
            float union_area = area() + other.area() - inter_area;

            return inter_area / union_area;
        }
    };

/**
 * BoundingBoxParser - Parse model output into bounding boxes
 *
 * Supports common detection model output formats:
 * - SSD MobileNet: [N, 4] boxes, [N] classes, [N] scores, [1] count
 * - YOLO: [N, 5+C] where 5 = x, y, w, h, obj_conf and C = class scores
 * - EfficientDet: Similar to SSD format
 */
    class BoundingBoxParser {
    public:
        /**
         * Parse SSD-style output (TFLite Object Detection API format)
         *
         * @param boxes Box coordinates [N, 4] in (y1, x1, y2, x2) format
         * @param classes Class IDs [N]
         * @param scores Confidence scores [N]
         * @param num_detections Number of valid detections
         * @param confidence_threshold Minimum confidence to include
         * @return Vector of valid bounding boxes
         */
        static std::vector<BoundingBox> parse_ssd_output(
                const float *boxes,
                const float *classes,
                const float *scores,
                int num_detections,
                float confidence_threshold = 0.5f);

        /**
         * Parse YOLO-style output
         *
         * @param output Raw model output [N, 5+num_classes]
         * @param num_boxes Number of boxes
         * @param num_classes Number of classes
         * @param confidence_threshold Minimum confidence
         * @param nms_threshold NMS IoU threshold
         * @return Vector of valid bounding boxes after NMS
         */
        static std::vector<BoundingBox> parse_yolo_output(
                const float *output,
                int num_boxes,
                int num_classes,
                float confidence_threshold = 0.5f,
                float nms_threshold = 0.45f);

        /**
         * Apply Non-Maximum Suppression
         *
         * @param boxes Input boxes (will be modified)
         * @param iou_threshold IoU threshold for suppression
         * @return Filtered boxes
         */
        static std::vector<BoundingBox> apply_nms(
                std::vector<BoundingBox> &boxes,
                float iou_threshold = 0.45f);

        /**
         * Clamp coordinates to [0, 1] range
         */
        static void clamp_coordinates(BoundingBox &box);

        /**
         * Normalize coordinates from pixel to [0, 1]
         */
        static BoundingBox from_pixels(
                int x1, int y1, int x2, int y2,
                int screen_width, int screen_height,
                float confidence, int class_id);
    };

} // namespace futon::inference

#endif // FUTON_INFERENCE_BOUNDING_BOX_H
