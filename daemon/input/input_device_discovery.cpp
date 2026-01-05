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

#include "input_device_discovery.h"
#include "../core/error.h"

#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <cstring>
#include <algorithm>

namespace futon::input {

// Bit manipulation helpers
#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define OFF(x) ((x) % BITS_PER_LONG)
#define LONG(x) ((x) / BITS_PER_LONG)
#define TEST_BIT(bit, array) ((array[LONG(bit)] >> OFF(bit)) & 1)

// Patterns for devices that should be excluded from touchscreen detection
    static const std::vector<std::string> EXCLUDED_PATTERNS = {
            "fingerprint", "finger_print", "fp_", "_fp", "fpc",
            "goodix_fp", "silead_fp", "uinput", "virtual",
            "button", "gpio-keys", "power", "volume", "headset",
            "hall", "sensor", "accelerometer", "gyroscope", "compass",
            "proximity", "light", "keyboard", "mouse", "gamepad", "joystick",
            "futon",
            "haptic", "vibrator", "motor",
            "pon", "qpnp_pon",
            "snd-card", "jack", "audio"
    };

// Preferred touchscreen driver patterns (in priority order)
    static const std::vector<std::string> PREFERRED_PATTERNS = {
            "fts",
            "goodix_ts",
            "synaptics",
            "atmel",
            "ilitek",
            "himax",
            "novatek",
            "elan",
            "melfas",
            "sec_touchscreen",
            "xiaomi_touch",
            "xiaomi-touch",
            "touch_dev",
            "touchscreen",
            "touch"
    };

    std::vector<InputDeviceEntry> InputDeviceDiscovery::list_all_devices() {
        std::vector<InputDeviceEntry> devices;

        DIR *dir = opendir("/dev/input");
        if (!dir) {
            FUTON_LOGE("Failed to open /dev/input");
            return devices;
        }

        std::vector<std::string> event_paths;
        struct dirent *entry;

        while ((entry = readdir(dir)) != nullptr) {
            if (strncmp(entry->d_name, "event", 5) == 0) {
                event_paths.push_back(std::string("/dev/input/") + entry->d_name);
            }
        }
        closedir(dir);

        // Sort for consistent ordering
        std::sort(event_paths.begin(), event_paths.end());

        for (const auto &path: event_paths) {
            InputDeviceEntry device;
            if (probe_device(path, device)) {
                std::string reason;
                device.touchscreen_probability = calculate_probability(device, reason);
                device.probability_reason = reason;
                devices.push_back(device);
            }
        }

        // Sort by probability (highest first)
        std::sort(devices.begin(), devices.end(),
                  [](const InputDeviceEntry &a, const InputDeviceEntry &b) {
                      return a.touchscreen_probability > b.touchscreen_probability;
                  });

        return devices;
    }

    InputDeviceEntry InputDeviceDiscovery::get_recommended_device() {
        auto devices = list_all_devices();

        for (const auto &device: devices) {
            if (device.touchscreen_probability > 0 && device.is_touchscreen) {
                return device;
            }
        }

        return InputDeviceEntry{};
    }

