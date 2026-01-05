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

#ifndef FUTON_VISION_FALLBACK_JAVA_FALLBACK_H
#define FUTON_VISION_FALLBACK_JAVA_FALLBACK_H

#include "core/error.h"
#include <cstdint>
#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>

namespace futon::vision {

/**
 * Result from Java helper process containing display token.
 */
    struct JavaHelperResult {
        void *display_token = nullptr;
        int32_t width = 0;
        int32_t height = 0;
        bool success = false;
        std::string error_message;
    };

/**
 * JavaFallback - Fallback mechanism for SurfaceControl access.
 */
    class JavaFallback {
    public:
        JavaFallback();

        ~JavaFallback();

        // Disable copy
        JavaFallback(const JavaFallback &) = delete;

        JavaFallback &operator=(const JavaFallback &) = delete;

        /**
         * Initialize the Java fallback system.
         * Prepares the Binder IPC channel for receiving display tokens.
         *
         * @return true on success
         */
        bool initialize();

        /**
         * Shutdown the Java fallback system.
         * Terminates any running helper process and cleans up resources.
         */
        void shutdown();

        /**
         * Check if Java fallback is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Create a virtual display using Java reflection.
         *
         * @param width Display width
         * @param height Display height
         * @param name Display name
         * @param timeout_ms Timeout in milliseconds (default 5000ms)
         * @return Result containing display token or error
         */
        core::Result <JavaHelperResult> create_display(
                uint32_t width,
                uint32_t height,
                const char *name = "FutonCapture",
                int timeout_ms = 5000);

        /**
         * Destroy a display created via Java fallback.
         *
         * @param display_token Token returned from create_display
         * @return true on success
         */
        bool destroy_display(void *display_token);

        /**
         * Check if Java fallback is available on this device.
         * Verifies app_process exists and is executable.
         */
        static bool is_available();

        /**
         * Get the path to the Java helper DEX file.
         */
        static const char *get_helper_dex_path();

    private:
        bool initialized_ = false;
        std::atomic<bool> helper_running_{false};
        pid_t helper_pid_ = -1;

        // Binder IPC for receiving display token
        int binder_fd_ = -1;
        std::string service_name_;

        // Synchronization for async result
        std::mutex result_mutex_;
        std::condition_variable result_cv_;
        JavaHelperResult pending_result_;
        bool result_ready_ = false;

        // Internal methods
        bool setup_binder_receiver();

        void cleanup_binder_receiver();

        bool launch_helper_process(uint32_t width, uint32_t height, const char *name);

        bool wait_for_result(int timeout_ms);

        void terminate_helper();

        // Binder callback handler
        void on_display_token_received(void *token, int32_t width, int32_t height);

        void on_helper_error(const char *error);
    };

/**
 * JavaHelperLauncher - Handles fork/exec of Java helper process.
 *
 * Uses app_process to start a minimal Java process that:
 * - Loads the SurfaceHelper class
 * - Uses reflection to access SurfaceControl
 * - Sends display token back via Binder
 */
    class JavaHelperLauncher {
    public:
        static constexpr const char *APP_PROCESS_PATH = "/system/bin/app_process";
        static constexpr const char *APP_PROCESS64_PATH = "/system/bin/app_process64";

        /**
         * Launch the Java helper process.
         *
         * @param dex_path Path to the helper DEX file
         * @param class_name Fully qualified class name to run
         * @param args Arguments to pass to the helper
         * @param out_pid Output: PID of the launched process
         * @return true on success
         */
        static bool launch(
                const char *dex_path,
                const char *class_name,
                const char *args,
                pid_t *out_pid);

        /**
         * Check if a helper process is still running.
         */
        static bool is_running(pid_t pid);

        /**
         * Terminate a helper process.
         */
        static bool terminate(pid_t pid);

        /**
         * Wait for a helper process to exit.
         *
         * @param pid Process ID
         * @param timeout_ms Timeout in milliseconds
         * @return Exit code, or -1 on timeout/error
         */
        static int wait_for_exit(pid_t pid, int timeout_ms);

    private:
        static constexpr const char *CLASSPATH_ENV = "CLASSPATH";
    };

} // namespace futon::vision

#endif // FUTON_VISION_FALLBACK_JAVA_FALLBACK_H
