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

#include "rate_limiter.h"
#include "core/error.h"

#include <algorithm>
#include <cmath>

namespace futon::core::auth {

    RateLimiter::RateLimiter(const RateLimitConfig &config)
            : config_(config) {
    }

    int64_t RateLimiter::current_time_ms() {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()
        ).count();
    }

    int64_t RateLimiter::calculate_backoff_ms(int failure_count) const {
        if (failure_count <= 0) return 0;

        // Exponential backoff: initial * multiplier^(failures-1)
        double backoff = config_.initial_backoff_ms *
                         std::pow(config_.backoff_multiplier, failure_count - 1);

        // Cap at max backoff
        return static_cast<int64_t>(std::min(backoff, static_cast<double>(config_.max_backoff_ms)));
    }

    RateLimitResult RateLimiter::check_allowed(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();
        auto it = attempts_.find(uid);

        // No previous attempts - allowed
        if (it == attempts_.end()) {
            return {true, 0, config_.max_failures, nullptr};
        }

        AuthAttempt &attempt = it->second;

        // Check if reset window has passed (no attempts for a while)
        if (attempt.last_attempt_ms > 0 &&
            (now - attempt.last_attempt_ms) > config_.reset_window_ms) {
            attempt.reset();
            return {true, 0, config_.max_failures, nullptr};
        }

        // Check if currently locked out
        if (attempt.lockout_until_ms > now) {
            int64_t remaining = attempt.lockout_until_ms - now;
            return {
                    false,
                    remaining,
                    -1,
                    "Too many failed attempts. Please wait before retrying."
            };
        }

        // Check if max failures reached (but lockout expired)
        if (attempt.failed_count >= config_.max_failures) {
            // Lockout expired, but still at max failures - apply new lockout
            int64_t backoff = calculate_backoff_ms(attempt.failed_count);
            attempt.lockout_until_ms = now + backoff;

            return {
                    false,
                    backoff,
                    0,
                    "Maximum authentication attempts exceeded."
            };
        }

        // Allowed, but with reduced remaining attempts
        int remaining = config_.max_failures - attempt.failed_count;
        return {true, 0, remaining, nullptr};
    }

    void RateLimiter::record_success(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = attempts_.find(uid);
        if (it != attempts_.end()) {
            it->second.reset();
            FUTON_LOGI("Rate limiter: UID %d reset after successful auth", uid);
        }
    }

    void RateLimiter::record_failure(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();
        AuthAttempt &attempt = attempts_[uid];

        // First failure in this window
        if (attempt.failed_count == 0) {
            attempt.first_failure_ms = now;
        }

        attempt.failed_count++;
        attempt.last_attempt_ms = now;

        // Calculate and apply lockout if threshold reached
        if (attempt.failed_count >= config_.max_failures) {
            int64_t backoff = calculate_backoff_ms(attempt.failed_count);
            attempt.lockout_until_ms = now + backoff;

            FUTON_LOGW("Rate limiter: UID %d locked out for %lld ms after %d failures",
                       uid, (long long) backoff, attempt.failed_count);
        } else {
            FUTON_LOGD("Rate limiter: UID %d failure %d/%d",
                       uid, attempt.failed_count, config_.max_failures);
        }
    }

    bool RateLimiter::is_locked_out(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = attempts_.find(uid);
        if (it == attempts_.end()) return false;

        return it->second.lockout_until_ms > current_time_ms();
    }

    int RateLimiter::get_failed_count(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = attempts_.find(uid);
        if (it == attempts_.end()) return 0;

        return it->second.failed_count;
    }

    int64_t RateLimiter::get_lockout_remaining_ms(uid_t uid) const {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = attempts_.find(uid);
        if (it == attempts_.end()) return 0;

        int64_t now = current_time_ms();
        if (it->second.lockout_until_ms <= now) return 0;

        return it->second.lockout_until_ms - now;
    }

    void RateLimiter::reset_uid(uid_t uid) {
        std::lock_guard<std::mutex> lock(mutex_);
        attempts_.erase(uid);
        FUTON_LOGI("Rate limiter: UID %d manually reset", uid);
    }

    void RateLimiter::reset_all() {
        std::lock_guard<std::mutex> lock(mutex_);
        attempts_.clear();
        FUTON_LOGI("Rate limiter: All UIDs reset");
    }

    void RateLimiter::cleanup_expired() {
        std::lock_guard<std::mutex> lock(mutex_);

        int64_t now = current_time_ms();
        int64_t cleanup_threshold = now - config_.reset_window_ms;

        for (auto it = attempts_.begin(); it != attempts_.end();) {
            // Remove entries that haven't had activity in reset_window_ms
            if (it->second.last_attempt_ms < cleanup_threshold) {
                FUTON_LOGD("Rate limiter: Cleaning up expired entry for UID %d", it->first);
                it = attempts_.erase(it);
            } else {
                ++it;
            }
        }
    }

    RateLimiter::Stats RateLimiter::get_stats() const {
        std::lock_guard<std::mutex> lock(mutex_);

        Stats stats{};
        stats.tracked_uids = attempts_.size();

        int64_t now = current_time_ms();
        for (const auto &[uid, attempt]: attempts_) {
            if (attempt.lockout_until_ms > now) {
                stats.locked_out_uids++;
            }
            stats.total_failures += attempt.failed_count;
        }

        return stats;
    }

} // namespace futon::core::auth
