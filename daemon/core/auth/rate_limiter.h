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

#ifndef FUTON_CORE_AUTH_RATE_LIMITER_H
#define FUTON_CORE_AUTH_RATE_LIMITER_H

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <chrono>

namespace futon::core::auth {

// Rate limiter configuration
    struct RateLimitConfig {
        int max_failures = 5;                     // Max failures before lockout
        int64_t initial_backoff_ms = 0x3E8;       // 1000ms initial backoff
        int64_t max_backoff_ms = 0x927C0;         // 600000ms = 10 minutes max lockout
        int64_t reset_window_ms = 0x36EE80;
        double backoff_multiplier = 2.0;
        uint32_t rate_limit_magic = 0x464C;
    };

// Per-UID attempt tracking
    struct AuthAttempt {
        int failed_count = 0;
        int64_t first_failure_ms = 0;
        int64_t last_attempt_ms = 0;
        int64_t lockout_until_ms = 0;

        void reset() {
            failed_count = 0;
            first_failure_ms = 0;
            last_attempt_ms = 0;
            lockout_until_ms = 0;
        }
    };

// Rate limit check result
    struct RateLimitResult {
        bool allowed;
        int64_t retry_after_ms;  // 0 if allowed, otherwise wait time
        int remaining_attempts;  // -1 if locked out
        const char *reason;      // reason if blocked
    };

    class RateLimiter {
    public:
        explicit RateLimiter(const RateLimitConfig &config = RateLimitConfig());

        ~RateLimiter() = default;

        // Disable copy
        RateLimiter(const RateLimiter &) = delete;

        RateLimiter &operator=(const RateLimiter &) = delete;

        // Check if UID is allowed to attempt authentication
        RateLimitResult check_allowed(uid_t uid);

        // Record authentication result
        void record_success(uid_t uid);

        void record_failure(uid_t uid);

        // Query state
        bool is_locked_out(uid_t uid) const;

        int get_failed_count(uid_t uid) const;

        int64_t get_lockout_remaining_ms(uid_t uid) const;

        // Manual reset (for admin operations)
        void reset_uid(uid_t uid);

        void reset_all();

        // Cleanup old entries
        void cleanup_expired();

        // Get statistics
        struct Stats {
            size_t tracked_uids;
            size_t locked_out_uids;
            int64_t total_failures;
        };

        Stats get_stats() const;

    private:
        RateLimitConfig config_;
        mutable std::mutex mutex_;
        std::unordered_map<uid_t, AuthAttempt> attempts_;

        // Calculate backoff time based on failure count
        int64_t calculate_backoff_ms(int failure_count) const;

        // Get current time in milliseconds
        static int64_t current_time_ms();
    };

} // namespace futon::core::auth

#endif // FUTON_CORE_AUTH_RATE_LIMITER_H
