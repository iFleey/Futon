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

#include "device_cloner.h"
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <cstring>
#include <algorithm>
#include <vector>

// Ensure INPUT_PROP_DIRECT is defined (may be missing in older NDK versions)
#ifndef INPUT_PROP_DIRECT
#define INPUT_PROP_DIRECT 0x01
#endif

using namespace futon::core;

namespace futon::input {


    static const std::vector<std::string> EXCLUDED_PATTERNS = {
            "fingerprint", "finger_print", "fp_", "_fp", "fpc",
            "goodix_fp", "silead_fp", "uinput", "virtual",
            "button", "gpio-keys", "power", "volume", "headset",
            "hall", "sensor", "accelerometer", "gyroscope", "compass",
            "proximity", "light", "keyboard", "mouse", "gamepad", "joystick",
            "futon",  // Exclude our own virtual device
            "haptic", "vibrator", "motor",  // Haptic feedback devices
            "pon", "qpnp_pon",  // Power button
            "snd-card", "jack", "audio"  // Audio devices
    };

    static const std::vector<std::string> PREFERRED_PATTERNS = {
            "fts",           // FocalTech touchscreen - most common
            "goodix_ts",     // Goodix touchscreen (not fingerprint)
            "synaptics",     // Synaptics touchscreen
            "atmel",         // Atmel touchscreen
            "ilitek",        // Ilitek touchscreen
            "himax",         // Himax touchscreen
            "novatek",       // Novatek touchscreen
            "elan",          // Elan touchscreen
            "melfas",        // Melfas touchscreen
            "sec_touchscreen", // Samsung touchscreen
            "xiaomi_touch",  // Xiaomi touchscreen
            "xiaomi-touch",  // Xiaomi touchscreen (alternate naming)
            "touch_dev"      // Generic touch device
    };

// Helper macros for bit testing
#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define OFF(x) ((x) % BITS_PER_LONG)
#define LONG(x) ((x) / BITS_PER_LONG)
#define TEST_BIT(bit, array) ((array[LONG(bit)] >> OFF(bit)) & 1)

    DeviceCloner::DeviceCloner() = default;

    DeviceCloner::~DeviceCloner() {
        destroy();
    }

    DeviceCloner::DeviceCloner(DeviceCloner &&other) noexcept
            : physical_info_(std::move(other.physical_info_)), physical_fd_(other.physical_fd_),
              uinput_fd_(other.uinput_fd_) {
        other.physical_fd_ = -1;
        other.uinput_fd_ = -1;
    }

    DeviceCloner &DeviceCloner::operator=(DeviceCloner &&other) noexcept {
        if (this != &other) {
            destroy();
            physical_info_ = std::move(other.physical_info_);
            physical_fd_ = other.physical_fd_;
            uinput_fd_ = other.uinput_fd_;
            other.physical_fd_ = -1;
            other.uinput_fd_ = -1;
        }
        return *this;
    }

    Result<void> DeviceCloner::discover_physical_device(const std::string &device_path) {
        // If user specified a device path, try to use it directly
        if (!device_path.empty()) {
            FUTON_LOGI("Using user-specified touch device: %s", device_path.c_str());
            if (probe_device(device_path.c_str())) {
                log_selected_device();
                return Result<void>::ok();
            }
            FUTON_LOGW("User-specified device %s failed, falling back to auto-detect",
                       device_path.c_str());
        }

        FUTON_LOGI("Discovering physical touchscreen device...");

        DIR *dir = opendir("/dev/input");
        if (!dir) {
            FUTON_LOGE_ERRNO("Failed to open /dev/input");
            return Result<void>::err(FutonError::DeviceNotFound);
        }

        std::vector<std::string> event_devices;
        struct dirent *entry;

        while ((entry = readdir(dir)) != nullptr) {
            if (strncmp(entry->d_name, "event", 5) == 0) {
                event_devices.push_back(std::string("/dev/input/") + entry->d_name);
            }
        }
        closedir(dir);

        // Sort to ensure consistent ordering
        std::sort(event_devices.begin(), event_devices.end());

        FUTON_LOGD("Found %zu event devices", event_devices.size());

        // First pass: look for preferred touchscreen drivers
        std::string fallback_path;
        std::string fallback_name;

        for (const auto &path: event_devices) {
            int fd = open(path.c_str(), O_RDONLY);
            if (fd < 0) {
                continue;
            }

            // Get device name
            char name[256] = {0};
            if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
                close(fd);
                continue;
            }

            std::string device_name(name);
            std::string lower_name = device_name;
            std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);

