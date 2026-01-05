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

#ifndef FUTON_CORE_MAIN_LOOP_H
#define FUTON_CORE_MAIN_LOOP_H

#include <atomic>
#include <functional>
#include <memory>
#include <condition_variable>

namespace futon::core {

// Forward declaration
    class Watchdog;

    class MainLoop {
    public:
        using ShutdownCallback = std::function<void()>;

        MainLoop();

        ~MainLoop();

        void set_shutdown_callback(ShutdownCallback cb);

        void set_watchdog(std::shared_ptr<Watchdog> wd);

        void run();

        void request_shutdown();

        bool is_running() const;

        // Get singleton instance for signal handler access
        static MainLoop *instance();

    private:
        std::atomic<bool> running_{false};
        std::condition_variable shutdown_cv_;
        ShutdownCallback shutdown_cb_;
        std::shared_ptr<Watchdog> watchdog_;

        static MainLoop *s_instance;

        static void signal_handler(int sig);

        void setup_signal_handlers();
    };

} // namespace futon::core

#endif // FUTON_CORE_MAIN_LOOP_H
