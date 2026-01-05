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

#ifndef FUTON_CORE_AUTH_UID_WHITELIST_H
#define FUTON_CORE_AUTH_UID_WHITELIST_H

#include <cstdint>
#include <string>
#include <vector>
#include <set>
#include <mutex>
#include <optional>
#include <functional>
#include <chrono>

namespace futon::core::auth {

/**
 * UID-based authorization for open source security model.
 *
 * In an open source project, we cannot rely on "hiding" secrets because
 * all code is public. Instead, we rely on:
 *
 * 1. UID verification - Android assigns unique UIDs per app signature
 * 2. User authorization - User explicitly grants permission to apps
 * 3. TOFU (Trust On First Use) - First connection establishes trust
 *
 * This is similar to how Magisk handles root authorization.
 */

// Authorization status for a UID
    enum class AuthorizationStatus {
        UNKNOWN,        // Never seen this UID
        PENDING,        // Waiting for user authorization
        AUTHORIZED,     // User granted permission
        DENIED,         // User denied permission
        REVOKED         // Previously authorized, now revoked
    };

// Information about an authorized app
    struct AuthorizedApp {
        uid_t uid;
        std::string package_name;
        std::string label;  // User-friendly name
        int64_t first_seen_ms;
        int64_t authorized_at_ms;
        int64_t last_access_ms;
        int access_count;
        AuthorizationStatus status;

        // Dynamic public key (TOFU model)
        std::vector<uint8_t> public_key;
        int64_t key_registered_at_ms;
    };

// Pending authorization request
    struct PendingAuthorization {
        uid_t uid;
        pid_t pid;
        std::string package_name;
        int64_t requested_at_ms;
        std::string request_reason;
    };

// Configuration for UID whitelist
    struct UidWhitelistConfig {
        std::string whitelist_path = "/data/adb/futon/authorized_apps.json";
        std::string pending_path = "/data/adb/futon/pending_auth.json";

        // Auto-authorize apps with same signature as daemon installer
        bool auto_authorize_same_signature = true;

        int64_t pending_timeout_ms = 0x493E0;
        size_t max_pending_requests = 0xA;
        uint32_t format_version = 0x464C;
    };

// Callback for authorization requests (to show UI notification)
    using AuthorizationRequestCallback = std::function<void(const PendingAuthorization &)>;

// Callback for authorization decisions
    using AuthorizationDecisionCallback = std::function<void(uid_t, bool allowed)>;

    class UidWhitelist {
    public:
        explicit UidWhitelist(const UidWhitelistConfig &config = UidWhitelistConfig());

        ~UidWhitelist();

        // Disable copy
        UidWhitelist(const UidWhitelist &) = delete;

        UidWhitelist &operator=(const UidWhitelist &) = delete;

        // Initialize - load whitelist from file
        bool initialize();

        // Shutdown - save whitelist to file
        void shutdown();

        // Check if UID is authorized
        AuthorizationStatus check_authorization(uid_t uid);

        // Check and update last access time
        bool is_authorized(uid_t uid);

        // Request authorization for a new UID
        // Returns true if request was created, false if already pending/authorized
        bool request_authorization(uid_t uid, pid_t pid, const std::string &package_name,
                                   const std::string &reason = "");

        // Handle user's authorization decision
        void authorize(uid_t uid, const std::string &label = "");

        void deny(uid_t uid);

        void revoke(uid_t uid);

        // Public key management (TOFU model)
        bool register_public_key(uid_t uid, const std::vector<uint8_t> &public_key);

        std::optional<std::vector<uint8_t>> get_public_key(uid_t uid) const;

        bool has_public_key(uid_t uid) const;

        // Query methods
        std::vector<AuthorizedApp> get_authorized_apps() const;

        std::vector<PendingAuthorization> get_pending_requests() const;

        std::optional<AuthorizedApp> get_app_info(uid_t uid) const;

        // Cleanup expired pending requests
        void cleanup_expired();

        // Set callbacks
        void set_authorization_request_callback(AuthorizationRequestCallback callback);

        void set_authorization_decision_callback(AuthorizationDecisionCallback callback);

        // Statistics
        struct Stats {
            size_t authorized_count;
            size_t denied_count;
            size_t pending_count;
            int64_t total_access_count;
        };

        Stats get_stats() const;

    private:
        UidWhitelistConfig config_;
        mutable std::mutex mutex_;

        // Authorized apps (keyed by UID)
        std::map<uid_t, AuthorizedApp> authorized_apps_;

        // Pending authorization requests
        std::map<uid_t, PendingAuthorization> pending_requests_;

        // Callbacks
        AuthorizationRequestCallback auth_request_callback_;
        AuthorizationDecisionCallback auth_decision_callback_;

        // Persistence
        bool load_whitelist();

        bool save_whitelist();

        // Helper to get current time
        static int64_t current_time_ms();

        // Get package name from UID (via /proc)
        static std::string get_package_name(uid_t uid, pid_t pid);
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_UID_WHITELIST_H
