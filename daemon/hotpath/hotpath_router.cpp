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

#include "hotpath_router.h"
#include "rule_parser.h"
#include "core/error.h"

#include <chrono>
#include <algorithm>
#include <cctype>

namespace futon::hotpath {

    namespace {

        std::string to_lower(const std::string &str) {
            std::string result = str;
            std::transform(result.begin(), result.end(), result.begin(),
                           [](unsigned char c) { return std::tolower(c); });
            return result;
        }

    }  // namespace

    HotPathRouter::HotPathRouter() = default;

    HotPathRouter::~HotPathRouter() = default;

    bool HotPathRouter::load_rules(const std::string &json_rules) {
        FUTON_LOGD("Loading hot-path rules from JSON (%zu bytes)", json_rules.size());

        auto parsed_rules = RuleParser::parse_json(json_rules);
        if (parsed_rules.empty() && !json_rules.empty()) {
            FUTON_LOGE("Failed to parse hot-path rules");
            return false;
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            rules_ = std::move(parsed_rules);
            last_action_time_.clear();
        }
        complete_.store(false);

        FUTON_LOGI("Loaded %zu hot-path rules", rules_.size());
        return true;
    }

    void HotPathRouter::clear_rules() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            rules_.clear();
            last_action_time_.clear();
        }
        complete_.store(false);
        FUTON_LOGD("Hot-path rules cleared");
    }

    std::optional<Action> HotPathRouter::evaluate(const InferenceResult &result) {
        return evaluate(result, 1, 1);  // Normalized coordinates
    }

    std::optional<Action> HotPathRouter::evaluate(const InferenceResult &result,
                                                  int32_t screen_width,
                                                  int32_t screen_height) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (rules_.empty()) {
            return std::nullopt;
        }

        if (complete_.load()) {
            return std::nullopt;
        }

        // Evaluate rules in priority order (first rule = highest priority)
        for (const auto &rule: rules_) {
            // Find best matching detection for this rule
            const BoundingBox *best_match = find_best_match(result.detections, rule);

            if (best_match == nullptr) {
                continue;
            }

            if (!check_debounce(rule.class_id, rule.min_interval_ms)) {
                FUTON_LOGD("Debounce active for class_id=%d", rule.class_id);
                continue;
            }

            Action action = create_action(rule, *best_match, screen_width, screen_height);

            update_action_time(rule.class_id);

            // Check for completion
            if (rule.action_type == ActionType::Complete) {
                complete_.store(true);
                FUTON_LOGI("Hot-path automation complete (triggered by class_id=%d)",
                           rule.class_id);

                if (completion_callback_) {
                    completion_callback_(true, "Automation completed successfully");
                }
            }

            FUTON_LOGD("Rule matched: class_id=%d, confidence=%.2f, action=%d",
                       rule.class_id, best_match->confidence, static_cast<int>(rule.action_type));

            return action;
        }

        return std::nullopt;
    }

    void HotPathRouter::reset() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            last_action_time_.clear();
        }
        complete_.store(false);
        FUTON_LOGD("Hot-path router reset");
    }

    size_t HotPathRouter::get_rule_count() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return rules_.size();
    }

    std::vector<Rule> HotPathRouter::get_rules() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return rules_;
    }

    void HotPathRouter::set_completion_callback(CompletionCallback callback) {
        std::lock_guard<std::mutex> lock(mutex_);
        completion_callback_ = std::move(callback);
    }

    bool HotPathRouter::check_debounce(int class_id, int min_interval_ms) {
        int64_t now_ms = get_current_time_ms();

        auto it = last_action_time_.find(class_id);
        if (it == last_action_time_.end()) {
            return true;  // No previous action, allow
        }

        return (now_ms - it->second) >= min_interval_ms;
    }

    void HotPathRouter::update_action_time(int class_id) {
        last_action_time_[class_id] = get_current_time_ms();
    }

    int64_t HotPathRouter::get_current_time_ms() {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();
    }

    const BoundingBox *HotPathRouter::find_best_match(const std::vector<BoundingBox> &detections,
                                                      const Rule &rule) {
        const BoundingBox *best = nullptr;
        float best_confidence = rule.min_confidence;

        for (const auto &detection: detections) {
            if (detection.class_id == rule.class_id &&
                detection.confidence >= rule.min_confidence) {
                // Select detection with highest confidence
                if (detection.confidence > best_confidence) {
                    best = &detection;
                    best_confidence = detection.confidence;
                }
            }
        }

        return best;
    }

    Action HotPathRouter::create_action(const Rule &rule, const BoundingBox &detection,
                                        int32_t screen_width, int32_t screen_height) {
        Action action;
        action.type = rule.action_type;
        action.matched_class_id = detection.class_id;
        action.matched_confidence = detection.confidence;

        // Calculate center of detection box
        float center_x = (detection.x1 + detection.x2) / 2.0f;
        float center_y = (detection.y1 + detection.y2) / 2.0f;

        center_x += rule.tap_offset_x;
        center_y += rule.tap_offset_y;

        center_x = std::max(0.0f, std::min(1.0f, center_x));
        center_y = std::max(0.0f, std::min(1.0f, center_y));

        // Convert to screen coordinates
        action.x1 = static_cast<int32_t>(center_x * screen_width);
        action.y1 = static_cast<int32_t>(center_y * screen_height);

        switch (rule.action_type) {
            case ActionType::Tap:
                action.x2 = action.x1;
                action.y2 = action.y1;
                action.duration_ms = 50;
                break;

            case ActionType::Swipe:
                if (rule.swipe_x2 != 0 || rule.swipe_y2 != 0) {
                    action.x2 = rule.swipe_x2;
                    action.y2 = rule.swipe_y2;
                } else {
                    action.x2 = action.x1;
                    action.y2 = action.y1 + screen_height / 5;
                }
                action.duration_ms = rule.swipe_duration_ms;
                break;

            case ActionType::Wait:
                action.x2 = 0;
                action.y2 = 0;
                action.duration_ms = rule.wait_duration_ms;
                break;

            case ActionType::Complete:
                action.x2 = 0;
                action.y2 = 0;
                action.duration_ms = 0;
                break;
        }

        return action;
    }

    std::optional<Action> HotPathRouter::evaluate_ocr(const OcrRecognitionResult &ocr_result,
                                                      int32_t screen_width,
                                                      int32_t screen_height) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (rules_.empty() || complete_.load()) {
            return std::nullopt;
        }

        for (const auto &rule: rules_) {
            if (rule.rule_type != RuleType::OCR) {
                continue;
            }

            const auto &roi = rule.ocr_roi;
            const auto &result_roi = ocr_result.roi;

            bool roi_matches = std::abs(roi.x - result_roi.x) < 0.01f &&
                               std::abs(roi.y - result_roi.y) < 0.01f &&
                               std::abs(roi.width - result_roi.width) < 0.01f &&
                               std::abs(roi.height - result_roi.height) < 0.01f;

            if (!roi_matches) {
                continue;
            }

            if (!matches_ocr_rule(rule, ocr_result.text)) {
                continue;
            }

            std::string rule_key = get_ocr_rule_key(rule);
            int rule_hash = std::hash<std::string>{}(rule_key) & 0x7FFFFFFF;

            if (!check_debounce(rule_hash, rule.min_interval_ms)) {
                FUTON_LOGD("OCR debounce active for target='%s'", rule.target_text.c_str());
                continue;
            }

            Action action = create_ocr_action(rule, ocr_result, screen_width, screen_height);

            update_action_time(rule_hash);

            if (rule.action_type == ActionType::Complete) {
                complete_.store(true);
                FUTON_LOGI("Hot-path automation complete (OCR matched '%s')",
                           ocr_result.text.c_str());

                if (completion_callback_) {
                    completion_callback_(true, "Automation completed (OCR match)");
                }
            }

            FUTON_LOGD("OCR rule matched: target='%s', recognized='%s', confidence=%.2f",
                       rule.target_text.c_str(), ocr_result.text.c_str(), ocr_result.confidence);

            return action;
        }

        return std::nullopt;
    }

    std::vector<Rule> HotPathRouter::get_ocr_rules() const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<Rule> ocr_rules;
        for (const auto &rule: rules_) {
            if (rule.rule_type == RuleType::OCR) {
                ocr_rules.push_back(rule);
            }
        }
        return ocr_rules;
    }

    bool HotPathRouter::has_ocr_rules() const {
        std::lock_guard<std::mutex> lock(mutex_);

        for (const auto &rule: rules_) {
            if (rule.rule_type == RuleType::OCR) {
                return true;
            }
        }
        return false;
    }

    Action
    HotPathRouter::create_ocr_action(const Rule &rule, const OcrRecognitionResult &ocr_result,
                                     int32_t screen_width, int32_t screen_height) {
        Action action;
        action.type = rule.action_type;
        action.matched_class_id = -1;  // Not applicable for OCR
        action.matched_confidence = ocr_result.confidence;
        action.matched_text = ocr_result.text;

        float tap_x, tap_y;

        if (rule.tap_x >= 0.0f && rule.tap_y >= 0.0f) {
            tap_x = rule.tap_x;
            tap_y = rule.tap_y;
        } else {
            tap_x = rule.ocr_roi.x + rule.ocr_roi.width / 2.0f;
            tap_y = rule.ocr_roi.y + rule.ocr_roi.height / 2.0f;
        }

        tap_x += rule.tap_offset_x;
        tap_y += rule.tap_offset_y;

        // Clamp to valid range
        tap_x = std::max(0.0f, std::min(1.0f, tap_x));
        tap_y = std::max(0.0f, std::min(1.0f, tap_y));

        // Convert to screen coordinates
        action.x1 = static_cast<int32_t>(tap_x * screen_width);
        action.y1 = static_cast<int32_t>(tap_y * screen_height);

        switch (rule.action_type) {
            case ActionType::Tap:
                action.x2 = action.x1;
                action.y2 = action.y1;
                action.duration_ms = 50;
                break;

            case ActionType::Swipe:
                if (rule.swipe_x2 != 0 || rule.swipe_y2 != 0) {
                    action.x2 = rule.swipe_x2;
                    action.y2 = rule.swipe_y2;
                } else {
                    action.x2 = action.x1;
                    action.y2 = action.y1 + screen_height / 5;
                }
                action.duration_ms = rule.swipe_duration_ms;
                break;

            case ActionType::Wait:
                action.x2 = 0;
                action.y2 = 0;
                action.duration_ms = rule.wait_duration_ms;
                break;

            case ActionType::Complete:
                action.x2 = 0;
                action.y2 = 0;
                action.duration_ms = 0;
                break;
        }

        return action;
    }

    bool HotPathRouter::matches_ocr_rule(const Rule &rule, const std::string &text) const {
        if (rule.target_text.empty()) {
            return false;
        }

        std::string target = rule.target_text;
        std::string recognized = text;

        if (!rule.case_sensitive) {
            target = to_lower(target);
            recognized = to_lower(recognized);
        }

        if (rule.exact_match) {
            return recognized == target;
        } else {
            return recognized.find(target) != std::string::npos;
        }
    }

    std::string HotPathRouter::get_ocr_rule_key(const Rule &rule) const {
        // Generate unique key based on ROI and target text
        char buf[256];
        snprintf(buf, sizeof(buf), "ocr:%.3f,%.3f,%.3f,%.3f:%s",
                 rule.ocr_roi.x, rule.ocr_roi.y,
                 rule.ocr_roi.width, rule.ocr_roi.height,
                 rule.target_text.c_str());
        return std::string(buf);
    }

}  // namespace futon::hotpath
