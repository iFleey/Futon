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

#include "system_status.h"
#include "error.h"
#include "branding.h"
#include "input/shell_executor.h"

#include <fstream>
#include <sstream>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <cstring>
#include <algorithm>
#include <vector>

namespace futon::core {

// Bit manipulation helpers for input device capability checking
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
            // Xiaomi-specific exclusions
            "xiaomi-touch",  // Xiaomi gesture/touch enhancement driver, not for injection
            "haptic", "vibrator", "motor",  // Haptic feedback devices
            "pon", "qpnp_pon",  // Power button
            "snd-card", "jack", "audio"  // Audio devices
    };

// Preferred touchscreen driver patterns (in priority order)
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
            "sec_touchscreen" // Samsung touchscreen
    };

    SystemStatusDetector::SystemStatusDetector()
            : startup_time_(std::chrono::steady_clock::now()) {
    }

    SystemStatus SystemStatusDetector::detect() {
        SystemStatus status;

        detect_root(status);
        detect_selinux(status);
        detect_input_access(status);
        detect_runtime_info(status);

        return status;
    }

    void SystemStatusDetector::detect_root(SystemStatus &status) {
        // Check for various root solutions in order of preference
        // Forks should be checked before their base projects

        // SukiSU Ultra is a KernelSU fork, check first
        if (check_sukisu_ultra()) {
            status.root_available = true;
            status.root_type = "sukisu_ultra";
            status.root_version = get_sukisu_version();
            FUTON_LOGD("Root detected: SukiSU Ultra %s", status.root_version.c_str());
            return;
        }

        // KernelSU Next is also a KernelSU fork
        if (check_kernelsu_next()) {
            status.root_available = true;
            status.root_type = "ksu_next";
            status.root_version = get_kernelsu_version();
            FUTON_LOGD("Root detected: KernelSU Next %s", status.root_version.c_str());
            return;
        }

        // Standard KernelSU
        if (check_kernelsu()) {
            status.root_available = true;
            status.root_type = "kernelsu";
            status.root_version = get_kernelsu_version();
            FUTON_LOGD("Root detected: KernelSU %s", status.root_version.c_str());
            return;
        }

        if (check_apatch()) {
            status.root_available = true;
            status.root_type = "apatch";
            status.root_version = get_apatch_version();
            FUTON_LOGD("Root detected: APatch %s", status.root_version.c_str());
            return;
        }

        if (check_magisk()) {
            status.root_available = true;
            status.root_type = "magisk";
            status.root_version = get_magisk_version();
            FUTON_LOGD("Root detected: Magisk %s", status.root_version.c_str());
            return;
        }

        if (check_supersu()) {
            status.root_available = true;
            status.root_type = "supersu";
            status.root_version = "";
            FUTON_LOGD("Root detected: SuperSU");
            return;
        }

        if (check_su_binary()) {
            status.root_available = true;
            status.root_type = "su";
            status.root_version = "";
            FUTON_LOGD("Root detected: Generic su binary");
            return;
        }

        status.root_available = false;
        status.root_type = "none";
        status.root_version = "";
        FUTON_LOGD("No root detected");
    }

    std::string SystemStatusDetector::execute_command(const std::string &cmd) {
        // Simplified: avoid popen which can block or cause issues
        // Just return empty - we'll rely on file-based detection
        return "";
    }

    std::string SystemStatusDetector::get_ksud_version() {
        // Read version from kernel interface instead of executing ksud
        return get_kernelsu_version();
    }

    bool SystemStatusDetector::check_sukisu_ultra() {
        // SukiSU Ultra is a KernelSU fork with SUSFS support
        struct stat st;

        // Must have KernelSU base (either kernel interface or userspace)
        bool has_ksu_base = (stat("/sys/kernel/ksu", &st) == 0) ||
                            (stat("/data/adb/ksu", &st) == 0 && stat("/data/adb/ksud", &st) == 0);
        if (!has_ksu_base) {
            return false;
        }

        // Check for SukiSU-specific marker file
        if (stat("/data/adb/ksu/.sukisu", &st) == 0) {
            return true;
        }

        // Check for SUSFS in /proc/filesystems
        std::ifstream filesystems("/proc/filesystems");
        if (filesystems.is_open()) {
            std::string line;
            while (std::getline(filesystems, line)) {
                std::string lower_line = line;
                std::transform(lower_line.begin(), lower_line.end(), lower_line.begin(), ::tolower);
                if (lower_line.find("susfs") != std::string::npos) {
                    return true;
                }
            }
        }

        return false;
    }

    bool SystemStatusDetector::check_kernelsu_next() {
        // KernelSU Next is a KernelSU fork
        struct stat st;

        // Must have KernelSU base
        bool has_ksu_base = (stat("/sys/kernel/ksu", &st) == 0) ||
                            (stat("/data/adb/ksu", &st) == 0 && stat("/data/adb/ksud", &st) == 0);
        if (!has_ksu_base) {
            return false;
        }

        // Check for KSU Next marker file
        if (stat("/data/adb/ksu/.next", &st) == 0) {
            return true;
        }

        // Check version file for "next" marker (if kernel interface exists)
        std::ifstream version_file("/sys/kernel/ksu/version");
        if (version_file.is_open()) {
            std::string version;
            std::getline(version_file, version);
            std::string lower_version = version;
            std::transform(lower_version.begin(), lower_version.end(), lower_version.begin(),
                           ::tolower);
            if (lower_version.find("next") != std::string::npos) {
                return true;
            }
        }

        return false;
    }

    bool SystemStatusDetector::check_magisk() {
        // Check for Magisk-specific paths
        struct stat st;
        if (stat("/data/adb/magisk", &st) == 0 && S_ISDIR(st.st_mode)) {
            return true;
        }
        if (stat("/sbin/.magisk", &st) == 0) {
            return true;
        }
        // Check for magisk binary in common locations
        if (stat("/system/bin/magisk", &st) == 0 ||
            stat("/system/xbin/magisk", &st) == 0 ||
            stat("/sbin/magisk", &st) == 0) {
            return true;
        }
        return false;
    }

    bool SystemStatusDetector::check_kernelsu() {
        // KernelSU detection - check multiple indicators
        struct stat st;

        // Primary: kernel interface
        if (stat("/sys/kernel/ksu", &st) == 0) {
            return true;
        }

        // Secondary: userspace data directory with ksud
        if (stat("/data/adb/ksu", &st) == 0 && S_ISDIR(st.st_mode)) {
            // Verify ksud binary exists
            if (stat("/data/adb/ksud", &st) == 0 ||
                stat("/data/adb/ksu/bin/ksud", &st) == 0) {
                return true;
            }
        }

        return false;
    }

    bool SystemStatusDetector::check_apatch() {
        // APatch uses /data/adb/ap
        struct stat st;
        if (stat("/data/adb/ap", &st) == 0 && S_ISDIR(st.st_mode)) {
            return true;
        }
        // Check for apd binary in common locations
        if (stat("/data/adb/ap/bin/apd", &st) == 0) {
            return true;
        }
        return false;
    }

    bool SystemStatusDetector::check_supersu() {
        // Check for SuperSU app data (most reliable)
        struct stat st;
        if (stat("/data/data/eu.chainfire.supersu", &st) == 0) {
            return true;
        }

        return false;
    }

    bool SystemStatusDetector::check_su_binary() {
        // Check common su binary locations
        const char *su_paths[] = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su"
        };

        struct stat st;
        for (const char *path: su_paths) {
            if (stat(path, &st) == 0 && (st.st_mode & S_IXUSR)) {
                return true;
            }
        }
        return false;
    }

    std::string SystemStatusDetector::get_magisk_version() {
        // Try to read Magisk version from magisk --version or version file
        std::ifstream version_file("/data/adb/magisk/util_functions.sh");
        if (version_file.is_open()) {
            std::string line;
            while (std::getline(version_file, line)) {
                if (line.find("MAGISK_VER=") != std::string::npos) {
                    size_t pos = line.find('=');
                    if (pos != std::string::npos) {
                        std::string ver = line.substr(pos + 1);
                        // Remove quotes if present
                        ver.erase(std::remove(ver.begin(), ver.end(), '\''), ver.end());
                        ver.erase(std::remove(ver.begin(), ver.end(), '"'), ver.end());
                        return ver;
                    }
                }
            }
        }

        // Fallback: check magisk.db or other indicators
        return "unknown";
    }

    std::string SystemStatusDetector::get_kernelsu_version() {
        // Try to get ksud version first (more informative)
        std::string ksud_version = get_ksud_binary_version();
        if (!ksud_version.empty() && ksud_version != "unknown") {
            return ksud_version;
        }

        // Fallback: Read KernelSU version from /sys/kernel/ksu/version
        std::ifstream version_file("/sys/kernel/ksu/version");
        if (version_file.is_open()) {
            std::string version;
            std::getline(version_file, version);
            return version;
        }
        return "unknown";
    }

    std::string SystemStatusDetector::get_ksud_binary_version() {
        // Use ShellExecutor with timeout to avoid blocking
        // Common paths for ksud binary
        const char *ksud_paths[] = {
                "/data/adb/ksu/bin/ksud",
                "/data/adb/ksud",
                "/data/adb/sukisu/bin/ksud"
        };

        for (const char *path: ksud_paths) {
            struct stat st;
            if (stat(path, &st) != 0 || !(st.st_mode & S_IXUSR)) {
                continue;
            }

            // Execute ksud --version with 1 second timeout
            std::string cmd = std::string(path) + " --version";
            std::string result = input::ShellExecutor::instance().exec(cmd.c_str(), 1000);

            if (!result.empty()) {
                // Trim whitespace
                result.erase(0, result.find_first_not_of(" \n\r\t"));
                result.erase(result.find_last_not_of(" \n\r\t") + 1);

                // Remove "ksud " prefix if present
                if (result.find("ksud ") == 0) {
                    result = result.substr(5);
                }

                if (!result.empty()) {
                    return result;
                }
            }
        }

        return "";
    }

    std::string SystemStatusDetector::get_apatch_version() {
        // APatch version detection
        std::ifstream version_file("/data/adb/ap/version");
        if (version_file.is_open()) {
            std::string version;
            std::getline(version_file, version);
            return version;
        }
        return "unknown";
    }

    std::string SystemStatusDetector::get_sukisu_version() {
        // SukiSU Ultra version - try ksud first, then kernel version
        std::string version = get_ksud_version();
        if (!version.empty() && version != "unknown") {
            return version;
        }

        // Fallback to kernel version
        return get_kernelsu_version();
    }

    void SystemStatusDetector::detect_selinux(SystemStatus &status) {
        // Read SELinux enforce status from /sys/fs/selinux/enforce
        std::ifstream enforce_file("/sys/fs/selinux/enforce");
        if (!enforce_file.is_open()) {
            // SELinux filesystem not mounted - likely disabled
            struct stat st;
            if (stat("/sys/fs/selinux", &st) != 0) {
                status.selinux_mode = SELinuxMode::DISABLED;
                FUTON_LOGD("SELinux: disabled (no selinuxfs)");
            } else {
                status.selinux_mode = SELinuxMode::UNKNOWN;
                FUTON_LOGD("SELinux: unknown (cannot read enforce)");
            }
            return;
        }

        int enforce_value = -1;
        enforce_file >> enforce_value;

        switch (enforce_value) {
            case 0:
                status.selinux_mode = SELinuxMode::PERMISSIVE;
                FUTON_LOGD("SELinux: permissive");
                break;
            case 1:
                status.selinux_mode = SELinuxMode::ENFORCING;
                FUTON_LOGD("SELinux: enforcing");
                break;
            default:
                status.selinux_mode = SELinuxMode::UNKNOWN;
                FUTON_LOGD("SELinux: unknown (value=%d)", enforce_value);
                break;
        }

        // Check if input access is allowed under current SELinux policy
        // In permissive mode, all access is allowed
        // In enforcing mode, we need proper policy rules
        status.input_access_allowed = (status.selinux_mode == SELinuxMode::PERMISSIVE) ||
                                      can_read_dev_input();
    }

    void SystemStatusDetector::detect_input_access(SystemStatus &status) {
        // Check if we can access /dev/input
        if (!can_read_dev_input()) {
            status.can_access_dev_input = false;
            status.input_error = "Cannot access /dev/input directory";
            FUTON_LOGW("Input access: denied - cannot read /dev/input");
            return;
        }

        // Find touch device
        std::string touch_path = find_touch_device();
        if (touch_path.empty()) {
            status.can_access_dev_input = false;
            status.input_error = "No touchscreen device found";
            FUTON_LOGW("Input access: no touchscreen found");
            return;
        }

        // Check if we can open the device
        int fd = open(touch_path.c_str(), O_RDONLY);
        if (fd < 0) {
            status.can_access_dev_input = false;
            status.input_error = "Cannot open touch device: " + std::string(strerror(errno));
            FUTON_LOGW("Input access: cannot open %s: %s", touch_path.c_str(), strerror(errno));
            return;
        }
        close(fd);

        status.can_access_dev_input = true;
        status.touch_device_path = touch_path;
        status.max_touch_points = get_max_touch_points(touch_path);
        status.input_error = "";

        FUTON_LOGD("Input access: OK, device=%s, max_points=%d",
                   touch_path.c_str(), status.max_touch_points);
    }

    bool SystemStatusDetector::can_read_dev_input() {
        DIR *dir = opendir("/dev/input");
        if (!dir) {
            return false;
        }
        closedir(dir);
        return true;
    }

    std::string SystemStatusDetector::find_touch_device() {
        DIR *dir = opendir("/dev/input");
        if (!dir) {
            return "";
        }

        std::vector<std::string> event_devices;
        struct dirent *entry;

        while ((entry = readdir(dir)) != nullptr) {
            if (strncmp(entry->d_name, "event", 5) == 0) {
                event_devices.push_back(std::string("/dev/input/") + entry->d_name);
            }
        }
        closedir(dir);

        // Sort for consistent ordering
        std::sort(event_devices.begin(), event_devices.end());

        // First pass: look for preferred touchscreen drivers
        std::string fallback_device;

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
            bool excluded = false;
            for (const auto &pattern: EXCLUDED_PATTERNS) {
                if (lower_name.find(pattern) != std::string::npos) {
                    excluded = true;
                    FUTON_LOGD("Excluding device %s (%s) - matches pattern: %s",
                               path.c_str(), name, pattern.c_str());
                    break;
                }
            }

            if (excluded) {
                close(fd);
                continue;
            }

            // Check if it's a touchscreen with MT support
            unsigned long abs_bits[NBITS(ABS_MAX)] = {0};
            if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) >= 0) {
                bool has_mt = TEST_BIT(ABS_MT_POSITION_X, abs_bits) &&
                              TEST_BIT(ABS_MT_POSITION_Y, abs_bits);
                bool has_st = TEST_BIT(ABS_X, abs_bits) && TEST_BIT(ABS_Y, abs_bits);

                if (has_mt || has_st) {
                    // Check if this is a preferred driver
                    bool is_preferred = false;
                    for (const auto &pattern: PREFERRED_PATTERNS) {
                        if (lower_name.find(pattern) != std::string::npos) {
                            is_preferred = true;
                            FUTON_LOGI("Found preferred touchscreen: %s (%s)", path.c_str(), name);
                            close(fd);
                            return path;
                        }
                    }

                    // Store as fallback if not preferred
                    if (fallback_device.empty()) {
                        fallback_device = path;
                        FUTON_LOGD("Found potential touchscreen (fallback): %s (%s)", path.c_str(),
                                   name);
                    }
                }
            }

            close(fd);
        }

        // Return fallback if no preferred device found
        if (!fallback_device.empty()) {
            FUTON_LOGI("Using fallback touchscreen: %s", fallback_device.c_str());
            return fallback_device;
        }

        FUTON_LOGW("No touchscreen device found");
        return "";
    }

    int32_t SystemStatusDetector::get_max_touch_points(const std::string &device_path) {
        int fd = open(device_path.c_str(), O_RDONLY);
        if (fd < 0) {
            return 1;
        }

        struct input_absinfo abs_info;
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &abs_info) == 0) {
            close(fd);
            return abs_info.maximum + 1;  // max slot index + 1 = number of slots
        }

        close(fd);
        return 1;  // Single touch fallback
    }

    void SystemStatusDetector::detect_runtime_info(SystemStatus &status) {
        status.daemon_pid = getpid();

        // Calculate uptime
        auto now = std::chrono::steady_clock::now();
        auto uptime = std::chrono::duration_cast<std::chrono::milliseconds>(now - startup_time_);
        status.uptime_ms = uptime.count();

        // Get daemon version from branding
        status.daemon_version = FUTON_VERSION;

        FUTON_LOGD("Runtime: pid=%d, uptime=%lldms, version=%s",
                   status.daemon_pid, static_cast<long long>(status.uptime_ms),
                   status.daemon_version.c_str());
    }

} // namespace futon::core
