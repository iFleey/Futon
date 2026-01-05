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

#include "main_loop.h"
#include "watchdog.h"
#include "error.h"
#include "auth/key_whitelist.h"

#include <csignal>
#include <unistd.h>
#include <condition_variable>
#include <mutex>

namespace futon::core {

    MainLoop *MainLoop::s_instance = nullptr;

    MainLoop::MainLoop() {
        s_instance = this;
    }

    MainLoop::~MainLoop() {
        if (s_instance == this) {
            s_instance = nullptr;
        }
    }

    MainLoop *MainLoop::instance() {
        return s_instance;
    }

    void MainLoop::set_shutdown_callback(ShutdownCallback cb) {
        shutdown_cb_ = std::move(cb);
    }

    void MainLoop::set_watchdog(std::shared_ptr<Watchdog> wd) {
        watchdog_ = std::move(wd);
    }

    void MainLoop::signal_handler(int sig) {
        FUTON_LOGI("Received signal %d", sig);

        if (sig == SIGHUP) {
            // SIGHUP: Reload configuration (keys, etc.)
            FUTON_LOGI("SIGHUP received - reloading keys");
            auto &key_whitelist = futon::core::auth::KeyWhitelist::instance();
            if (key_whitelist.reload()) {
                FUTON_LOGI("Key whitelist reloaded: %zu keys", key_whitelist.key_count());
            } else {
                FUTON_LOGW("Key whitelist reload failed");
            }
            return;  // Don't shutdown on SIGHUP
        }

        if (s_instance != nullptr) {
            s_instance->request_shutdown();
        }
    }

    void MainLoop::setup_signal_handlers() {
        struct sigaction sa;
        sa.sa_handler = signal_handler;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;

        // Handle SIGTERM for graceful shutdown
        if (sigaction(SIGTERM, &sa, nullptr) == -1) {
            FUTON_LOGE_ERRNO("Failed to set SIGTERM handler");
        }

        // Handle SIGINT for graceful shutdown (Ctrl+C)
        if (sigaction(SIGINT, &sa, nullptr) == -1) {
            FUTON_LOGE_ERRNO("Failed to set SIGINT handler");
        }

        // Handle SIGHUP for configuration reload (key whitelist)
        if (sigaction(SIGHUP, &sa, nullptr) == -1) {
            FUTON_LOGE_ERRNO("Failed to set SIGHUP handler");
        }

        // Ignore SIGPIPE to prevent crashes on broken pipes
        sa.sa_handler = SIG_IGN;
        if (sigaction(SIGPIPE, &sa, nullptr) == -1) {
            FUTON_LOGE_ERRNO("Failed to ignore SIGPIPE");
        }

        FUTON_LOGI("Signal handlers configured");
    }

    void MainLoop::run() {
        FUTON_LOGI("Starting main loop");

        setup_signal_handlers();
        running_.store(true, std::memory_order_release);

        // Start watchdog if configured
        if (watchdog_) {
            watchdog_->start();
            FUTON_LOGI("Watchdog started");
        }

        FUTON_LOGI("Entering main loop (waiting for shutdown signal)");

        // Main blocking loop - wait for shutdown signal
        // Binder service registration is handled separately in lib_ipc
        std::mutex mtx;
        std::unique_lock<std::mutex> lock(mtx);
        shutdown_cv_.wait(lock, [this] {
            return !running_.load(std::memory_order_acquire);
        });

        FUTON_LOGI("Main loop received shutdown signal");

        // Cleanup
        if (watchdog_) {
            watchdog_->stop();
            FUTON_LOGI("Watchdog stopped");
        }

        // Invoke shutdown callback
        if (shutdown_cb_) {
            FUTON_LOGI("Invoking shutdown callback");
            shutdown_cb_();
        }

        FUTON_LOGI("Main loop exited");
    }

    void MainLoop::request_shutdown() {
        FUTON_LOGI("Shutdown requested");
        running_.store(false, std::memory_order_release);

        // Wake up the main loop
        shutdown_cv_.notify_all();
    }

    bool MainLoop::is_running() const {
        return running_.load(std::memory_order_acquire);
    }

} // namespace futon::core
