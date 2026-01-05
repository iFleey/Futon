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

#include "security_audit.h"
#include "core/error.h"

#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <filesystem>
#include <algorithm>

namespace futon::core::auth {

    const char *security_event_type_to_string(SecurityEventType type) {
        switch (type) {
            case SecurityEventType::AUTH_CHALLENGE_REQUESTED:
                return "AUTH_CHALLENGE_REQUESTED";
            case SecurityEventType::AUTH_CHALLENGE_EXPIRED:
                return "AUTH_CHALLENGE_EXPIRED";
            case SecurityEventType::AUTH_SUCCESS:
                return "AUTH_SUCCESS";
            case SecurityEventType::AUTH_FAILURE_INVALID_SIGNATURE:
                return "AUTH_FAILURE_INVALID_SIGNATURE";
            case SecurityEventType::AUTH_FAILURE_NO_CHALLENGE:
                return "AUTH_FAILURE_NO_CHALLENGE";
            case SecurityEventType::AUTH_FAILURE_CHALLENGE_EXPIRED:
                return "AUTH_FAILURE_CHALLENGE_EXPIRED";
            case SecurityEventType::AUTH_FAILURE_PUBKEY_MISSING:
                return "AUTH_FAILURE_PUBKEY_MISSING";
            case SecurityEventType::AUTH_RATE_LIMITED:
                return "AUTH_RATE_LIMITED";
            case SecurityEventType::SESSION_CREATED:
                return "SESSION_CREATED";
            case SecurityEventType::SESSION_EXPIRED:
                return "SESSION_EXPIRED";
            case SecurityEventType::SESSION_INVALIDATED:
                return "SESSION_INVALIDATED";
            case SecurityEventType::SESSION_CONFLICT:
                return "SESSION_CONFLICT";
            case SecurityEventType::API_ACCESS_DENIED:
                return "API_ACCESS_DENIED";
            case SecurityEventType::API_ACCESS_GRANTED:
                return "API_ACCESS_GRANTED";
            case SecurityEventType::SIGNATURE_MISMATCH:
                return "SIGNATURE_MISMATCH";
            case SecurityEventType::UID_MISMATCH:
                return "UID_MISMATCH";
            case SecurityEventType::PUBKEY_TAMPERED:
                return "PUBKEY_TAMPERED";
            case SecurityEventType::PROCESS_VERIFICATION_FAILED:
                return "PROCESS_VERIFICATION_FAILED";
            case SecurityEventType::PACKAGE_VERIFICATION_FAILED:
                return "PACKAGE_VERIFICATION_FAILED";
            case SecurityEventType::CALLER_VERIFICATION_FAILED:
                return "CALLER_VERIFICATION_FAILED";
            case SecurityEventType::SECURITY_CHECK_FAILED:
                return "SECURITY_CHECK_FAILED";
            case SecurityEventType::DEBUGGER_DETECTED:
                return "DEBUGGER_DETECTED";
            case SecurityEventType::FRIDA_DETECTED:
                return "FRIDA_DETECTED";
            case SecurityEventType::XPOSED_DETECTED:
                return "XPOSED_DETECTED";
            case SecurityEventType::DEVICE_MISMATCH:
                return "DEVICE_MISMATCH";
            case SecurityEventType::INTEGRITY_VIOLATION:
                return "INTEGRITY_VIOLATION";
            case SecurityEventType::WATERMARK_INVALID:
                return "WATERMARK_INVALID";
            case SecurityEventType::TAMPERING_DETECTED:
                return "TAMPERING_DETECTED";
            case SecurityEventType::ATTESTATION_FAILED:
                return "ATTESTATION_FAILED";
            case SecurityEventType::DAEMON_STARTED:
                return "DAEMON_STARTED";
            case SecurityEventType::DAEMON_STOPPED:
                return "DAEMON_STOPPED";
            case SecurityEventType::PUBKEY_LOADED:
                return "PUBKEY_LOADED";
            case SecurityEventType::PUBKEY_RELOADED:
                return "PUBKEY_RELOADED";
            case SecurityEventType::CONFIG_CHANGED:
                return "CONFIG_CHANGED";
            default:
                return "UNKNOWN";
        }
    }

