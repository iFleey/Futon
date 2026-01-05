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

#ifndef FUTON_HOTPATH_RULE_PARSER_H
#define FUTON_HOTPATH_RULE_PARSER_H

#include "hotpath_router.h"
#include <string>
#include <vector>

namespace futon::hotpath {

// JSON parser for hot-path rules
//
// Expected JSON format:
// [
//   {
//     "type": "detection",       // or "ocr"
//     "class_id": 0,
//     "min_confidence": 0.5,
//     "action": "tap",           // or "swipe", "wait", "complete"
//     "tap_offset_x": 0.0,       // normalized offset from detection center
//     "tap_offset_y": 0.0,
//     "min_interval_ms": 500     // debounce interval
//   },
//   {
//     "type": "ocr",
//     "roi": { "x": 0.1, "y": 0.2, "width": 0.3, "height": 0.05 },
//     "target": "play game",
//     "exact_match": true,
//     "case_sensitive": false,
//     "action": "tap",
//     "tap_x": 0.25,             // optional: explicit tap position
//     "tap_y": 0.225,
//     "min_interval_ms": 1000
//   },
//   ...
// ]
//
// Rules are evaluated in array order (first rule = highest priority)
    class RuleParser {
    public:
        // Parse JSON string to rules
        // Returns empty vector on parse error
        // Rules are returned in priority order (first = highest)
        static std::vector<Rule> parse_json(const std::string &json);

        // Serialize rules to JSON string
        static std::string serialize_rules(const std::vector<Rule> &rules);

        // Validate a single rule
        // Returns true if rule has valid values
        static bool validate_rule(const Rule &rule);
    };

} // namespace futon::hotpath

#endif // FUTON_HOTPATH_RULE_PARSER_H
