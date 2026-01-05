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

// NOTE: GPLv3 Compliance
//
// This module uses USER-CONFIGURABLE files for authorization settings.
// Users who modify and recompile this software can change these files
// to authorize their own builds, as required by GPLv3 Section 6.
//
// Security Model:
// - Package/signature checks are for USER CONVENIENCE (prevent accidental
//   installation of wrong app), NOT security boundaries
// - Real security comes from Challenge-Response authentication with
//   user-deployed public keys (User-Provisioned PKI)
// - Users have FULL CONTROL over what apps are authorized

#include "hardened_config.h"
#include "device_fingerprint.h"
#include "crypto_utils.h"
#include "core/error.h"

#include <fstream>
#include <sstream>
#include <cstring>
#include <chrono>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <cerrno>

namespace futon::core::auth {

// GPLv3 Compliant: All authorization settings are in user-editable files
// Users can modify these files to authorize their own modified builds
// Paths are consistent with app/src/.../config/DaemonConfig.kt
    namespace config_paths {
        constexpr const char *BASE_DIR = "/data/adb/futon";
        constexpr const char *PACKAGE_FILE = "/data/adb/futon/authorized_package.txt";
        constexpr const char *SIGNATURE_FILE = "/data/adb/futon/authorized_signature.txt";

        // Default values (used only if config files don't exist)
        // Users can override by creating the config files
        constexpr const char *DEFAULT_PACKAGE = "me.fleey.futon";
        // EC P-384 + SHA384withECDSA certificate fingerprint (SHA-256)
        constexpr const char *DEFAULT_SIGNATURE_HEX =
                "feedaff70554680050b02cefbd70342d383eab9d6b7963bad5158c17db604b69";
    }

    SecurityPolicy::Level SecurityPolicy::current_level_ = SecurityPolicy::Level::ENFORCING;

    HardenedConfig &HardenedConfig::instance() {
        static HardenedConfig instance;
        return instance;
    }

    HardenedConfig::HardenedConfig() = default;

    HardenedConfig::~HardenedConfig() {
        shutdown();
    }

    bool HardenedConfig::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (initialized_) {
            return true;
        }

        FUTON_LOGI("Initializing configuration system...");

        // Ensure config directory exists
        mkdir(config_paths::BASE_DIR, 0755);

        // Derive device-specific key for entropy (not for obscurity)
        device_key_ = derive_device_key();
        if (device_key_.empty()) {
            FUTON_LOGW("Failed to derive device key, using fallback");
        }

        // Environment checks are now telemetry, not security boundaries
        // An attacker with source code can bypass any software check
        if (!is_environment_safe()) {
            FUTON_LOGW(
                    "Environment check detected potential instrumentation (logged for telemetry)");
            // We log but don't block - real security is in crypto verification
        }

        initialized_ = true;
        last_check_time_ = static_cast<uint64_t>(
                std::chrono::steady_clock::now().time_since_epoch().count());

