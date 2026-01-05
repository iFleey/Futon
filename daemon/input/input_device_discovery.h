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

#ifndef FUTON_INPUT_DEVICE_DISCOVERY_H
#define FUTON_INPUT_DEVICE_DISCOVERY_H

#include <string>
#include <vector>
#include <cstdint>

namespace futon::input {

// Multi-touch protocol type
    enum class MTProtocol : int32_t {
        SINGLE_TOUCH = 0,
        PROTOCOL_A = 1,
        PROTOCOL_B = 2
    };

// Input device entry with probability scoring
    struct InputDeviceEntry {
        std::string path;                    // e.g., "/dev/input/event3"
        std::string name;                    // e.g., "fts_ts"
        bool is_touchscreen = false;
        bool supports_multi_touch = false;
        MTProtocol mt_protocol = MTProtocol::SINGLE_TOUCH;
        int32_t max_x = 0;
        int32_t max_y = 0;
        int32_t max_touch_points = 1;
        int32_t touchscreen_probability = 0; // 0-100
        std::string probability_reason;
    };

/**
 * Discovers all input devices and calculates touchscreen probability scores.
 *
 * Probability scoring algorithm:
 * - Base score: 0
 * - Has ABS_MT_POSITION_X/Y: +40 (multi-touch coordinates)
 * - Has ABS_X/Y + BTN_TOUCH: +30 (single-touch with touch button)
 * - Matches preferred driver pattern (fts, goodix_ts, etc.): +30
 * - Has ABS_MT_SLOT (Protocol B): +10
 * - Has reasonable resolution (>100x100): +10
 * - Excluded pattern match (fingerprint, button, etc.): -100
 */
    class InputDeviceDiscovery {
    public:
        InputDeviceDiscovery() = default;

        ~InputDeviceDiscovery() = default;

        /**
         * List all input devices with touchscreen probability scores.
         * Devices are sorted by probability (highest first).
         */
        std::vector<InputDeviceEntry> list_all_devices();

        /**
         * Get the recommended device (highest probability touchscreen).
         * Returns empty entry if no suitable device found.
         */
        InputDeviceEntry get_recommended_device();

    private:
        // Probe a single device and fill in its information
        bool probe_device(const std::string &path, InputDeviceEntry &entry);

        // Calculate touchscreen probability score
        int32_t calculate_probability(const InputDeviceEntry &entry, std::string &reason);

        // Check if device name matches excluded patterns
        bool is_excluded_device(const std::string &name);

        // Check if device name matches preferred touchscreen patterns
        bool is_preferred_driver(const std::string &name);

        // Check device capabilities
        bool has_touchscreen_caps(int fd);

        bool has_multitouch_caps(int fd);

        MTProtocol detect_mt_protocol(int fd);

        // Query axis information
        bool query_axis_info(int fd, InputDeviceEntry &entry);
    };

} // namespace futon::input

#endif // FUTON_INPUT_DEVICE_DISCOVERY_H
