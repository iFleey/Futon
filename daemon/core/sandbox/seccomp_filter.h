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

#ifndef FUTON_CORE_SANDBOX_SECCOMP_FILTER_H
#define FUTON_CORE_SANDBOX_SECCOMP_FILTER_H

#include <string>
#include <vector>
#include <functional>
#include <cstdint>

namespace futon::core::sandbox {

// Three-level filtering policy
    enum class SeccompAction {
        ALLOW,  // Level 1: Core whitelist - allow immediately
        LOG,    // Level 2: Unknown/edge syscalls - log but allow (telemetry)
        KILL    // Level 3: RCE behaviors - kill process immediately
    };

// Kernel version info for syscall compatibility
    struct KernelInfo {
        int major;
        int minor;
        int patch;
        int android_api_level;
        std::string release;

        bool operator>=(const KernelInfo &other) const {
            if (major != other.major) return major > other.major;
            if (minor != other.minor) return minor > other.minor;
            return patch >= other.patch;
        }
    };

// Seccomp configuration
    struct SeccompConfig {
        // Log path for Level 2 (LOG) violations
        std::string audit_log_path = "/data/adb/futon/seccomp_audit.log";

        // Maximum audit log size before rotation
        size_t max_audit_log_size = 1024 * 1024;  // 1MB

        // Enable dynamic syscall probing at startup
        bool enable_syscall_probing = true;

        // Extra syscalls to allow (user-configurable)
        std::vector<int> extra_allowed_syscalls;

        // Extra syscalls to block (user-configurable)
        std::vector<int> extra_blocked_syscalls;
    };

// Result of seccomp installation
    struct SeccompResult {
        bool success;
        std::string error_message;
        int allowed_count;   // Level 1 syscalls
        int logged_count;    // Level 2 syscalls
        int blocked_count;   // Level 3 syscalls
        KernelInfo kernel_info;
    };

// Audit log entry for Level 2 violations
    struct SeccompAuditEntry {
        uint64_t timestamp_ns;
        int syscall_nr;
        std::string syscall_name;
        pid_t pid;
        pid_t tid;
    };

// Callback for audit logging
    using AuditCallback = std::function<void(const SeccompAuditEntry &)>;

// Seccomp-BPF Filter using libseccomp
// Implements three-level filtering:
// - Level 1 (Allow): Core whitelist for daemon operation
// - Level 2 (Log): Unknown syscalls - logged for telemetry
// - Level 3 (Kill): Dangerous syscalls - immediate process death
    class SeccompFilter {
    public:
        // Install seccomp filter with default config
        static SeccompResult install();

        // Install seccomp filter with custom config
        static SeccompResult install(const SeccompConfig &config);

        // Check if seccomp is currently active
        static bool is_active();

        // Get current seccomp mode (0=disabled, 1=strict, 2=filter)
        static int get_mode();

        // Get detected kernel info
        static KernelInfo get_kernel_info();

        // Probe which syscalls are actually used by current libc
        static std::vector<int> probe_required_syscalls();

        // Get syscall name from number
        static std::string get_syscall_name(int syscall_nr);

        // Set audit callback for Level 2 violations
        static void set_audit_callback(AuditCallback callback);

        // Read audit log entries
        static std::vector<SeccompAuditEntry> read_audit_log(
                const std::string &path,
                size_t max_entries = 100
        );

        // Test: attempt to execute shell (should cause process death)
        // Only call this in test mode!
        static void test_execve_blocked();

    private:
        // Detect kernel version and Android API level
        static KernelInfo detect_kernel_info();

        // Build syscall whitelist based on kernel version
        static std::vector<int> build_allow_list(const KernelInfo &kernel);

        // Build syscall log list (unknown/edge cases)
        static std::vector<int> build_log_list(const KernelInfo &kernel);

        // Build syscall kill list (dangerous)
        static std::vector<int> build_kill_list();

        // Write audit log entry
        static void write_audit_log(
                const std::string &path,
                const SeccompAuditEntry &entry
        );

        static AuditCallback s_audit_callback;
    };

} // namespace futon::core::sandbox

#endif // FUTON_CORE_SANDBOX_SECCOMP_FILTER_H