        FUTON_LOGI("Configuration initialized successfully");
        return true;
    }

    void HardenedConfig::shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);

        // Securely clear sensitive data
        if (!device_key_.empty()) {
            std::fill(device_key_.begin(), device_key_.end(), 0);
            device_key_.clear();
        }

        initialized_ = false;
    }

    std::vector<uint8_t> HardenedConfig::derive_device_key() {
        std::vector<uint8_t> entropy;

        std::ifstream serial("/sys/devices/soc0/serial_number");
        if (serial.is_open()) {
            std::string s;
            std::getline(serial, s);
            entropy.insert(entropy.end(), s.begin(), s.end());
        }

        std::ifstream cpuinfo("/proc/cpuinfo");
        if (cpuinfo.is_open()) {
            std::string line;
            while (std::getline(cpuinfo, line)) {
                if (line.find("Hardware") != std::string::npos ||
                    line.find("Serial") != std::string::npos) {
                    entropy.insert(entropy.end(), line.begin(), line.end());
                }
            }
        }

        std::ifstream boot_id("/proc/sys/kernel/random/boot_id");
        if (boot_id.is_open()) {
            std::string s;
            std::getline(boot_id, s);
            entropy.insert(entropy.end(), s.begin(), s.end());
        }

        if (entropy.empty()) {
            // Fallback: use process info
            pid_t pid = getpid();
            entropy.push_back(static_cast<uint8_t>(pid & 0xFF));
            entropy.push_back(static_cast<uint8_t>((pid >> 8) & 0xFF));

            auto now = std::chrono::steady_clock::now().time_since_epoch().count();
            for (int i = 0; i < 8; ++i) {
                entropy.push_back(static_cast<uint8_t>((now >> (i * 8)) & 0xFF));
            }
        }

        return CryptoUtils::sha256(entropy);
    }

    std::string HardenedConfig::get_authorized_package() const {
        // GPLv3: Read from user-configurable file first
        std::string package = read_config_file(config_paths::PACKAGE_FILE);
        if (!package.empty()) {
            return package;
        }
        // Fallback to default (user can override by creating the file)
        return config_paths::DEFAULT_PACKAGE;
    }

    std::vector<uint8_t> HardenedConfig::get_authorized_signature() const {
        // GPLv3: Read from user-configurable file first
        std::string sig_hex = read_config_file(config_paths::SIGNATURE_FILE);
        if (sig_hex.empty()) {
            // Fallback to default (user can override by creating the file)
            sig_hex = config_paths::DEFAULT_SIGNATURE_HEX;
        }

        auto result = CryptoUtils::from_hex(sig_hex);
        return result.value_or(std::vector<uint8_t>{});
    }

    std::string HardenedConfig::read_config_file(const std::string &path) const {
        std::ifstream file(path);
        if (!file.is_open()) {
            return "";
        }

        std::string content;
        std::getline(file, content);

        // Trim whitespace
        content.erase(0, content.find_first_not_of(" \t\n\r"));
        content.erase(content.find_last_not_of(" \t\n\r") + 1);

        return content;
    }

    std::vector<uint8_t> HardenedConfig::get_authorized_pubkey_fingerprint() const {
        // Same as signature for APK signing certificate
        return get_authorized_signature();
    }

    ConfigVerifyResult HardenedConfig::verify_all() const {
        ConfigVerifyResult result;
        result.valid = true;
        result.device_bound = false;  // Device binding removed (GPLv3 compliance)
        result.integrity_ok = true;   // Self-integrity checks removed (can be patched)
        result.environment_safe = is_environment_safe();

        // Environment check is now informational only
        if (!result.environment_safe) {
            result.failure_reason = "Instrumentation detected (informational)";
            // Don't set valid=false - this is telemetry, not a security boundary
        }

        return result;
    }

    bool HardenedConfig::verify_integrity() const {
        // Removed: self-integrity checks are useless in open source
        // An attacker can simply patch out the check
        return true;
    }

    bool HardenedConfig::verify_device_binding() const {
        // Removed: device binding is a form of DRM incompatible with GPLv3 spirit
        return true;
    }

    bool HardenedConfig::is_device_bound() const {
        // Removed: always return false
        return false;
    }

    bool HardenedConfig::is_environment_safe() const {
        // These checks are now TELEMETRY, not security boundaries
        // An attacker with source code can bypass any of these
        // We keep them for logging/analytics purposes only

        if (is_debugger_attached()) {
            FUTON_LOGD("Debugger detected (telemetry)");
            return false;
        }

        if (is_frida_present()) {
            FUTON_LOGD("Frida detected (telemetry)");
            return false;
        }

        if (is_xposed_present()) {
            FUTON_LOGD("Xposed detected (telemetry)");
            return false;
        }

        return true;
    }

    bool HardenedConfig::is_debugger_attached() const {
        std::ifstream status("/proc/self/status");
        if (!status.is_open()) return false;

        std::string line;
        while (std::getline(status, line)) {
            if (line.find("TracerPid:") != std::string::npos) {
                size_t pos = line.find(':');
                if (pos != std::string::npos) {
                    std::string pid_str = line.substr(pos + 1);
                    pid_str.erase(0, pid_str.find_first_not_of(" \t"));
                    try {
                        int tracer_pid = std::stoi(pid_str);
                        return tracer_pid != 0;
                    } catch (...) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    bool HardenedConfig::is_frida_present() const {
        // Check /proc/self/maps for Frida libraries
        std::ifstream maps("/proc/self/maps");
        if (maps.is_open()) {
            std::string line;
            while (std::getline(maps, line)) {
                if (line.find("frida") != std::string::npos ||
                    line.find("gadget") != std::string::npos ||
                    line.find("linjector") != std::string::npos) {
                    return true;
                }
            }
        }

        // Check for Frida default port
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock >= 0) {
            struct sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_port = htons(27042);
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

            int flags = fcntl(sock, F_GETFL, 0);
            fcntl(sock, F_SETFL, flags | O_NONBLOCK);

            int result = connect(sock, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr));
            close(sock);

            if (result == 0 || errno == EINPROGRESS) {
                return true;
            }
        }

        return false;
    }

    bool HardenedConfig::is_xposed_present() const {
        // Check /proc/self/maps
        std::ifstream maps("/proc/self/maps");
        if (maps.is_open()) {
            std::string line;
            while (std::getline(maps, line)) {
                if (line.find("XposedBridge") != std::string::npos ||
                    line.find("libedxp") != std::string::npos ||
                    line.find("liblspd") != std::string::npos) {
                    return true;
                }
            }
        }

        // Check for Xposed/LSPosed paths
        const char *xposed_paths[] = {
                "/system/framework/XposedBridge.jar",
                "/data/adb/lspd",
                "/data/adb/edxp"
        };

        for (const char *path: xposed_paths) {
            if (access(path, F_OK) == 0) {
                return true;
            }
        }

        return false;
    }

    bool HardenedConfig::bind_to_device() {
        // Removed: device binding is incompatible with GPLv3
        FUTON_LOGW("bind_to_device() is deprecated and does nothing");
        return true;
    }

    std::vector<uint8_t> HardenedConfig::get_device_key() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return device_key_;
    }

    std::vector<uint8_t> HardenedConfig::get_config_fingerprint() const {
        // Fingerprint based on current config (from files or defaults)
        std::string data = get_authorized_package();
        auto sig = get_authorized_signature();
        data += CryptoUtils::to_hex(sig);

        return CryptoUtils::sha256(
                std::vector<uint8_t>(data.begin(), data.end()));
    }

    void HardenedConfig::perform_security_check() {
        // Now just logs telemetry
        is_environment_safe();

        last_check_time_ = static_cast<uint64_t>(
                std::chrono::steady_clock::now().time_since_epoch().count());
    }

    HardenedConfig::SecurityStatus HardenedConfig::get_security_status() const {
        std::lock_guard<std::mutex> lock(mutex_);

        SecurityStatus status;
        status.initialized = initialized_;
        status.device_bound = false;  // Always false now
        status.integrity_verified = true;  // Always true now
        status.environment_safe = is_environment_safe();
        status.security_violations = 0;
        status.last_check_time = last_check_time_;

        return status;
    }

// SecurityPolicy implementation
    bool SecurityPolicy::is_operation_allowed(const std::string & /*operation*/) {
        // All operations allowed - real security is in crypto verification
        return true;
    }

    void SecurityPolicy::enforce() {
        // No-op - enforcement removed
    }

    SecurityPolicy::Level SecurityPolicy::get_level() {
        return current_level_;
    }

    void SecurityPolicy::set_level(Level level) {
        current_level_ = level;
    }

} // namespace futon::core::auth
