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

#ifndef FUTON_CORE_AUTH_DEVICE_FINGERPRINT_H
#define FUTON_CORE_AUTH_DEVICE_FINGERPRINT_H

#include <cstdint>
#include <string>
#include <vector>
#include <array>
#include <optional>
#include <mutex>

namespace futon::core::auth {

// Device fingerprint components
    struct DeviceFingerprintComponents {
        std::vector<uint8_t> cpu_info;           // CPU characteristics
        std::vector<uint8_t> memory_info;        // Memory layout
        std::vector<uint8_t> kernel_info;        // Kernel version/config
        std::vector<uint8_t> hardware_serial;    // Hardware identifiers
        std::vector<uint8_t> boot_id;            // Boot-specific ID
        std::vector<uint8_t> build_fingerprint;  // Android build info
        std::vector<uint8_t> selinux_info;       // SELinux configuration
        std::vector<uint8_t> partition_info;     // Partition layout
        std::vector<uint8_t> timing_fingerprint; // CPU timing characteristics
    };

// Fingerprint binding configuration
    struct DeviceBindingConfig {
        std::string binding_file_path = "/data/adb/futon/.device_binding";

        // Which components to include in fingerprint
        bool use_cpu_info = true;
        bool use_memory_info = true;
        bool use_kernel_info = true;
        bool use_hardware_serial = true;
        bool use_boot_id = false;  // Changes on reboot
        bool use_build_fingerprint = true;
        bool use_selinux_info = true;
        bool use_partition_info = true;
        bool use_timing_fingerprint = true;

        // Tolerance for fingerprint matching (0-100%)
        // Allows for minor system changes
        int match_threshold_percent = 85;

        // Maximum allowed component drift
        int max_component_changes = 2;
    };

// Fingerprint verification result
    struct FingerprintVerifyResult {
        bool verified;
        int match_score;           // 0-100
        int components_matched;
        int components_total;
        std::string failure_reason;

        static FingerprintVerifyResult success(int score, int matched, int total) {
            return {true, score, matched, total, ""};
        }

        static FingerprintVerifyResult failure(const std::string &reason) {
            return {false, 0, 0, 0, reason};
        }
    };

    class DeviceFingerprint {
    public:
        explicit DeviceFingerprint(const DeviceBindingConfig &config = DeviceBindingConfig());

        ~DeviceFingerprint() = default;

        // Disable copy
        DeviceFingerprint(const DeviceFingerprint &) = delete;

        DeviceFingerprint &operator=(const DeviceFingerprint &) = delete;

        // Initialize fingerprinting system
        bool initialize();

        // Collect current device fingerprint
        std::vector<uint8_t> collect_fingerprint();

        // Get individual components
        DeviceFingerprintComponents collect_components();

        // Bind to current device (first-time setup)
        bool bind_to_device();

        // Verify current device matches bound fingerprint
        FingerprintVerifyResult verify_device();

        // Check if device is already bound
        bool is_bound() const;

        // Get bound fingerprint hash
        std::optional<std::vector<uint8_t>> get_bound_fingerprint() const;

        // Clear device binding
        bool clear_binding();

        // Get fingerprint for external use (e.g., challenge-response)
        std::vector<uint8_t> get_device_entropy() const;

    private:
        DeviceBindingConfig config_;
        mutable std::mutex mutex_;

        // Cached bound fingerprint
        std::optional<std::vector<uint8_t>> bound_fingerprint_;
        std::optional<DeviceFingerprintComponents> bound_components_;

        // Component collectors
        std::vector<uint8_t> collect_cpu_info();

        std::vector<uint8_t> collect_memory_info();

        std::vector<uint8_t> collect_kernel_info();

        std::vector<uint8_t> collect_hardware_serial();

        std::vector<uint8_t> collect_boot_id();

        std::vector<uint8_t> collect_build_fingerprint();

        std::vector<uint8_t> collect_selinux_info();

        std::vector<uint8_t> collect_partition_info();

        std::vector<uint8_t> collect_timing_fingerprint();

        // Combine components into single fingerprint
        std::vector<uint8_t> combine_components(const DeviceFingerprintComponents &components);

        // Compare two component sets
        int compare_components(
                const DeviceFingerprintComponents &a,
                const DeviceFingerprintComponents &b,
                int &matched,
                int &total
        );

        // Load/save binding
        bool load_binding();

        bool save_binding(const std::vector<uint8_t> &fingerprint,
                          const DeviceFingerprintComponents &components);

        // Helper to read proc/sys files
        std::string read_file(const std::string &path);

        std::vector<uint8_t> hash_string(const std::string &str);
    };

// CPU timing-based fingerprinting (inspired by hardware PUFs)
    class TimingFingerprint {
    public:
        // Measure CPU timing characteristics
        static std::vector<uint8_t> measure();

    private:
        // Measure instruction timing variance
        static uint64_t measure_instruction_timing();

        // Measure memory access timing
        static uint64_t measure_memory_timing();

        // Measure cache timing
        static uint64_t measure_cache_timing();

        // Measure branch prediction timing
        static uint64_t measure_branch_timing();
    };

// Hardware entropy collector
    class HardwareEntropy {
    public:
        // Collect entropy from various hardware sources
        static std::vector<uint8_t> collect(size_t bytes);

    private:
        // Individual entropy sources
        static void add_cpu_entropy(std::vector<uint8_t> &pool);

        static void add_timing_entropy(std::vector<uint8_t> &pool);

        static void add_memory_entropy(std::vector<uint8_t> &pool);

        static void add_system_entropy(std::vector<uint8_t> &pool);
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_DEVICE_FINGERPRINT_H
