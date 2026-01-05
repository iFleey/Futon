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

#ifndef FUTON_INPUT_SHELL_EXECUTOR_H
#define FUTON_INPUT_SHELL_EXECUTOR_H

#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>

namespace futon::input {

    class ShellExecutor {
    public:
        static ShellExecutor &instance();

        bool start();

        void stop();

        bool is_running() const { return running_.load(); }

        std::string exec(const char *cmd, int timeout_ms = 2000);

        int exec_status(const char *cmd, int timeout_ms = 2000);

    private:
        ShellExecutor() = default;

        ~ShellExecutor();

        ShellExecutor(const ShellExecutor &) = delete;

        ShellExecutor &operator=(const ShellExecutor &) = delete;

        void worker_loop();

        static bool can_direct_exec(const char *cmd);

        static std::vector<std::string> parse_args(const char *cmd);

        struct Request {
            enum Type {
                GET_OUTPUT, GET_STATUS
            };
            Type type;
            std::string cmd;
            int timeout_ms;

            std::string output;
            int status = -1;
            bool done = false;
            std::condition_variable cv;
            std::mutex mtx;
        };

        std::thread worker_;
        std::atomic<bool> running_{false};

        std::mutex queue_mtx_;
        std::condition_variable queue_cv_;
        std::queue<Request *> queue_;
    };

} // namespace futon::input

#endif
