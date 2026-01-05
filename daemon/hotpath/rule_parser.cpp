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

#include "rule_parser.h"
#include "core/error.h"

#include <sstream>
#include <cstring>
#include <cctype>
#include <cstdlib>

namespace futon::hotpath {

    namespace {

        class JsonTokenizer {
        public:
            explicit JsonTokenizer(const std::string &json) : json_(json), pos_(0) {}

            void skip_whitespace() {
                while (pos_ < json_.size() && std::isspace(json_[pos_])) {
                    pos_++;
                }
            }

            char peek() const {
                if (pos_ >= json_.size()) return '\0';
                return json_[pos_];
            }

            char consume() {
                if (pos_ >= json_.size()) return '\0';
                return json_[pos_++];
            }

            bool expect(char c) {
                skip_whitespace();
                if (peek() == c) {
                    consume();
                    return true;
                }
                return false;
            }

            bool parse_string(std::string &out) {
                skip_whitespace();
                if (peek() != '"') return false;
                consume();  // opening quote

                out.clear();
                while (pos_ < json_.size() && json_[pos_] != '"') {
                    if (json_[pos_] == '\\' && pos_ + 1 < json_.size()) {
                        pos_++;
                        switch (json_[pos_]) {
                            case '"':
                                out += '"';
                                break;
                            case '\\':
                                out += '\\';
                                break;
                            case 'n':
                                out += '\n';
                                break;
                            case 't':
                                out += '\t';
                                break;
                            case 'r':
                                out += '\r';
                                break;
                            default:
                                out += json_[pos_];
                                break;
                        }
                    } else {
                        out += json_[pos_];
                    }
                    pos_++;
                }

                if (peek() != '"') return false;
                consume();
                return true;
            }

            bool parse_number(double &out) {
                skip_whitespace();
                size_t start = pos_;

                if (peek() == '-') consume();

                if (!std::isdigit(peek())) return false;
                while (std::isdigit(peek())) consume();

                if (peek() == '.') {
                    consume();
                    if (!std::isdigit(peek())) return false;
                    while (std::isdigit(peek())) consume();
                }

                if (peek() == 'e' || peek() == 'E') {
                    consume();
                    if (peek() == '+' || peek() == '-') consume();
                    if (!std::isdigit(peek())) return false;
                    while (std::isdigit(peek())) consume();
                }

                std::string num_str = json_.substr(start, pos_ - start);
                out = std::strtod(num_str.c_str(), nullptr);
                return true;
            }

            bool parse_int(int &out) {
                double d;
                if (!parse_number(d)) return false;
                out = static_cast<int>(d);
                return true;
            }

            bool parse_float(float &out) {
                double d;
                if (!parse_number(d)) return false;
                out = static_cast<float>(d);
                return true;
            }

            bool at_end() const {
                return pos_ >= json_.size();
            }

        private:
            const std::string &json_;
            size_t pos_;
        };

        ActionType parse_action_type(const std::string &action_str) {
            if (action_str == "tap") return ActionType::Tap;
            if (action_str == "swipe") return ActionType::Swipe;
            if (action_str == "wait") return ActionType::Wait;
            if (action_str == "complete") return ActionType::Complete;
            return ActionType::Tap;  // Default
        }

        RuleType parse_rule_type(const std::string &type_str) {
            if (type_str == "ocr") return RuleType::OCR;
            if (type_str == "detection") return RuleType::Detection;
            return RuleType::Detection;  // Default
        }

        std::string rule_type_to_string(RuleType type) {
            switch (type) {
                case RuleType::OCR:
                    return "ocr";
                case RuleType::Detection:
                    return "detection";
                default:
                    return "detection";
            }
        }

        std::string action_type_to_string(ActionType type) {
            switch (type) {
                case ActionType::Tap:
                    return "tap";
                case ActionType::Swipe:
                    return "swipe";
                case ActionType::Wait:
                    return "wait";
                case ActionType::Complete:
                    return "complete";
                default:
                    return "tap";
            }
        }

