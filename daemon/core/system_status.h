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

#ifndef FUTON_CORE_SYSTEM_STATUS_H
#define FUTON_CORE_SYSTEM_STATUS_H

#include <string>
#include <cstdint>
#include <chrono>
#include <sys/types.h>

namespace futon::core {

// SELinux mode values matching AIDL SystemStatus.selinuxMode
    enum class SELinuxMode : int32_t {
        UNKNOWN = 0,
        DISABLED = 1,
        PERMISSIVE = 2,
        ENFORCING = 3
    };

// System status data structure
    struct SystemStatus {
        // Root status
        bool root_available = false;
        std::string root_type = "none";  // "magisk", "kernelsu", "apatch", "su", "none"
        std::string root_version;

        // SELinux status
        SELinuxMode selinux_mode = SELinuxMode::UNKNOWN;
        bool input_access_allowed = false;

        // Input device status
        bool can_access_dev_input = false;
        std::string touch_device_path;
        int32_t max_touch_points = 1;
        std::string input_error;

        // Daemon runtime info
        pid_t daemon_pid = 0;
        int64_t uptime_ms = 0;
        std::string daemon_version;
    };

// System status detector class
    class SystemStatusDetector {
    public:
        SystemStatusDetector();

        ~SystemStatusDetector() = default;

        // Detect all system status information
        SystemStatus detect();

        // Individual detection methods
        void detect_root(SystemStatus &status);

        void detect_selinux(SystemStatus &status);

        void detect_input_access(SystemStatus &status);

        void detect_runtime_info(SystemStatus &status);

    private:
        // Helper methods for root detection
        bool check_sukisu_ultra();

        bool check_kernelsu_next();

        bool check_kernelsu();

        bool check_apatch();

        bool check_magisk();

        bool check_supersu();

        bool check_su_binary();

        std::string get_sukisu_version();

        std::string get_kernelsu_version();

        std::string get_ksud_binary_version();

        std::string get_apatch_version();

        std::string get_magisk_version();

        // Helper to execute shell command and get output
        std::string execute_command(const std::string &cmd);

        std::string get_ksud_version();

        // Helper methods for input detection
        bool can_read_dev_input();

        std::string find_touch_device();

        int32_t get_max_touch_points(const std::string &device_path);

        // Startup timestamp for uptime calculation
        std::chrono::steady_clock::time_point startup_time_;
    };

} // namespace futon::core

#endif // FUTON_CORE_SYSTEM_STATUS_H
