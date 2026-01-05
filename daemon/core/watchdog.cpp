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

#include "watchdog.h"
#include "error.h"

#include <sys/resource.h>
#include <chrono>

namespace futon::core {

    Watchdog::Watchdog(int timeout_ms)
            : timeout_ms_(timeout_ms) {
        FUTON_LOGD("Watchdog created with timeout=%dms", timeout_ms);
    }

    Watchdog::~Watchdog() {
        stop();
    }

    void Watchdog::start() {
        if (running_.load(std::memory_order_acquire)) {
            FUTON_LOGW("Watchdog already running");
            return;
        }

        // Initialize heartbeat to current time
        last_heartbeat_.store(get_current_time_ms(), std::memory_order_release);
        running_.store(true, std::memory_order_release);

        monitor_thread_ = std::thread(&Watchdog::monitor_loop, this);

        FUTON_LOGI("Watchdog started with timeout=%dms, check_interval=%dms",
                   timeout_ms_, CHECK_INTERVAL_MS);
    }

    void Watchdog::stop() {
        if (!running_.load(std::memory_order_acquire)) {
            return;
        }

        FUTON_LOGI("Stopping watchdog");
        running_.store(false, std::memory_order_release);

        if (monitor_thread_.joinable()) {
            monitor_thread_.join();
        }

        FUTON_LOGI("Watchdog stopped");
    }

    void Watchdog::feed() {
        last_heartbeat_.store(get_current_time_ms(), std::memory_order_release);
        armed_.store(true, std::memory_order_release);
    }

    void Watchdog::disarm() {
        armed_.store(false, std::memory_order_release);
        last_heartbeat_.store(get_current_time_ms(), std::memory_order_release);
    }

    void Watchdog::set_recovery_callback(RecoveryCallback cb) {
        recovery_cb_ = std::move(cb);
    }

    bool Watchdog::is_running() const {
        return running_.load(std::memory_order_acquire);
    }

    int64_t Watchdog::get_last_heartbeat() const {
        return last_heartbeat_.load(std::memory_order_acquire);
    }

    void Watchdog::set_thread_priority() {
        // Set nice value to -5 for high priority (but not real-time)
        if (setpriority(PRIO_PROCESS, 0, -5) == -1) {
            FUTON_LOGW("Failed to set watchdog thread priority: %s", strerror(errno));
        } else {
            FUTON_LOGD("Watchdog thread priority set to nice(-5)");
        }
    }

    int64_t Watchdog::get_current_time_ms() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();
    }

    void Watchdog::monitor_loop() {
        FUTON_LOGD("Watchdog monitor thread started");

        set_thread_priority();

        while (running_.load(std::memory_order_acquire)) {
            // Sleep for check interval
            std::this_thread::sleep_for(std::chrono::milliseconds(CHECK_INTERVAL_MS));

            if (!running_.load(std::memory_order_acquire)) {
                break;
            }

            // Only check timeout when armed (pipeline is actively running)
            if (!armed_.load(std::memory_order_acquire)) {
                continue;
            }

            int64_t now = get_current_time_ms();
            int64_t last = last_heartbeat_.load(std::memory_order_acquire);
            int64_t elapsed = now - last;

            if (elapsed > timeout_ms_) {
                FUTON_LOGW("Watchdog timeout detected: elapsed=%lldms, timeout=%dms",
                           static_cast<long long>(elapsed), timeout_ms_);

                if (recovery_cb_) {
                    FUTON_LOGI("Triggering recovery callback");
                    recovery_cb_();

                    // Reset heartbeat after recovery attempt
                    last_heartbeat_.store(get_current_time_ms(), std::memory_order_release);
                } else {
                    FUTON_LOGW("No recovery callback set, timeout ignored");
                }
            }
        }

        FUTON_LOGD("Watchdog monitor thread exited");
    }

} // namespace futon::core