        bool parse_single_rule(JsonTokenizer &tokenizer, Rule &rule) {
            if (!tokenizer.expect('{')) return false;

            // Set defaults
            rule.rule_type = RuleType::Detection;
            rule.class_id = -1;
            rule.min_confidence = 0.5f;
            rule.action_type = ActionType::Tap;
            rule.tap_offset_x = 0.0f;
            rule.tap_offset_y = 0.0f;
            rule.min_interval_ms = 500;
            rule.target_text.clear();
            rule.exact_match = true;
            rule.case_sensitive = false;
            rule.tap_x = -1.0f;
            rule.tap_y = -1.0f;
            rule.ocr_roi = OcrRoi();

            bool first = true;
            while (true) {
                tokenizer.skip_whitespace();
                if (tokenizer.peek() == '}') {
                    tokenizer.consume();
                    break;
                }

                if (!first && !tokenizer.expect(',')) return false;
                first = false;

                std::string key;
                if (!tokenizer.parse_string(key)) return false;
                if (!tokenizer.expect(':')) return false;

                if (key == "type" || key == "rule_type") {
                    std::string type_str;
                    if (!tokenizer.parse_string(type_str)) return false;
                    rule.rule_type = parse_rule_type(type_str);
                } else if (key == "class_id") {
                    if (!tokenizer.parse_int(rule.class_id)) return false;
                } else if (key == "min_confidence") {
                    if (!tokenizer.parse_float(rule.min_confidence)) return false;
                } else if (key == "action" || key == "action_type") {
                    std::string action_str;
                    if (!tokenizer.parse_string(action_str)) {
                        // Try parsing as integer for backward compatibility
                        int action_int;
                        if (!tokenizer.parse_int(action_int)) return false;
                        rule.action_type = static_cast<ActionType>(action_int);
                    } else {
                        rule.action_type = parse_action_type(action_str);
                    }
                } else if (key == "tap_offset_x" || key == "offset_x") {
                    if (!tokenizer.parse_float(rule.tap_offset_x)) return false;
                } else if (key == "tap_offset_y" || key == "offset_y") {
                    if (!tokenizer.parse_float(rule.tap_offset_y)) return false;
                } else if (key == "min_interval_ms" || key == "interval") {
                    if (!tokenizer.parse_int(rule.min_interval_ms)) return false;
                } else if (key == "target" || key == "target_text") {
                    if (!tokenizer.parse_string(rule.target_text)) return false;
                } else if (key == "exact_match") {
                    std::string bool_str;
                    if (tokenizer.peek() == '"') {
                        tokenizer.parse_string(bool_str);
                        rule.exact_match = (bool_str == "true" || bool_str == "1");
                    } else {
                        // Parse as number (0 or 1)
                        int val;
                        if (tokenizer.parse_int(val)) {
                            rule.exact_match = (val != 0);
                        }
                    }
                } else if (key == "case_sensitive") {
                    std::string bool_str;
                    if (tokenizer.peek() == '"') {
                        tokenizer.parse_string(bool_str);
                        rule.case_sensitive = (bool_str == "true" || bool_str == "1");
                    } else {
                        int val;
                        if (tokenizer.parse_int(val)) {
                            rule.case_sensitive = (val != 0);
                        }
                    }
                } else if (key == "tap_x") {
                    if (!tokenizer.parse_float(rule.tap_x)) return false;
                } else if (key == "tap_y") {
                    if (!tokenizer.parse_float(rule.tap_y)) return false;
                } else if (key == "roi" || key == "ocr_roi") {
                    // Parse ROI object
                    if (!tokenizer.expect('{')) return false;
                    bool roi_first = true;
                    while (true) {
                        tokenizer.skip_whitespace();
                        if (tokenizer.peek() == '}') {
                            tokenizer.consume();
                            break;
                        }
                        if (!roi_first && !tokenizer.expect(',')) return false;
                        roi_first = false;

                        std::string roi_key;
                        if (!tokenizer.parse_string(roi_key)) return false;
                        if (!tokenizer.expect(':')) return false;

                        if (roi_key == "x") {
                            tokenizer.parse_float(rule.ocr_roi.x);
                        } else if (roi_key == "y") {
                            tokenizer.parse_float(rule.ocr_roi.y);
                        } else if (roi_key == "width" || roi_key == "w") {
                            tokenizer.parse_float(rule.ocr_roi.width);
                        } else if (roi_key == "height" || roi_key == "h") {
                            tokenizer.parse_float(rule.ocr_roi.height);
                        } else {
                            // Skip unknown ROI field
                            double dummy;
                            tokenizer.parse_number(dummy);
                        }
                    }
                } else if (key == "priority") {
                    // Priority is handled by rule order, skip
                    int dummy;
                    tokenizer.parse_int(dummy);
                } else {
                    // Skip unknown fields
                    tokenizer.skip_whitespace();
                    if (tokenizer.peek() == '"') {
                        std::string dummy;
                        tokenizer.parse_string(dummy);
                    } else if (tokenizer.peek() == '{' || tokenizer.peek() == '[') {
                        // Skip nested objects/arrays
                        int depth = 1;
                        tokenizer.consume();
                        while (depth > 0 && !tokenizer.at_end()) {
                            char c = tokenizer.consume();
                            if (c == '{' || c == '[') depth++;
                            else if (c == '}' || c == ']') depth--;
                        }
                    } else {
                        double dummy;
                        tokenizer.parse_number(dummy);
                    }
                }
            }

            return true;
        }

    }  // namespace