    const char *security_severity_to_string(SecuritySeverity severity) {
        switch (severity) {
            case SecuritySeverity::DEBUG:
                return "DEBUG";
            case SecuritySeverity::INFO:
                return "INFO";
            case SecuritySeverity::WARNING:
                return "WARN";
            case SecuritySeverity::ERROR:
                return "ERROR";
            case SecuritySeverity::CRITICAL:
                return "CRIT";
            default:
                return "UNKNOWN";
        }
    }

    std::string SecurityEvent::to_log_string() const {
        std::ostringstream oss;

        // Format timestamp as ISO 8601
        auto time_point = std::chrono::system_clock::time_point(
                std::chrono::milliseconds(timestamp_ms));
        auto time_t_val = std::chrono::system_clock::to_time_t(time_point);
        auto ms = timestamp_ms % 1000;

        oss << std::put_time(std::gmtime(&time_t_val), "%Y-%m-%dT%H:%M:%S")
            << "." << std::setfill('0') << std::setw(3) << ms << "Z";

        oss << " | " << std::setw(5) << security_severity_to_string(severity);
        oss << " | " << security_event_type_to_string(type);
        oss << " | uid=" << uid;
        oss << " pid=" << pid;

        if (!instance_id.empty()) {
            oss << " instance=" << instance_id;
        }

        if (!details.empty()) {
            oss << " | " << details;
        }

        return oss.str();
    }

    int64_t SecurityAudit::current_time_ms() {
        auto now = std::chrono::system_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();
    }

    SecurityAudit::SecurityAudit(const AuditConfig &config)
            : config_(config) {
    }

    SecurityAudit::~SecurityAudit() {
        shutdown();
    }

    bool SecurityAudit::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (config_.enable_file_logging) {
            // Ensure directory exists
            std::filesystem::path log_path(config_.log_path);
            std::filesystem::path log_dir = log_path.parent_path();

            try {
                if (!log_dir.empty() && !std::filesystem::exists(log_dir)) {
                    std::filesystem::create_directories(log_dir);
                }
            } catch (const std::exception &e) {
                FUTON_LOGE("Failed to create log directory: %s", e.what());
                return false;
            }

            // Open log file in append mode
            log_file_.open(config_.log_path, std::ios::app);
            if (!log_file_.is_open()) {
                FUTON_LOGE("Failed to open security log file: %s", config_.log_path.c_str());
                return false;
            }

            // Get current file size
            log_file_.seekp(0, std::ios::end);
            current_file_size_ = log_file_.tellp();
        }