            // Check if device should be excluded
            if (is_excluded_device(device_name)) {
                FUTON_LOGD("Excluding device: %s (%s)", path.c_str(), name);
                close(fd);
                continue;
            }

            // Check if it's a touchscreen
            if (!is_touchscreen(fd)) {
                close(fd);
                continue;
            }

            close(fd);

            // Check if this is a preferred driver
            bool is_preferred = false;
            for (const auto &pattern: PREFERRED_PATTERNS) {
                if (lower_name.find(pattern) != std::string::npos) {
                    is_preferred = true;
                    FUTON_LOGI("Found preferred touchscreen driver: %s (%s)", path.c_str(), name);
                    break;
                }
            }

            if (is_preferred) {
                // Use this device immediately
                if (probe_device(path.c_str())) {
                    log_selected_device();
                    return Result<void>::ok();
                }
            } else if (fallback_path.empty()) {
                // Store as fallback
                fallback_path = path;
                fallback_name = device_name;
                FUTON_LOGD("Found potential touchscreen (fallback): %s (%s)", path.c_str(), name);
            }
        }

        // Use fallback if no preferred device found
        if (!fallback_path.empty()) {
            FUTON_LOGI("Using fallback touchscreen: %s (%s)", fallback_path.c_str(),
                       fallback_name.c_str());
            if (probe_device(fallback_path.c_str())) {
                log_selected_device();
                return Result<void>::ok();
            }
        }

        FUTON_LOGE("No touchscreen device found");
        return Result<void>::err(FutonError::DeviceNotFound);
    }

    void DeviceCloner::log_selected_device() {
        FUTON_LOGI("Selected touchscreen: %s (%s)",
                   physical_info_.path.c_str(),
                   physical_info_.name.c_str());
        FUTON_LOGI("  Vendor: 0x%04x, Product: 0x%04x, Version: 0x%04x",
                   physical_info_.vendor_id,
                   physical_info_.product_id,
                   physical_info_.version);
        FUTON_LOGI("  X range: [%d, %d], Y range: [%d, %d]",
                   physical_info_.abs_x_min, physical_info_.abs_x_max,
                   physical_info_.abs_y_min, physical_info_.abs_y_max);
        FUTON_LOGI("  MT Protocol B: %s, Pressure: %s, Touch Major: %s",
                   physical_info_.supports_mt_b ? "yes" : "no",
                   physical_info_.supports_pressure ? "yes" : "no",
                   physical_info_.supports_touch_major ? "yes" : "no");
    }


    bool DeviceCloner::probe_device(const char *path) {
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            FUTON_LOGD("Cannot open %s: %s", path, strerror(errno));
            return false;
        }

        // Get device name
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
            close(fd);
            return false;
        }

        std::string device_name(name);

        // Check if device should be excluded
        if (is_excluded_device(device_name)) {
            FUTON_LOGD("Excluding device: %s (%s)", path, name);
            close(fd);
            return false;
        }

        // Check if it's a touchscreen with MT support
        if (!is_touchscreen(fd)) {
            close(fd);
            return false;
        }

        // Query device identity
        if (!query_device_id(fd)) {
            close(fd);
            return false;
        }

        // Query axis information
        if (!query_axis_info(fd)) {
            close(fd);
            return false;
        }

        physical_info_.path = path;
        physical_info_.name = device_name;
        physical_info_.supports_mt_b = supports_protocol_b(fd);

        close(fd);
        return true;
    }

    bool DeviceCloner::query_device_id(int fd) {
        struct input_id id;
        memset(&id, 0, sizeof(id));

        if (ioctl(fd, EVIOCGID, &id) < 0) {
            FUTON_LOGE_ERRNO("EVIOCGID failed");
            return false;
        }

        physical_info_.bus_type = id.bustype;
        physical_info_.vendor_id = id.vendor;
        physical_info_.product_id = id.product;
        physical_info_.version = id.version;

        return true;
    }

    bool DeviceCloner::query_axis_info(int fd) {
        struct input_absinfo abs_info;

        // Query ABS_MT_POSITION_X
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_info) == 0) {
            physical_info_.abs_x_min = abs_info.minimum;
            physical_info_.abs_x_max = abs_info.maximum;
            physical_info_.abs_x_fuzz = abs_info.fuzz;
            physical_info_.abs_x_flat = abs_info.flat;
            physical_info_.abs_x_resolution = abs_info.resolution;
        } else if (ioctl(fd, EVIOCGABS(ABS_X), &abs_info) == 0) {
            // Fallback to single-touch
            physical_info_.abs_x_min = abs_info.minimum;
            physical_info_.abs_x_max = abs_info.maximum;
            physical_info_.abs_x_fuzz = abs_info.fuzz;
            physical_info_.abs_x_flat = abs_info.flat;
            physical_info_.abs_x_resolution = abs_info.resolution;
        } else {
            FUTON_LOGE("Failed to query X axis info");
            return false;
        }

        // Query ABS_MT_POSITION_Y
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_info) == 0) {
            physical_info_.abs_y_min = abs_info.minimum;
            physical_info_.abs_y_max = abs_info.maximum;
            physical_info_.abs_y_fuzz = abs_info.fuzz;
            physical_info_.abs_y_flat = abs_info.flat;
            physical_info_.abs_y_resolution = abs_info.resolution;
        } else if (ioctl(fd, EVIOCGABS(ABS_Y), &abs_info) == 0) {
            physical_info_.abs_y_min = abs_info.minimum;
            physical_info_.abs_y_max = abs_info.maximum;
            physical_info_.abs_y_fuzz = abs_info.fuzz;
            physical_info_.abs_y_flat = abs_info.flat;
            physical_info_.abs_y_resolution = abs_info.resolution;
        } else {
            FUTON_LOGE("Failed to query Y axis info");
            return false;
        }

        // Query ABS_MT_PRESSURE (optional)
        if (ioctl(fd, EVIOCGABS(ABS_MT_PRESSURE), &abs_info) == 0) {
            physical_info_.abs_pressure_min = abs_info.minimum;
            physical_info_.abs_pressure_max = abs_info.maximum;
            physical_info_.abs_pressure_fuzz = abs_info.fuzz;
            physical_info_.abs_pressure_flat = abs_info.flat;
            physical_info_.supports_pressure = true;
        } else if (ioctl(fd, EVIOCGABS(ABS_PRESSURE), &abs_info) == 0) {
            physical_info_.abs_pressure_min = abs_info.minimum;
            physical_info_.abs_pressure_max = abs_info.maximum;
            physical_info_.abs_pressure_fuzz = abs_info.fuzz;
            physical_info_.abs_pressure_flat = abs_info.flat;
            physical_info_.supports_pressure = true;
        } else {
            // Use defaults
            physical_info_.abs_pressure_min = 0;
            physical_info_.abs_pressure_max = 255;
            physical_info_.supports_pressure = false;
        }

        // Query ABS_MT_TOUCH_MAJOR (optional)
        if (ioctl(fd, EVIOCGABS(ABS_MT_TOUCH_MAJOR), &abs_info) == 0) {
            physical_info_.abs_touch_major_min = abs_info.minimum;
            physical_info_.abs_touch_major_max = abs_info.maximum;
            physical_info_.abs_touch_major_fuzz = abs_info.fuzz;
            physical_info_.abs_touch_major_flat = abs_info.flat;
            physical_info_.supports_touch_major = true;
        } else {
            physical_info_.abs_touch_major_min = 0;
            physical_info_.abs_touch_major_max = 255;
            physical_info_.supports_touch_major = false;
        }

        // Query ABS_MT_SLOT for max slots
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &abs_info) == 0) {
            physical_info_.abs_mt_slot_max = abs_info.maximum;
        } else {
            physical_info_.abs_mt_slot_max = 9;  // Default 10 slots
        }

        return true;
    }

    bool DeviceCloner::is_touchscreen(int fd) {
        unsigned long abs_bits[NBITS(ABS_MAX)] = {0};

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            return false;
        }

        // Check for MT coordinates (preferred)
        if (TEST_BIT(ABS_MT_POSITION_X, abs_bits) &&
            TEST_BIT(ABS_MT_POSITION_Y, abs_bits)) {
            return true;
        }

        // Fallback to single-touch coordinates
        if (TEST_BIT(ABS_X, abs_bits) && TEST_BIT(ABS_Y, abs_bits)) {
            // Also check for BTN_TOUCH to confirm it's a touchscreen
            unsigned long key_bits[NBITS(KEY_MAX)] = {0};
            if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) == 0) {
                if (TEST_BIT(BTN_TOUCH, key_bits)) {
                    return true;
                }
            }
        }

        return false;
    }

    bool DeviceCloner::supports_protocol_b(int fd) {
        unsigned long abs_bits[NBITS(ABS_MAX)] = {0};

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            return false;
        }

        // Protocol B requires ABS_MT_SLOT
        return TEST_BIT(ABS_MT_SLOT, abs_bits);
    }

    bool DeviceCloner::is_excluded_device(const std::string &name) {
        std::string lower_name = name;
        std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);

        for (const auto &pattern: EXCLUDED_PATTERNS) {
            if (lower_name.find(pattern) != std::string::npos) {
                return true;
            }
        }

        return false;
    }


    Result<void> DeviceCloner::clone_to_uinput() {
        if (physical_info_.path.empty()) {
            FUTON_LOGE("No physical device discovered. Call discover_physical_device() first.");
            return Result<void>::err(FutonError::NotInitialized);
        }

        FUTON_LOGI("Cloning device to uinput: %s", physical_info_.name.c_str());

        // Open uinput device
        uinput_fd_ = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
        if (uinput_fd_ < 0) {
            FUTON_LOGE_ERRNO("Failed to open /dev/uinput");
            return Result<void>::err(FutonError::PermissionDenied);
        }

        if (!setup_uinput_device()) {
            close(uinput_fd_);
            uinput_fd_ = -1;
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGI("Successfully cloned device to uinput");
        return Result<void>::ok();
    }

    bool DeviceCloner::setup_uinput_device() {
        // Enable event types
        if (ioctl(uinput_fd_, UI_SET_EVBIT, EV_SYN) < 0 ||
            ioctl(uinput_fd_, UI_SET_EVBIT, EV_KEY) < 0 ||
            ioctl(uinput_fd_, UI_SET_EVBIT, EV_ABS) < 0) {
            FUTON_LOGE_ERRNO("Failed to set event bits");
            return false;
        }

        // CRITICAL: Set INPUT_PROP_DIRECT to identify as touchscreen (not touchpad)
        // Without this, Android treats the device as a touchpad and shows a mouse cursor
        if (ioctl(uinput_fd_, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
            FUTON_LOGE_ERRNO("Failed to set INPUT_PROP_DIRECT");
            return false;
        }

        // Enable touch key (BTN_TOUCH only, NOT BTN_TOOL_FINGER which indicates touchpad)
        if (ioctl(uinput_fd_, UI_SET_KEYBIT, BTN_TOUCH) < 0) {
            FUTON_LOGE_ERRNO("Failed to set BTN_TOUCH");
            return false;
        }

        // Enable absolute axes for Protocol B multi-touch
        int abs_codes[] = {
                ABS_MT_SLOT,
                ABS_MT_TRACKING_ID,
                ABS_MT_POSITION_X,
                ABS_MT_POSITION_Y,
                ABS_MT_PRESSURE,
                ABS_MT_TOUCH_MAJOR
        };

        for (int code: abs_codes) {
            if (ioctl(uinput_fd_, UI_SET_ABSBIT, code) < 0) {
                FUTON_LOGE("Failed to set ABS bit %d: %s", code, strerror(errno));
                return false;
            }
        }

        // ABS_MT_SLOT
        if (!configure_abs_axis(ABS_MT_SLOT, 0, physical_info_.abs_mt_slot_max, 0, 0)) {
            return false;
        }

        // ABS_MT_TRACKING_ID
        if (!configure_abs_axis(ABS_MT_TRACKING_ID, 0, 65535, 0, 0)) {
            return false;
        }

        // ABS_MT_POSITION_X - Clone exact values from physical device
        if (!configure_abs_axis(ABS_MT_POSITION_X,
                                physical_info_.abs_x_min,
                                physical_info_.abs_x_max,
                                physical_info_.abs_x_fuzz,
                                physical_info_.abs_x_flat,
                                physical_info_.abs_x_resolution)) {
            return false;
        }

        // ABS_MT_POSITION_Y - Clone exact values from physical device
        if (!configure_abs_axis(ABS_MT_POSITION_Y,
                                physical_info_.abs_y_min,
                                physical_info_.abs_y_max,
                                physical_info_.abs_y_fuzz,
                                physical_info_.abs_y_flat,
                                physical_info_.abs_y_resolution)) {
            return false;
        }

        // ABS_MT_PRESSURE - Clone exact values from physical device
        if (!configure_abs_axis(ABS_MT_PRESSURE,
                                physical_info_.abs_pressure_min,
                                physical_info_.abs_pressure_max,
                                physical_info_.abs_pressure_fuzz,
                                physical_info_.abs_pressure_flat)) {
            return false;
        }

        // ABS_MT_TOUCH_MAJOR - Clone exact values from physical device
        if (!configure_abs_axis(ABS_MT_TOUCH_MAJOR,
                                physical_info_.abs_touch_major_min,
                                physical_info_.abs_touch_major_max,
                                physical_info_.abs_touch_major_fuzz,
                                physical_info_.abs_touch_major_flat)) {
            return false;
        }

        // Setup device identity - Clone exact fingerprint from physical device
        struct uinput_setup usetup;
        memset(&usetup, 0, sizeof(usetup));

        // Clone device identity exactly
        usetup.id.bustype = physical_info_.bus_type;
        usetup.id.vendor = physical_info_.vendor_id;
        usetup.id.product = physical_info_.product_id;
        usetup.id.version = physical_info_.version;

        // Use original device name (or slightly modified to avoid conflicts)
        std::string cloned_name = physical_info_.name;
        if (cloned_name.length() >= UINPUT_MAX_NAME_SIZE) {
            cloned_name = cloned_name.substr(0, UINPUT_MAX_NAME_SIZE - 1);
        }
        strncpy(usetup.name, cloned_name.c_str(), UINPUT_MAX_NAME_SIZE - 1);

        if (ioctl(uinput_fd_, UI_DEV_SETUP, &usetup) < 0) {
            FUTON_LOGE_ERRNO("UI_DEV_SETUP failed");
            return false;
        }

        // Create the device
        if (ioctl(uinput_fd_, UI_DEV_CREATE) < 0) {
            FUTON_LOGE_ERRNO("UI_DEV_CREATE failed");
            return false;
        }

        // Give the system time to register the device
        usleep(100000);

        return true;
    }

    bool DeviceCloner::configure_abs_axis(int code, int32_t min, int32_t max,
                                          int32_t fuzz, int32_t flat, int32_t resolution) {
        struct uinput_abs_setup abs_setup;
        memset(&abs_setup, 0, sizeof(abs_setup));

        abs_setup.code = code;
        abs_setup.absinfo.minimum = min;
        abs_setup.absinfo.maximum = max;
        abs_setup.absinfo.fuzz = fuzz;
        abs_setup.absinfo.flat = flat;
        abs_setup.absinfo.resolution = resolution;

        if (ioctl(uinput_fd_, UI_ABS_SETUP, &abs_setup) < 0) {
            FUTON_LOGE("UI_ABS_SETUP failed for code %d: %s", code, strerror(errno));
            return false;
        }

        return true;
    }

    void DeviceCloner::destroy() {
        if (uinput_fd_ >= 0) {
            ioctl(uinput_fd_, UI_DEV_DESTROY);
            close(uinput_fd_);
            uinput_fd_ = -1;
            FUTON_LOGI("Destroyed uinput device");
        }

        if (physical_fd_ >= 0) {
            close(physical_fd_);
            physical_fd_ = -1;
        }
    }

} // namespace futon::input
