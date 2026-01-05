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

#include "shell_executor.h"
#include "core/error.h"

#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <cstring>
#include <chrono>

using namespace futon::core;

namespace futon::input {

    namespace {

        std::string exec_command(const char *cmd, int timeout_ms, int *exit_status) {
            int pipefd[2];
            if (pipe(pipefd) < 0) {
                if (exit_status) *exit_status = -1;
                return "";
            }

            pid_t pid = fork();
            if (pid < 0) {
                close(pipefd[0]);
                close(pipefd[1]);
                if (exit_status) *exit_status = -1;
                return "";
            }

            if (pid == 0) {
                close(pipefd[0]);
                dup2(pipefd[1], STDOUT_FILENO);
                dup2(pipefd[1], STDERR_FILENO);
                close(pipefd[1]);

                int null_fd = open("/dev/null", O_RDONLY);
                if (null_fd >= 0) {
                    dup2(null_fd, STDIN_FILENO);
                    close(null_fd);
                }

                execl("/system/bin/sh", "sh", "-c", cmd, nullptr);
                _exit(127);
            }

            close(pipefd[1]);
            fcntl(pipefd[0], F_SETFL, O_NONBLOCK);

            std::string result;
            char buffer[1024];
            auto start = std::chrono::steady_clock::now();
            bool done = false;

            while (!done) {
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - start).count();

                if (elapsed >= timeout_ms) {
                    kill(pid, SIGKILL);
                    break;
                }

                struct pollfd pfd = {pipefd[0], POLLIN, 0};
                int ret = poll(&pfd, 1, 50);

                if (ret > 0 && (pfd.revents & POLLIN)) {
                    ssize_t n = read(pipefd[0], buffer, sizeof(buffer) - 1);
                    if (n > 0) {
                        buffer[n] = '\0';
                        result += buffer;
                    } else if (n == 0) {
                        done = true;
                    }
                }

                if (pfd.revents & (POLLHUP | POLLERR)) {
                    while ((ret = read(pipefd[0], buffer, sizeof(buffer) - 1)) > 0) {
                        buffer[ret] = '\0';
                        result += buffer;
                    }
                    done = true;
                }

                int status;
                pid_t w = waitpid(pid, &status, WNOHANG);
                if (w == pid) {
                    while ((ret = read(pipefd[0], buffer, sizeof(buffer) - 1)) > 0) {
                        buffer[ret] = '\0';
                        result += buffer;
                    }
                    if (exit_status) {
                        *exit_status = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
                    }
                    done = true;
                }
            }

            close(pipefd[0]);

            int status;
            waitpid(pid, &status, WNOHANG);

            while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
                result.pop_back();
            }

            return result;
        }

    } // anonymous namespace

    ShellExecutor &ShellExecutor::instance() {
        static ShellExecutor inst;
        return inst;
    }

    ShellExecutor::~ShellExecutor() {
        stop();
    }

    bool ShellExecutor::start() {
        if (running_.load()) return true;

        running_.store(true);
        worker_ = std::thread(&ShellExecutor::worker_loop, this);
        pthread_setname_np(worker_.native_handle(), "FutonShellExec");

        FUTON_LOGI("ShellExecutor started");
        return true;
    }

    void ShellExecutor::stop() {
        if (!running_.load()) return;

        running_.store(false);
        queue_cv_.notify_all();

        if (worker_.joinable()) {
            worker_.join();
        }
    }

    void ShellExecutor::worker_loop() {
        while (running_.load()) {
            Request *req = nullptr;

            {
                std::unique_lock<std::mutex> lock(queue_mtx_);
                queue_cv_.wait_for(lock, std::chrono::milliseconds(100), [this] {
                    return !queue_.empty() || !running_.load();
                });

                if (!running_.load() && queue_.empty()) break;
                if (queue_.empty()) continue;

                req = queue_.front();
                queue_.pop();
            }

            if (!req) continue;

            int status = -1;
            std::string output = exec_command(req->cmd.c_str(), req->timeout_ms, &status);

            {
                std::lock_guard<std::mutex> lock(req->mtx);
                req->output = std::move(output);
                req->status = status;
                req->done = true;
            }
            req->cv.notify_one();
        }
    }

    std::string ShellExecutor::exec(const char *cmd, int timeout_ms) {
        if (!running_.load()) start();

        Request req;
        req.type = Request::GET_OUTPUT;
        req.cmd = cmd;
        req.timeout_ms = timeout_ms;

        {
            std::lock_guard<std::mutex> lock(queue_mtx_);
            queue_.push(&req);
        }
        queue_cv_.notify_one();

        {
            std::unique_lock<std::mutex> lock(req.mtx);
            req.cv.wait_for(lock, std::chrono::milliseconds(timeout_ms + 1000), [&req] {
                return req.done;
            });
        }

        return req.output;
    }

    int ShellExecutor::exec_status(const char *cmd, int timeout_ms) {
        if (!running_.load()) start();

        Request req;
        req.type = Request::GET_STATUS;
        req.cmd = cmd;
        req.timeout_ms = timeout_ms;

        {
            std::lock_guard<std::mutex> lock(queue_mtx_);
            queue_.push(&req);
        }
        queue_cv_.notify_one();

        {
            std::unique_lock<std::mutex> lock(req.mtx);
            req.cv.wait_for(lock, std::chrono::milliseconds(timeout_ms + 1000), [&req] {
                return req.done;
            });
        }

        return req.done ? req.status : -1;
    }

    bool ShellExecutor::can_direct_exec(const char *) { return false; }

    std::vector<std::string> ShellExecutor::parse_args(const char *) { return {}; }

} // namespace futon::input