        FUTON_LOGI("Security audit initialized: file=%s, max_size=%zu",
                   config_.log_path.c_str(), config_.max_file_size);
        return true;
    }

    void SecurityAudit::shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (log_file_.is_open()) {
            log_file_.flush();
            log_file_.close();
        }
    }

    void SecurityAudit::log(SecurityEventType type, SecuritySeverity severity,
                            uid_t uid, pid_t pid, const std::string &details) {
        log(type, severity, uid, pid, "", details);
    }

    void SecurityAudit::log(SecurityEventType type, SecuritySeverity severity,
                            uid_t uid, pid_t pid, const std::string &instance_id,
                            const std::string &details) {
        SecurityEvent event;
        event.timestamp_ms = current_time_ms();
        event.type = type;
        event.severity = severity;
        event.uid = uid;
        event.pid = pid;
        event.instance_id = instance_id;
        event.details = details;

        {
            std::lock_guard<std::mutex> lock(mutex_);

            // Update statistics
            stats_.total_events++;
            switch (type) {
                case SecurityEventType::AUTH_SUCCESS:
                    stats_.auth_successes++;
                    break;
                case SecurityEventType::AUTH_FAILURE_INVALID_SIGNATURE:
                case SecurityEventType::AUTH_FAILURE_NO_CHALLENGE:
                case SecurityEventType::AUTH_FAILURE_CHALLENGE_EXPIRED:
                case SecurityEventType::AUTH_FAILURE_PUBKEY_MISSING:
                    stats_.auth_failures++;
                    break;
                case SecurityEventType::AUTH_RATE_LIMITED:
                    stats_.rate_limit_hits++;
                    break;
                case SecurityEventType::API_ACCESS_DENIED:
                    stats_.api_denials++;
                    break;
                case SecurityEventType::SIGNATURE_MISMATCH:
                case SecurityEventType::UID_MISMATCH:
                case SecurityEventType::PUBKEY_TAMPERED:
                case SecurityEventType::PROCESS_VERIFICATION_FAILED:
                case SecurityEventType::PACKAGE_VERIFICATION_FAILED:
                case SecurityEventType::CALLER_VERIFICATION_FAILED:
                case SecurityEventType::SECURITY_CHECK_FAILED:
                case SecurityEventType::DEBUGGER_DETECTED:
                case SecurityEventType::FRIDA_DETECTED:
                case SecurityEventType::XPOSED_DETECTED:
                case SecurityEventType::DEVICE_MISMATCH:
                case SecurityEventType::INTEGRITY_VIOLATION:
                case SecurityEventType::WATERMARK_INVALID:
                case SecurityEventType::TAMPERING_DETECTED:
                    stats_.security_violations++;
                    break;
                default:
                    break;
            }

            // Write to file if enabled and severity meets threshold
            if (config_.enable_file_logging && severity >= config_.min_file_severity) {
                write_to_file(event);
            }

            // Add to memory buffer if enabled and severity meets threshold
            if (config_.enable_memory_logging && severity >= config_.min_memory_severity) {
                add_to_memory(event);
            }
        }

        // Call callback outside of lock
        if (callback_) {
            callback_(event);
        }
    }

    void SecurityAudit::write_to_file(const SecurityEvent &event) {
        if (!log_file_.is_open()) return;

        std::string log_line = event.to_log_string() + "\n";
        log_file_ << log_line;
        log_file_.flush();

        current_file_size_ += log_line.size();

        // Check if rotation is needed
        if (current_file_size_ >= config_.max_file_size) {
            rotate_log_file();
        }
    }

    void SecurityAudit::add_to_memory(const SecurityEvent &event) {
        memory_buffer_.push_back(event);

        // Trim if exceeds max size
        while (memory_buffer_.size() > config_.max_memory_entries) {
            memory_buffer_.pop_front();
        }
    }

    void SecurityAudit::rotate_log_file() {
        log_file_.close();

        // Rotate existing files
        for (int i = config_.max_rotated_files - 1; i >= 0; --i) {
            std::string old_name = (i == 0) ? config_.log_path : get_rotated_filename(i);
            std::string new_name = get_rotated_filename(i + 1);

            try {
                if (std::filesystem::exists(old_name)) {
                    if (i == config_.max_rotated_files - 1) {
                        std::filesystem::remove(old_name);
                    } else {
                        std::filesystem::rename(old_name, new_name);
                    }
                }
            } catch (const std::exception &e) {
                FUTON_LOGW("Log rotation error: %s", e.what());
            }
        }

        // Open new log file
        log_file_.open(config_.log_path, std::ios::out | std::ios::trunc);
        current_file_size_ = 0;

        FUTON_LOGI("Security log rotated");
    }

    std::string SecurityAudit::get_rotated_filename(int index) const {
        return config_.log_path + "." + std::to_string(index);
    }

    void SecurityAudit::log_auth_success(uid_t uid, pid_t pid, const std::string &instance_id) {
        log(SecurityEventType::AUTH_SUCCESS, SecuritySeverity::INFO,
            uid, pid, instance_id, "Authentication successful");
    }

    void SecurityAudit::log_auth_failure(uid_t uid, pid_t pid, SecurityEventType reason,
                                         const std::string &details) {
        log(reason, SecuritySeverity::WARNING, uid, pid, details);
    }

    void SecurityAudit::log_rate_limited(uid_t uid, pid_t pid, int64_t retry_after_ms) {
        std::string details = "Retry after " + std::to_string(retry_after_ms) + "ms";
        log(SecurityEventType::AUTH_RATE_LIMITED, SecuritySeverity::WARNING,
            uid, pid, details);
    }

    void SecurityAudit::log_api_denied(uid_t uid, pid_t pid, const std::string &api_name) {
        log(SecurityEventType::API_ACCESS_DENIED, SecuritySeverity::WARNING,
            uid, pid, "API: " + api_name);
    }

    void SecurityAudit::log_session_event(SecurityEventType type, uid_t uid,
                                          const std::string &instance_id) {
        SecuritySeverity severity = (type == SecurityEventType::SESSION_CONFLICT)
                                    ? SecuritySeverity::WARNING
                                    : SecuritySeverity::INFO;
        log(type, severity, uid, 0, instance_id, "");
    }

    void SecurityAudit::log_security_violation(SecurityEventType type, uid_t uid, pid_t pid,
                                               const std::string &details) {
        log(type, SecuritySeverity::CRITICAL, uid, pid, details);
    }

    std::vector<SecurityEvent> SecurityAudit::get_recent_events(size_t count) const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<SecurityEvent> result;
        size_t start = (memory_buffer_.size() > count)
                       ? memory_buffer_.size() - count
                       : 0;

        for (size_t i = start; i < memory_buffer_.size(); ++i) {
            result.push_back(memory_buffer_[i]);
        }

        return result;
    }

    std::vector<SecurityEvent> SecurityAudit::get_events_by_uid(uid_t uid, size_t count) const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<SecurityEvent> result;
        for (auto it = memory_buffer_.rbegin();
             it != memory_buffer_.rend() && result.size() < count;
             ++it) {
            if (it->uid == uid) {
                result.push_back(*it);
            }
        }

        std::reverse(result.begin(), result.end());
        return result;
    }

    std::vector<SecurityEvent> SecurityAudit::get_events_by_severity(
            SecuritySeverity min_severity, size_t count) const {
        std::lock_guard<std::mutex> lock(mutex_);

        std::vector<SecurityEvent> result;
        for (auto it = memory_buffer_.rbegin();
             it != memory_buffer_.rend() && result.size() < count;
             ++it) {
            if (it->severity >= min_severity) {
                result.push_back(*it);
            }
        }

        std::reverse(result.begin(), result.end());
        return result;
    }

    SecurityAudit::Stats SecurityAudit::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return stats_;
    }

    void SecurityAudit::set_callback(AuditCallback callback) {
        std::lock_guard<std::mutex> lock(mutex_);
        callback_ = std::move(callback);
    }

    void SecurityAudit::rotate_logs() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (config_.enable_file_logging) {
            rotate_log_file();
        }
    }

// Global instance
    static std::unique_ptr<SecurityAudit> g_security_audit;
    static std::mutex g_audit_init_mutex;

    SecurityAudit &get_security_audit() {
        std::lock_guard<std::mutex> lock(g_audit_init_mutex);
        if (!g_security_audit) {
            g_security_audit = std::make_unique<SecurityAudit>();
            g_security_audit->initialize();
        }
        return *g_security_audit;
    }

    void init_security_audit(const AuditConfig &config) {
        std::lock_guard<std::mutex> lock(g_audit_init_mutex);
        g_security_audit = std::make_unique<SecurityAudit>(config);
        g_security_audit->initialize();
    }

} // namespace futon::core::auth
