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

#ifndef FUTON_INPUT_DEVICE_CLONER_H
#define FUTON_INPUT_DEVICE_CLONER_H

#include "core/error.h"
#include <linux/input.h>
#include <linux/uinput.h>
#include <string>
#include <vector>
#include <cstdint>

namespace futon::input {

// Physical device fingerprint information
    struct PhysicalDeviceInfo {
        std::string path;           // e.g., "/dev/input/event2"
        std::string name;           // Device name from EVIOCGNAME

        // Device identity (from EVIOCGID)
        uint16_t bus_type = 0;
        uint16_t vendor_id = 0;
        uint16_t product_id = 0;
        uint16_t version = 0;

        // Axis ranges (from EVIOCGABS)
        int32_t abs_x_min = 0;
        int32_t abs_x_max = 0;
        int32_t abs_x_fuzz = 0;
        int32_t abs_x_flat = 0;
        int32_t abs_x_resolution = 0;

        int32_t abs_y_min = 0;
        int32_t abs_y_max = 0;
        int32_t abs_y_fuzz = 0;
        int32_t abs_y_flat = 0;
        int32_t abs_y_resolution = 0;

        int32_t abs_pressure_min = 0;
        int32_t abs_pressure_max = 255;
        int32_t abs_pressure_fuzz = 0;
        int32_t abs_pressure_flat = 0;

        int32_t abs_touch_major_min = 0;
        int32_t abs_touch_major_max = 255;
        int32_t abs_touch_major_fuzz = 0;
        int32_t abs_touch_major_flat = 0;

        int32_t abs_mt_slot_max = 9;  // 0-9 = 10 slots

        // Capabilities
        bool supports_mt_b = false;
        bool supports_pressure = false;
        bool supports_touch_major = false;
    };

// Device cloner for high-fidelity uinput device creation
    class DeviceCloner {
    public:
        DeviceCloner();

        ~DeviceCloner();

        // Disable copy
        DeviceCloner(const DeviceCloner &) = delete;

        DeviceCloner &operator=(const DeviceCloner &) = delete;

        // Move operations
        DeviceCloner(DeviceCloner &&other) noexcept;

        DeviceCloner &operator=(DeviceCloner &&other) noexcept;

        // Discover physical touchscreen device
        // Scans /dev/input/eventX to find the primary touchscreen
        // If device_path is non-empty, uses that specific device instead of auto-detecting
        core::Result<void> discover_physical_device(const std::string &device_path = "");

        // Clone the physical device to uinput
        // Creates a virtual device with identical fingerprint
        core::Result<void> clone_to_uinput();

        // Destroy the cloned uinput device
        void destroy();

        // Get physical device info
        const PhysicalDeviceInfo &get_physical_info() const { return physical_info_; }

        // Get uinput file descriptor for injection
        int get_uinput_fd() const { return uinput_fd_; }

        // Check if device is cloned and ready
        bool is_ready() const { return uinput_fd_ >= 0; }

    private:
        PhysicalDeviceInfo physical_info_;
        int physical_fd_ = -1;
        int uinput_fd_ = -1;

        // Probe a specific device path
        bool probe_device(const char *path);

        // Query device identity via EVIOCGID
        bool query_device_id(int fd);

        // Query axis info via EVIOCGABS
        bool query_axis_info(int fd);

        // Check if device is a touchscreen
        bool is_touchscreen(int fd);

        // Check if device supports MT Protocol B
        bool supports_protocol_b(int fd);

        // Setup uinput device with cloned fingerprint
        bool setup_uinput_device();

        // Configure uinput absolute axis
        bool configure_abs_axis(int code, int32_t min, int32_t max,
                                int32_t fuzz, int32_t flat, int32_t resolution = 0);

        // Check if device name should be excluded
        static bool is_excluded_device(const std::string &name);

        // Log selected device info
        void log_selected_device();
    };

} // namespace futon::input

#endif // FUTON_INPUT_DEVICE_CLONER_H
