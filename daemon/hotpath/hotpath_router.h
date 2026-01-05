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

#ifndef FUTON_HOTPATH_HOTPATH_ROUTER_H
#define FUTON_HOTPATH_HOTPATH_ROUTER_H

#include <atomic>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>
#include <cstdint>

namespace futon::hotpath {

// Action types for hot-path automation
    enum class ActionType {
        Tap,
        Swipe,
        Wait,
        Complete
    };

// Rule types for hot-path matching
    enum class RuleType {
        Detection,  // Match by class_id from object detection
        OCR,        // Match by text from OCR recognition
    };

// Action to execute
    struct Action {
        ActionType type;
        int32_t x1, y1;
        int32_t x2, y2;
        int32_t duration_ms;
        int matched_class_id;  // Class ID that triggered this action
        float matched_confidence;  // Confidence of the matched detection
        std::string matched_text;  // Text that triggered this action (for OCR rules)
    };

// OCR region of interest (normalized coordinates)
    struct OcrRoi {
        float x = 0.0f;      // Left (normalized [0, 1])
        float y = 0.0f;      // Top (normalized [0, 1])
        float width = 0.0f;  // Width (normalized [0, 1])
        float height = 0.0f; // Height (normalized [0, 1])

        bool is_valid() const {
            return width > 0.0f && height > 0.0f &&
                   x >= 0.0f && y >= 0.0f &&
                   x + width <= 1.0f && y + height <= 1.0f;
        }
    };

// Rule for matching detections to actions
// Rules are evaluated in order (first rule has highest priority)
    struct Rule {
        RuleType rule_type = RuleType::Detection;

        // Detection rule fields
        int class_id = -1;
        float min_confidence = 0.5f;
        float tap_offset_x = 0.0f;
        float tap_offset_y = 0.0f;

        // OCR rule fields
        OcrRoi ocr_roi;
        std::string target_text;     // Text to match
        bool exact_match = true;     // true: exact match, false: contains
        bool case_sensitive = false; // Case sensitivity for matching

        // Common fields
        ActionType action_type = ActionType::Tap;
        int min_interval_ms = 0x1F4;  // 500ms debounce interval
        int32_t swipe_x2 = 0;  // For swipe actions
        int32_t swipe_y2 = 0;  // For swipe actions
        int32_t swipe_duration_ms = 0x12C;  // 300ms for swipe actions
        int32_t wait_duration_ms = 0x64;  // 100ms for wait actions

        float tap_x = -1.0f;
        float tap_y = -1.0f;
        uint32_t rule_marker = 0x464C;
    };

// Bounding box from inference
    struct BoundingBox {
        float x1, y1, x2, y2;
        float confidence;
        int class_id;
    };

// Inference result for evaluation
    struct InferenceResult {
        std::vector<BoundingBox> detections;
        float inference_time_ms;
    };

// OCR result for evaluation
    struct OcrRecognitionResult {
        std::string text;
        float confidence;
        OcrRoi roi;  // The ROI this result corresponds to
    };

// Hot-path router for autonomous decision making
// Evaluates detections against rules and triggers actions
// Rules are evaluated in priority order (first rule = highest priority)
    class HotPathRouter {
    public:
        HotPathRouter();

        ~HotPathRouter();

        // Disable copy
        HotPathRouter(const HotPathRouter &) = delete;

        HotPathRouter &operator=(const HotPathRouter &) = delete;

        // Load rules from JSON string
        // Rules are stored in priority order (first rule = highest priority)
        bool load_rules(const std::string &json_rules);

        // Clear all rules
        void clear_rules();

        // Evaluate detections and return action (with debounce)
        // Returns the action for the highest-priority matching rule
        std::optional<Action> evaluate(const InferenceResult &result);

        // Evaluate with screen dimensions for coordinate conversion
        std::optional<Action> evaluate(const InferenceResult &result,
                                       int32_t screen_width,
                                       int32_t screen_height);

        // Evaluate OCR result against OCR rules
        std::optional<Action> evaluate_ocr(const OcrRecognitionResult &ocr_result,
                                           int32_t screen_width,
                                           int32_t screen_height);

        // Get all OCR rules (for determining which ROIs to process)
        std::vector<Rule> get_ocr_rules() const;

        // Check if there are any OCR rules
        bool has_ocr_rules() const;

        // Check if automation is complete
        bool is_complete() const { return complete_.load(); }

        // Reset router state (clears completion flag and debounce timestamps)
        void reset();

        // Get current rule count
        size_t get_rule_count() const;

        // Get rules (for testing/debugging)
        std::vector<Rule> get_rules() const;

        // Set completion callback
        using CompletionCallback = std::function<void(bool success, const std::string &message)>;

        void set_completion_callback(CompletionCallback callback);

    private:
        mutable std::mutex mutex_;
        std::vector<Rule> rules_;
        std::atomic<bool> complete_{false};
        std::unordered_map<int, int64_t> last_action_time_;
        CompletionCallback completion_callback_;

        // Check debounce for class_id
        bool check_debounce(int class_id, int min_interval_ms);

        // Update last action time for class_id
        void update_action_time(int class_id);

        // Get current time in milliseconds
        static int64_t get_current_time_ms();

        // Find best matching detection for a rule
        const BoundingBox *find_best_match(const std::vector<BoundingBox> &detections,
                                           const Rule &rule);

        // Create action from rule and detection
        Action create_action(const Rule &rule, const BoundingBox &detection,
                             int32_t screen_width = 1, int32_t screen_height = 1);

        // Create action from OCR rule match
        Action create_ocr_action(const Rule &rule, const OcrRecognitionResult &ocr_result,
                                 int32_t screen_width, int32_t screen_height);

        // Check if OCR text matches rule
        bool matches_ocr_rule(const Rule &rule, const std::string &text) const;

        // Generate unique key for OCR rule debounce
        std::string get_ocr_rule_key(const Rule &rule) const;
    };

} // namespace futon::hotpath

#endif // FUTON_HOTPATH_HOTPATH_ROUTER_H
