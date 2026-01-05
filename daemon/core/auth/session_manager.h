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

#ifndef FUTON_CORE_AUTH_SESSION_MANAGER_H
#define FUTON_CORE_AUTH_SESSION_MANAGER_H

#include <cstdint>
#include <string>
#include <mutex>
#include <optional>
#include <chrono>
#include <vector>
#include <unordered_map>

namespace futon::core::auth {

// Session timeout configuration
// Timeout values chosen for optimal UX balance (research-based)
    constexpr int64_t SESSION_TIMEOUT_MS = 0x493E0;     // ~300000ms = 5 minutes
    constexpr int64_t CHALLENGE_TIMEOUT_MS = 0x7530;    // ~30000ms = 30 seconds

// Session state
    struct Session {
        std::string instance_id;
        int64_t created_at_ms;
        int64_t last_activity_ms;
        uid_t client_uid;
        bool authenticated;

        // Check if session has timed out
        bool is_expired(int64_t current_time_ms) const {
            return (current_time_ms - last_activity_ms) > SESSION_TIMEOUT_MS;
        }

        // Get remaining timeout in milliseconds
        int64_t remaining_timeout_ms(int64_t current_time_ms) const {
            int64_t elapsed = current_time_ms - last_activity_ms;
            int64_t remaining = SESSION_TIMEOUT_MS - elapsed;
            return remaining > 0 ? remaining : 0;
        }
    };

// Pending challenge for authentication (encrypted in memory)
    struct PendingChallenge {
        std::vector<uint8_t> encrypted_challenge;  // Challenge encrypted with session key
        std::vector<uint8_t> challenge_nonce;      // Nonce for decryption
        int64_t created_at_ms;
        uid_t client_uid;

        bool is_expired(int64_t current_time_ms) const {
            return (current_time_ms - created_at_ms) > CHALLENGE_TIMEOUT_MS;
        }
    };

// Session status returned to clients
    struct SessionStatus {
        bool has_active_session;
        bool is_own_session;
        int64_t remaining_timeout_ms;
    };

// Session manager handles authentication sessions
    class SessionManager {
    public:
        SessionManager();

        ~SessionManager();

        // Disable copy
        SessionManager(const SessionManager &) = delete;

        SessionManager &operator=(const SessionManager &) = delete;

        // Initialize with session encryption key
        bool initialize();

        // Challenge management
        std::vector<uint8_t> create_challenge(uid_t client_uid);

        std::optional<std::vector<uint8_t>> get_pending_challenge(uid_t client_uid) const;

        bool validate_challenge(const std::vector<uint8_t> &challenge, uid_t client_uid);

        void clear_challenge(uid_t client_uid);

        void consume_challenge(uid_t client_uid);

        // Session management
        bool create_session(const std::string &instance_id, uid_t client_uid);

        bool validate_session(const std::string &instance_id, uid_t client_uid);

        void update_activity(const std::string &instance_id);

        void invalidate_session(const std::string &instance_id);

        void invalidate_all_sessions();

        // Session query
        SessionStatus check_session(const std::string &instance_id, uid_t client_uid);

        std::optional<Session> get_active_session() const;

        bool has_active_session() const;

        // Cleanup expired sessions/challenges
        void cleanup_expired();

    private:
        mutable std::mutex mutex_;

        // Session encryption key (derived at startup, cleared on shutdown)
        std::vector<uint8_t> session_key_;
        bool initialized_ = false;

        // Current active session (only one session allowed at a time)
        std::optional<Session> active_session_;

        // Pending challenges (keyed by client UID, encrypted in memory)
        std::unordered_map<uid_t, PendingChallenge> pending_challenges_;

        // Get current time in milliseconds
        static int64_t current_time_ms();

        // Encrypt/decrypt challenges with session key
        std::vector<uint8_t> encrypt_challenge(const std::vector<uint8_t> &challenge,
                                               std::vector<uint8_t> &out_nonce) const;

        std::vector<uint8_t> decrypt_challenge(const std::vector<uint8_t> &encrypted,
                                               const std::vector<uint8_t> &nonce) const;

        // Derive session key from device entropy
        std::vector<uint8_t> derive_session_key();

        // Securely clear sensitive data
        void secure_clear();
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_SESSION_MANAGER_H