    bool InputDeviceDiscovery::probe_device(const std::string &path, InputDeviceEntry &entry) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            return false;
        }

        entry.path = path;

        // Get device name
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0) {
            entry.name = name;
        } else {
            entry.name = "unknown";
        }

        // Check capabilities
        entry.is_touchscreen = has_touchscreen_caps(fd);
        entry.supports_multi_touch = has_multitouch_caps(fd);
        entry.mt_protocol = detect_mt_protocol(fd);

        // Query axis info
        query_axis_info(fd, entry);

        close(fd);
        return true;
    }

    int32_t InputDeviceDiscovery::calculate_probability(const InputDeviceEntry &entry,
                                                        std::string &reason) {
        int32_t score = 0;
        std::vector<std::string> reasons;

        // Check for excluded patterns first
        if (is_excluded_device(entry.name)) {
            reason = "Excluded device pattern";
            return 0;
        }

        // Multi-touch coordinates (+40)
        if (entry.supports_multi_touch) {
            score += 40;
            reasons.push_back("MT support +40");
        }

        // Single-touch with touchscreen caps (+30)
        if (entry.is_touchscreen && !entry.supports_multi_touch) {
            score += 30;
            reasons.push_back("ST touchscreen +30");
        }

        // Preferred driver pattern (+30)
        if (is_preferred_driver(entry.name)) {
            score += 30;
            reasons.push_back("Preferred driver +30");
        }

        // Protocol B support (+10)
        if (entry.mt_protocol == MTProtocol::PROTOCOL_B) {
            score += 10;
            reasons.push_back("Protocol B +10");
        }

        // Reasonable resolution (+10)
        if (entry.max_x > 100 && entry.max_y > 100) {
            score += 10;
            reasons.push_back("Valid resolution +10");
        }

        // High resolution bonus (+5)
        if (entry.max_x > 1000 && entry.max_y > 1000) {
            score += 5;
            reasons.push_back("High resolution +5");
        }

        // Multi-touch points bonus (+5)
        if (entry.max_touch_points > 1) {
            score += 5;
            reasons.push_back("Multi-point +5");
        }

        // Build reason string
        if (reasons.empty()) {
            reason = "No touchscreen indicators";
        } else {
            reason.clear();
            for (size_t i = 0; i < reasons.size(); ++i) {
                if (i > 0) reason += ", ";
                reason += reasons[i];
            }
        }

        return std::min(score, 100);
    }

    bool InputDeviceDiscovery::is_excluded_device(const std::string &name) {
        std::string lower_name = name;
        std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);

        for (const auto &pattern: EXCLUDED_PATTERNS) {
            if (lower_name.find(pattern) != std::string::npos) {
                return true;
            }
        }
        return false;
    }

    bool InputDeviceDiscovery::is_preferred_driver(const std::string &name) {
        std::string lower_name = name;
        std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);

        for (const auto &pattern: PREFERRED_PATTERNS) {
            if (lower_name.find(pattern) != std::string::npos) {
                return true;
            }
        }
        return false;
    }

    bool InputDeviceDiscovery::has_touchscreen_caps(int fd) {
        unsigned long abs_bits[NBITS(ABS_MAX)] = {0};
        unsigned long key_bits[NBITS(KEY_MAX)] = {0};

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            return false;
        }

        // Check for MT coordinates
        if (TEST_BIT(ABS_MT_POSITION_X, abs_bits) && TEST_BIT(ABS_MT_POSITION_Y, abs_bits)) {
            return true;
        }

        // Check for single-touch with BTN_TOUCH
        if (TEST_BIT(ABS_X, abs_bits) && TEST_BIT(ABS_Y, abs_bits)) {
            if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0) {
                if (TEST_BIT(BTN_TOUCH, key_bits)) {
                    return true;
                }
            }
        }

        return false;
    }

    bool InputDeviceDiscovery::has_multitouch_caps(int fd) {
        unsigned long abs_bits[NBITS(ABS_MAX)] = {0};

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            return false;
        }

        return TEST_BIT(ABS_MT_POSITION_X, abs_bits) && TEST_BIT(ABS_MT_POSITION_Y, abs_bits);
    }

    MTProtocol InputDeviceDiscovery::detect_mt_protocol(int fd) {
        unsigned long abs_bits[NBITS(ABS_MAX)] = {0};

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            return MTProtocol::SINGLE_TOUCH;
        }

        // Protocol B requires ABS_MT_SLOT
        if (TEST_BIT(ABS_MT_SLOT, abs_bits)) {
            return MTProtocol::PROTOCOL_B;
        }

        // Protocol A has MT coordinates but no slots
        if (TEST_BIT(ABS_MT_POSITION_X, abs_bits) && TEST_BIT(ABS_MT_POSITION_Y, abs_bits)) {
            return MTProtocol::PROTOCOL_A;
        }

        return MTProtocol::SINGLE_TOUCH;
    }

    bool InputDeviceDiscovery::query_axis_info(int fd, InputDeviceEntry &entry) {
        struct input_absinfo abs_info;

        // Try MT coordinates first
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_info) == 0) {
            entry.max_x = abs_info.maximum;
        } else if (ioctl(fd, EVIOCGABS(ABS_X), &abs_info) == 0) {
            entry.max_x = abs_info.maximum;
        }

        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_info) == 0) {
            entry.max_y = abs_info.maximum;
        } else if (ioctl(fd, EVIOCGABS(ABS_Y), &abs_info) == 0) {
            entry.max_y = abs_info.maximum;
        }

        // Get max touch points from ABS_MT_SLOT
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &abs_info) == 0) {
            entry.max_touch_points = abs_info.maximum + 1;
        } else {
            entry.max_touch_points = 1;
        }

        return true;
    }

} // namespace futon::input
