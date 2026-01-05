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

#include "bounding_box.h"
#include "core/error.h"

#include <algorithm>
#include <cmath>

namespace futon::inference {

    std::vector<BoundingBox> BoundingBoxParser::parse_ssd_output(
            const float *boxes,
            const float *classes,
            const float *scores,
            int num_detections,
            float confidence_threshold) {

        std::vector<BoundingBox> result;

        if (!boxes || num_detections <= 0) {
            return result;
        }

        result.reserve(num_detections);

        for (int i = 0; i < num_detections; i++) {
            float score = scores ? scores[i] : 1.0f;

            if (score < confidence_threshold) {
                continue;
            }

            BoundingBox box;
            // SSD format: [y1, x1, y2, x2] normalized
            box.y1 = boxes[i * 4 + 0];
            box.x1 = boxes[i * 4 + 1];
            box.y2 = boxes[i * 4 + 2];
            box.x2 = boxes[i * 4 + 3];
            box.confidence = score;
            box.class_id = classes ? static_cast<int>(classes[i]) : 0;

            // Clamp to valid range
            clamp_coordinates(box);

            if (box.is_valid()) {
                result.push_back(box);
            }
        }

        FUTON_LOGD("BoundingBoxParser: Parsed %zu boxes from %d detections (threshold=%.2f)",
                   result.size(), num_detections, confidence_threshold);

        return result;
    }

    std::vector<BoundingBox> BoundingBoxParser::parse_yolo_output(
            const float *output,
            int num_boxes,
            int num_classes,
            float confidence_threshold,
            float nms_threshold) {

        std::vector<BoundingBox> result;

        if (!output || num_boxes <= 0 || num_classes <= 0) {
            return result;
        }

        const int stride = 5 + num_classes;  // x, y, w, h, obj_conf, class_scores...

        result.reserve(num_boxes);

        for (int i = 0; i < num_boxes; i++) {
            const float *box_data = output + i * stride;

            float obj_conf = box_data[4];
            if (obj_conf < confidence_threshold) {
                continue;
            }

            // Find best class
            int best_class = 0;
            float best_class_score = box_data[5];
            for (int c = 1; c < num_classes; c++) {
                if (box_data[5 + c] > best_class_score) {
                    best_class_score = box_data[5 + c];
                    best_class = c;
                }
            }

            float final_confidence = obj_conf * best_class_score;
            if (final_confidence < confidence_threshold) {
                continue;
            }

            // YOLO format: center_x, center_y, width, height (normalized)
            float cx = box_data[0];
            float cy = box_data[1];
            float w = box_data[2];
            float h = box_data[3];

            BoundingBox box;
            box.x1 = cx - w / 2.0f;
            box.y1 = cy - h / 2.0f;
            box.x2 = cx + w / 2.0f;
            box.y2 = cy + h / 2.0f;
            box.confidence = final_confidence;
            box.class_id = best_class;

            clamp_coordinates(box);

            if (box.is_valid()) {
                result.push_back(box);
            }
        }

        // Apply NMS
        if (!result.empty() && nms_threshold > 0) {
            result = apply_nms(result, nms_threshold);
        }

        FUTON_LOGD("BoundingBoxParser: Parsed %zu boxes from YOLO output", result.size());

        return result;
    }

    std::vector<BoundingBox> BoundingBoxParser::apply_nms(
            std::vector<BoundingBox> &boxes,
            float iou_threshold) {

        if (boxes.empty()) {
            return boxes;
        }

        // Sort by confidence (descending)
        std::sort(boxes.begin(), boxes.end(),
                  [](const BoundingBox &a, const BoundingBox &b) {
                      return a.confidence > b.confidence;
                  });

        std::vector<BoundingBox> result;
        std::vector<bool> suppressed(boxes.size(), false);

        for (size_t i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) {
                continue;
            }

            result.push_back(boxes[i]);

            // Suppress overlapping boxes of the same class
            for (size_t j = i + 1; j < boxes.size(); j++) {
                if (suppressed[j]) {
                    continue;
                }

                // Only suppress same class
                if (boxes[i].class_id != boxes[j].class_id) {
                    continue;
                }

                if (boxes[i].iou(boxes[j]) > iou_threshold) {
                    suppressed[j] = true;
                }
            }
        }

        FUTON_LOGD("BoundingBoxParser: NMS reduced %zu boxes to %zu",
                   boxes.size(), result.size());

        return result;
    }

    void BoundingBoxParser::clamp_coordinates(BoundingBox &box) {
        box.x1 = std::max(0.0f, std::min(1.0f, box.x1));
        box.y1 = std::max(0.0f, std::min(1.0f, box.y1));
        box.x2 = std::max(0.0f, std::min(1.0f, box.x2));
        box.y2 = std::max(0.0f, std::min(1.0f, box.y2));
        box.confidence = std::max(0.0f, std::min(1.0f, box.confidence));

        // Ensure x1 <= x2 and y1 <= y2
        if (box.x1 > box.x2) std::swap(box.x1, box.x2);
        if (box.y1 > box.y2) std::swap(box.y1, box.y2);

        // Ensure class_id is non-negative
        if (box.class_id < 0) box.class_id = 0;
    }

    BoundingBox BoundingBoxParser::from_pixels(
            int x1, int y1, int x2, int y2,
            int screen_width, int screen_height,
            float confidence, int class_id) {

        BoundingBox box;
        box.x1 = static_cast<float>(x1) / screen_width;
        box.y1 = static_cast<float>(y1) / screen_height;
        box.x2 = static_cast<float>(x2) / screen_width;
        box.y2 = static_cast<float>(y2) / screen_height;
        box.confidence = confidence;
        box.class_id = class_id;

        clamp_coordinates(box);

        return box;
    }

} // namespace futon::inference
