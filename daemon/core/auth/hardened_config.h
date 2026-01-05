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

#ifndef FUTON_CORE_AUTH_HARDENED_CONFIG_H
#define FUTON_CORE_AUTH_HARDENED_CONFIG_H

#include <cstdint>
#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include <atomic>

namespace futon::core::auth {

// Forward declarations
    class DeviceFingerprint;

    class IntegrityChecker;

// Configuration verification result
    struct ConfigVerifyResult {
        bool valid;
        bool device_bound;
        bool integrity_ok;
        bool environment_safe;
        std::string failure_reason;

        bool is_fully_valid() const {
            return valid && device_bound && integrity_ok && environment_safe;
        }
    };

// Plain-text configuration paths (GPLv3 Compliant)
// Users can modify these files to authorize their own modified builds
// All paths are under /data/adb/futon/ for consistency with DaemonConfig
    struct ConfigPaths {
        static constexpr const char *BASE_DIR = "/data/adb/futon";
        static constexpr const char *PACKAGE_FILE = "/data/adb/futon/authorized_package.txt";
        static constexpr const char *SIGNATURE_FILE = "/data/adb/futon/authorized_signature.txt";
        static constexpr const char *PUBKEY_FILE = "/data/adb/futon/.auth_pubkey";
    };

// Configuration manager with file-based storage
// Security model: User-Provisioned PKI (keys deployed by user with root access)
    class HardenedConfig {
    public:
        // Singleton access
        static HardenedConfig &instance();

        // Initialize configuration system
        bool initialize();

        // Shutdown and cleanup
        void shutdown();

        // Get authorized package name (plain text from file)
        std::string get_authorized_package() const;

        // Get authorized APK signature (hex from file)
        std::vector<uint8_t> get_authorized_signature() const;

        // Get authorized public key fingerprint
        std::vector<uint8_t> get_authorized_pubkey_fingerprint() const;

        // Verify all security conditions
        ConfigVerifyResult verify_all() const;

        // Individual verification methods
        bool verify_integrity() const;

        bool verify_device_binding() const;

        // Environment checks (telemetry only, not security boundaries)
        bool is_environment_safe() const;

        bool is_debugger_attached() const;

        bool is_frida_present() const;

        bool is_xposed_present() const;

        // Device binding
        bool bind_to_device();

        bool is_device_bound() const;

        // Get device-specific key (for session encryption)
        std::vector<uint8_t> get_device_key() const;

        // Runtime configuration updates
        bool update_authorized_package(const std::string &package);

        bool update_authorized_signature(const std::vector<uint8_t> &signature);

        // Get configuration fingerprint (for external verification)
        std::vector<uint8_t> get_config_fingerprint() const;

        // Periodic security check
        void perform_security_check();

        // Get security status
        struct SecurityStatus {
            bool initialized;
            bool device_bound;
            bool integrity_verified;
            bool environment_safe;
            uint32_t security_violations;
            uint64_t last_check_time;
        };

        SecurityStatus get_security_status() const;

    private:
        HardenedConfig();

        ~HardenedConfig();

        // Disable copy
        HardenedConfig(const HardenedConfig &) = delete;

        HardenedConfig &operator=(const HardenedConfig &) = delete;

        // State
        bool initialized_ = false;
        mutable std::mutex mutex_;

        // Device-derived key (for session encryption, not config obfuscation)
        std::vector<uint8_t> device_key_;

        // Cached values from files
        mutable std::optional<std::string> cached_package_;
        mutable std::optional<std::vector<uint8_t>> cached_signature_;

        // Security counters (telemetry)
        mutable std::atomic<uint32_t> security_violations_{0};
        mutable uint64_t last_check_time_ = 0;

        // File I/O helpers
        std::string read_config_file(const std::string &path) const;

        bool write_config_file(const std::string &path, const std::string &content);

        // Key derivation (for session encryption)
        std::vector<uint8_t> derive_device_key();

        // Environment checks (telemetry)
        bool check_proc_status() const;

        bool check_proc_maps() const;

        bool check_system_properties() const;

        // Clear cached values
        void clear_cache() const;

        // Log security event (telemetry only)
        void log_security_event(const std::string &event) const;
    };

// Security policy enforcement
    class SecurityPolicy {
    public:
        // Check if operation is allowed
        static bool is_operation_allowed(const std::string &operation);

        // Enforce security policy
        static void enforce();

        // Get current policy level
        enum class Level {
            PERMISSIVE,   // Log violations but allow
            ENFORCING,    // Block violations
            PARANOID      // Crash on any violation
        };

        static Level get_level();

        static void set_level(Level level);

    private:
        static Level current_level_;
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_HARDENED_CONFIG_H