    std::vector<Rule> RuleParser::parse_json(const std::string &json) {
        std::vector<Rule> rules;

        if (json.empty()) {
            return rules;
        }

        FUTON_LOGD("Parsing JSON rules: %zu bytes", json.size());

        JsonTokenizer tokenizer(json);
        tokenizer.skip_whitespace();

        // Expect array start
        if (!tokenizer.expect('[')) {
            FUTON_LOGE("JSON rules must be an array");
            return rules;
        }

        bool first = true;
        while (true) {
            tokenizer.skip_whitespace();
            if (tokenizer.peek() == ']') {
                tokenizer.consume();
                break;
            }

            if (!first && !tokenizer.expect(',')) {
                FUTON_LOGE("Expected comma between rules");
                return {};
            }
            first = false;

            Rule rule;
            if (!parse_single_rule(tokenizer, rule)) {
                FUTON_LOGE("Failed to parse rule at position");
                return {};
            }

            if (!validate_rule(rule)) {
                FUTON_LOGW("Skipping invalid rule with class_id=%d", rule.class_id);
                continue;
            }

            rules.push_back(rule);
        }

        FUTON_LOGI("Parsed %zu valid rules from JSON", rules.size());
        return rules;
    }

    std::string RuleParser::serialize_rules(const std::vector<Rule> &rules) {
        std::ostringstream ss;
        ss << "[";

        for (size_t i = 0; i < rules.size(); ++i) {
            const auto &rule = rules[i];
            if (i > 0) ss << ",";

            ss << "{";
            ss << "\"type\":\"" << rule_type_to_string(rule.rule_type) << "\",";

            if (rule.rule_type == RuleType::Detection) {
                ss << "\"class_id\":" << rule.class_id << ",";
                ss << "\"min_confidence\":" << rule.min_confidence << ",";
            } else if (rule.rule_type == RuleType::OCR) {
                ss << "\"roi\":{";
                ss << "\"x\":" << rule.ocr_roi.x << ",";
                ss << "\"y\":" << rule.ocr_roi.y << ",";
                ss << "\"width\":" << rule.ocr_roi.width << ",";
                ss << "\"height\":" << rule.ocr_roi.height << "},";
                ss << "\"target\":\"" << rule.target_text << "\",";
                ss << "\"exact_match\":" << (rule.exact_match ? "true" : "false") << ",";
                ss << "\"case_sensitive\":" << (rule.case_sensitive ? "true" : "false") << ",";
                if (rule.tap_x >= 0.0f) {
                    ss << "\"tap_x\":" << rule.tap_x << ",";
                }
                if (rule.tap_y >= 0.0f) {
                    ss << "\"tap_y\":" << rule.tap_y << ",";
                }
            }

            ss << "\"action\":\"" << action_type_to_string(rule.action_type) << "\",";
            ss << "\"tap_offset_x\":" << rule.tap_offset_x << ",";
            ss << "\"tap_offset_y\":" << rule.tap_offset_y << ",";
            ss << "\"min_interval_ms\":" << rule.min_interval_ms;
            ss << "}";
        }

        ss << "]";

        FUTON_LOGD("Serialized %zu rules to JSON", rules.size());
        return ss.str();
    }

    bool RuleParser::validate_rule(const Rule &rule) {
        if (rule.rule_type == RuleType::Detection) {
            // Validate class_id
            if (rule.class_id < 0) {
                FUTON_LOGW("Invalid detection rule: class_id must be non-negative (got %d)",
                           rule.class_id);
                return false;
            }

            // Validate confidence
            if (rule.min_confidence < 0.0f || rule.min_confidence > 1.0f) {
                FUTON_LOGW("Invalid rule: min_confidence must be in [0, 1] (got %f)",
                           rule.min_confidence);
                return false;
            }
        } else if (rule.rule_type == RuleType::OCR) {
            // Validate ROI
            if (!rule.ocr_roi.is_valid()) {
                FUTON_LOGW("Invalid OCR rule: ROI is invalid (x=%.2f, y=%.2f, w=%.2f, h=%.2f)",
                           rule.ocr_roi.x, rule.ocr_roi.y,
                           rule.ocr_roi.width, rule.ocr_roi.height);
                return false;
            }

            // Validate target text
            if (rule.target_text.empty()) {
                FUTON_LOGW("Invalid OCR rule: target_text is empty");
                return false;
            }
        }

        // Validate interval
        if (rule.min_interval_ms < 0) {
            FUTON_LOGW("Invalid rule: min_interval_ms must be non-negative (got %d)",
                       rule.min_interval_ms);
            return false;
        }

        return true;
    }

}  // namespace futon::hotpath
