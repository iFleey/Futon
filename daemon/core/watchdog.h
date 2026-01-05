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

#ifndef FUTON_CORE_WATCHDOG_H
#define FUTON_CORE_WATCHDOG_H

#include <atomic>
#include <functional>
#include <thread>
#include <cstdint>

namespace futon::core {

    class Watchdog {
    public:
        using RecoveryCallback = std::function<void()>;

        explicit Watchdog(int timeout_ms = 200);

        ~Watchdog();

        // Disable copy
        Watchdog(const Watchdog &) = delete;

        Watchdog &operator=(const Watchdog &) = delete;

        void start();

        void stop();

        // Feed the watchdog and arm it (call from pipeline loop)
        void feed();

        // Disarm the watchdog (call when pipeline stops)
        void disarm();

        void set_recovery_callback(RecoveryCallback cb);

        bool is_running() const;

        int64_t get_last_heartbeat() const;

    private:
        static constexpr int CHECK_INTERVAL_MS = 50;

        std::atomic<bool> running_{false};
        std::atomic<bool> armed_{false};  // Only check timeout when armed
        std::atomic<int64_t> last_heartbeat_{0};
        int timeout_ms_;
        RecoveryCallback recovery_cb_;
        std::thread monitor_thread_;

        void monitor_loop();

        void set_thread_priority();

        int64_t get_current_time_ms() const;
    };

} // namespace futon::core

#endif // FUTON_CORE_WATCHDOG_H
