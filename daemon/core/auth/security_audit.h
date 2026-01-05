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

#ifndef FUTON_CORE_AUTH_SECURITY_AUDIT_H
#define FUTON_CORE_AUTH_SECURITY_AUDIT_H

#include <cstdint>
#include <string>
#include <vector>
#include <mutex>
#include <deque>
#include <fstream>
#include <functional>

namespace futon::core::auth {

// Security event types
    enum class SecurityEventType {
        // Authentication events
        AUTH_CHALLENGE_REQUESTED,
        AUTH_CHALLENGE_EXPIRED,
        AUTH_SUCCESS,
        AUTH_FAILURE_INVALID_SIGNATURE,
        AUTH_FAILURE_NO_CHALLENGE,
        AUTH_FAILURE_CHALLENGE_EXPIRED,
        AUTH_FAILURE_PUBKEY_MISSING,
        AUTH_RATE_LIMITED,

        // Session events
        SESSION_CREATED,
        SESSION_EXPIRED,
        SESSION_INVALIDATED,
        SESSION_CONFLICT,

        // Access control events
        API_ACCESS_DENIED,
        API_ACCESS_GRANTED,

        // Security violations
        SIGNATURE_MISMATCH,
        UID_MISMATCH,
        PUBKEY_TAMPERED,
        PROCESS_VERIFICATION_FAILED,
        PACKAGE_VERIFICATION_FAILED,
        CALLER_VERIFICATION_FAILED,

        // Advanced security events
        SECURITY_CHECK_FAILED,
        DEBUGGER_DETECTED,
        FRIDA_DETECTED,
        XPOSED_DETECTED,
        DEVICE_MISMATCH,
        INTEGRITY_VIOLATION,
        WATERMARK_INVALID,
        TAMPERING_DETECTED,
        ATTESTATION_FAILED,

        // System events
        DAEMON_STARTED,
        DAEMON_STOPPED,
        PUBKEY_LOADED,
        PUBKEY_RELOADED,
        CONFIG_CHANGED
    };

    const char *security_event_type_to_string(SecurityEventType type);

// Security event severity
    enum class SecuritySeverity {
        DEBUG,      // Verbose debugging info
        INFO,       // Normal operations
        WARNING,    // Potential issues
        ERROR,      // Errors that don't compromise security
        CRITICAL    // Security violations
    };

    const char *security_severity_to_string(SecuritySeverity severity);

// Security event record
    struct SecurityEvent {
        int64_t timestamp_ms;
        SecurityEventType type;
        SecuritySeverity severity;
        uid_t uid;
        pid_t pid;
        std::string instance_id;
        std::string details;
        std::string source_ip;  // For future network support

        std::string to_log_string() const;
    };

// Audit log configuration
    struct AuditConfig {
        std::string log_path = "/data/adb/futon/security.log";
        size_t max_file_size = 1024 * 1024;  // 1 MB
        int max_rotated_files = 3;
        size_t max_memory_entries = 100;
        SecuritySeverity min_file_severity = SecuritySeverity::INFO;
        SecuritySeverity min_memory_severity = SecuritySeverity::WARNING;
        bool enable_file_logging = true;
        bool enable_memory_logging = true;
    };

// Audit event callback
    using AuditCallback = std::function<void(const SecurityEvent &)>;

    class SecurityAudit {
    public:
        explicit SecurityAudit(const AuditConfig &config = AuditConfig());

        ~SecurityAudit();

        // Disable copy
        SecurityAudit(const SecurityAudit &) = delete;

        SecurityAudit &operator=(const SecurityAudit &) = delete;

        // Initialize audit system
        bool initialize();

        void shutdown();

        // Log security events
        void log(SecurityEventType type, SecuritySeverity severity,
                 uid_t uid, pid_t pid, const std::string &details);

        void log(SecurityEventType type, SecuritySeverity severity,
                 uid_t uid, pid_t pid, const std::string &instance_id,
                 const std::string &details);

        // Convenience methods for common events
        void log_auth_success(uid_t uid, pid_t pid, const std::string &instance_id);

        void log_auth_failure(uid_t uid, pid_t pid, SecurityEventType reason,
                              const std::string &details = "");

        void log_rate_limited(uid_t uid, pid_t pid, int64_t retry_after_ms);

        void log_api_denied(uid_t uid, pid_t pid, const std::string &api_name);

        void log_session_event(SecurityEventType type, uid_t uid,
                               const std::string &instance_id);

        void log_security_violation(SecurityEventType type, uid_t uid, pid_t pid,
                                    const std::string &details);

        // Query recent events
        std::vector<SecurityEvent> get_recent_events(size_t count = 50) const;

        std::vector<SecurityEvent> get_events_by_uid(uid_t uid, size_t count = 20) const;

        std::vector<SecurityEvent> get_events_by_severity(
                SecuritySeverity min_severity, size_t count = 50) const;

        // Statistics
        struct Stats {
            int64_t total_events;
            int64_t auth_successes;
            int64_t auth_failures;
            int64_t rate_limit_hits;
            int64_t api_denials;
            int64_t security_violations;
        };

        Stats get_stats() const;

        // Register callback for real-time event notification
        void set_callback(AuditCallback callback);

        // Force log rotation
        void rotate_logs();

    private:
        AuditConfig config_;
        mutable std::mutex mutex_;

        // File logging
        std::ofstream log_file_;
        size_t current_file_size_ = 0;

        // Memory buffer for recent events
        std::deque<SecurityEvent> memory_buffer_;

        // Statistics
        Stats stats_{};

        // Callback
        AuditCallback callback_;

        // Internal helpers
        void write_to_file(const SecurityEvent &event);

        void add_to_memory(const SecurityEvent &event);

        void rotate_log_file();

        std::string get_rotated_filename(int index) const;

        static int64_t current_time_ms();
    };

// Global audit instance (singleton pattern)
    SecurityAudit &get_security_audit();

    void init_security_audit(const AuditConfig &config);

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_SECURITY_AUDIT_H
