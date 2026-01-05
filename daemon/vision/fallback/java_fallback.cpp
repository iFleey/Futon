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

#include "vision/fallback/java_fallback.h"
#include "vision/fallback/java_helper_receiver.h"
#include "core/error.h"

#include <unistd.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <signal.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstring>
#include <chrono>

using namespace futon::core;

namespace futon::vision {

// Binder receiver instance
    static std::unique_ptr<JavaHelperReceiver> g_receiver;

// Default paths for helper DEX
    static constexpr const char *HELPER_DEX_PATHS[] = {
            "/data/local/tmp/futon_helper.dex",
            "/data/data/me.fleey.futon/files/futon_helper.dex",
            "/sdcard/Android/data/me.fleey.futon/files/futon_helper.dex"
    };

// Binder service name for IPC
    static constexpr const char *BINDER_SERVICE_NAME = "futon_java_helper";

    JavaFallback::JavaFallback() = default;

    JavaFallback::~JavaFallback() {
        shutdown();
    }

    bool JavaFallback::initialize() {
        if (initialized_) {
            FUTON_LOGW("JavaFallback: already initialized");
            return true;
        }

        FUTON_LOGI("JavaFallback: initializing");

        // Check if app_process is available
        if (!is_available()) {
            FUTON_LOGE("JavaFallback: app_process not available");
            return false;
        }

        // Setup Binder receiver for display token
        if (!setup_binder_receiver()) {
            FUTON_LOGE("JavaFallback: failed to setup Binder receiver");
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("JavaFallback: initialized successfully");
        return true;
    }

    void JavaFallback::shutdown() {
        if (!initialized_) {
            return;
        }

        FUTON_LOGI("JavaFallback: shutting down");

        // Terminate any running helper process
        terminate_helper();

        // Cleanup Binder receiver
        cleanup_binder_receiver();

        initialized_ = false;
    }

    bool JavaFallback::is_available() {
        // Check for app_process64 first (64-bit), then app_process (32-bit)
        struct stat st;
        if (stat(JavaHelperLauncher::APP_PROCESS64_PATH, &st) == 0) {
            if (st.st_mode & S_IXUSR) {
                FUTON_LOGD("JavaFallback: found app_process64");
                return true;
            }
        }
        if (stat(JavaHelperLauncher::APP_PROCESS_PATH, &st) == 0) {
            if (st.st_mode & S_IXUSR) {
                FUTON_LOGD("JavaFallback: found app_process");
                return true;
            }
        }
        return false;
    }

    const char *JavaFallback::get_helper_dex_path() {
        struct stat st;
        for (const char *path: HELPER_DEX_PATHS) {
            if (stat(path, &st) == 0 && S_ISREG(st.st_mode)) {
                return path;
            }
        }
        return HELPER_DEX_PATHS[0]; // Default path
    }

    bool JavaFallback::setup_binder_receiver() {
        service_name_ = BINDER_SERVICE_NAME;

        // Create and initialize the Binder receiver
        g_receiver = std::make_unique<JavaHelperReceiver>();

        if (!g_receiver->initialize(service_name_.c_str())) {
            FUTON_LOGW("JavaFallback: Binder receiver init failed, will use fallback file");
            g_receiver.reset();
            // Continue anyway - we can use the fallback file mechanism
        } else {
            // Set up callbacks
            g_receiver->set_token_callback(
                    [this](void *token, int32_t width, int32_t height) {
                        on_display_token_received(token, width, height);
                    });

            g_receiver->set_error_callback(
                    [this](const char *error) {
                        on_helper_error(error);
                    });
        }

        FUTON_LOGD("JavaFallback: Binder receiver setup (service: %s)", service_name_.c_str());
        return true;
    }

    void JavaFallback::cleanup_binder_receiver() {
        if (g_receiver) {
            g_receiver->shutdown();
            g_receiver.reset();
        }

        if (binder_fd_ >= 0) {
            close(binder_fd_);
            binder_fd_ = -1;
        }
        service_name_.clear();
    }

    Result <JavaHelperResult> JavaFallback::create_display(
            uint32_t width,
            uint32_t height,
            const char *name,
            int timeout_ms) {

        if (!initialized_) {
            FUTON_LOGE("JavaFallback: not initialized");
            return Result<JavaHelperResult>::error(FutonError::NotInitialized);
        }

        if (helper_running_) {
            FUTON_LOGW("JavaFallback: helper already running");
            return Result<JavaHelperResult>::error(FutonError::ResourceExhausted);
        }

        FUTON_LOGI("JavaFallback: creating display %ux%u name=%s timeout=%dms",
                   width, height, name, timeout_ms);

        // Reset result state
        {
            std::lock_guard<std::mutex> lock(result_mutex_);
            result_ready_ = false;
            pending_result_ = JavaHelperResult{};
        }

        // Launch helper process
        if (!launch_helper_process(width, height, name)) {
            FUTON_LOGE("JavaFallback: failed to launch helper process");
            return Result<JavaHelperResult>::error(FutonError::InternalError);
        }

        // Wait for result with timeout
        if (!wait_for_result(timeout_ms)) {
            FUTON_LOGE("JavaFallback: timeout waiting for display token");
            terminate_helper();
            return Result<JavaHelperResult>::error(FutonError::Timeout);
        }

        // Get result
        JavaHelperResult result;
        {
            std::lock_guard<std::mutex> lock(result_mutex_);
            result = pending_result_;
        }

        if (!result.success) {
            FUTON_LOGE("JavaFallback: helper failed: %s", result.error_message.c_str());
            return Result<JavaHelperResult>::error(FutonError::PrivateApiUnavailable);
        }

        FUTON_LOGI("JavaFallback: display created successfully, token=%p", result.display_token);
        return Result<JavaHelperResult>::ok(result);
    }

    bool JavaFallback::destroy_display(void *display_token) {
        if (!display_token) {
            return false;
        }

        FUTON_LOGI("JavaFallback: destroying display token=%p", display_token);

        // In a full implementation, this would send a destroy command
        // to the Java helper or call SurfaceControl.destroyDisplay via JNI

        return true;
    }

    bool JavaFallback::launch_helper_process(uint32_t width, uint32_t height, const char *name) {
        const char *dex_path = get_helper_dex_path();

        // Build arguments string
        char args[256];
        snprintf(args, sizeof(args), "%u %u %s %s",
                 width, height, name, service_name_.c_str());

        FUTON_LOGD("JavaFallback: launching helper dex=%s args=%s", dex_path, args);

        pid_t pid;
        if (!JavaHelperLauncher::launch(dex_path,
                                        "me.fleey.futon.helper.SurfaceHelper",
                                        args, &pid)) {
            FUTON_LOGE("JavaFallback: failed to launch helper");
            return false;
        }

        helper_pid_ = pid;
        helper_running_ = true;
        FUTON_LOGI("JavaFallback: helper launched pid=%d", pid);
        return true;
    }

    bool JavaFallback::wait_for_result(int timeout_ms) {
        std::unique_lock<std::mutex> lock(result_mutex_);

        auto deadline = std::chrono::steady_clock::now() +
                        std::chrono::milliseconds(timeout_ms);

        while (!result_ready_) {
            if (result_cv_.wait_until(lock, deadline) == std::cv_status::timeout) {
                FUTON_LOGD("JavaFallback: Binder callback not received, timeout");
                return false;
            }

            // Check if helper process died
            if (helper_pid_ > 0 && !JavaHelperLauncher::is_running(helper_pid_)) {
                FUTON_LOGW("JavaFallback: helper process died unexpectedly");
                return false;
            }
        }

        return true;
    }

    void JavaFallback::terminate_helper() {
        if (helper_pid_ > 0) {
            FUTON_LOGD("JavaFallback: terminating helper pid=%d", helper_pid_);
            JavaHelperLauncher::terminate(helper_pid_);
            JavaHelperLauncher::wait_for_exit(helper_pid_, 1000);
            helper_pid_ = -1;
        }
        helper_running_ = false;
    }

    void JavaFallback::on_display_token_received(void *token, int32_t width, int32_t height) {
        std::lock_guard<std::mutex> lock(result_mutex_);
        pending_result_.display_token = token;
        pending_result_.width = width;
        pending_result_.height = height;
        pending_result_.success = true;
        result_ready_ = true;
        result_cv_.notify_one();
    }

    void JavaFallback::on_helper_error(const char *error) {
        std::lock_guard<std::mutex> lock(result_mutex_);
        pending_result_.success = false;
        pending_result_.error_message = error ? error : "Unknown error";
        result_ready_ = true;
        result_cv_.notify_one();
    }

// JavaHelperLauncher implementation

    bool JavaHelperLauncher::launch(
            const char *dex_path,
            const char *class_name,
            const char *args,
            pid_t *out_pid) {

        if (!dex_path || !class_name || !out_pid) {
            FUTON_LOGE("JavaHelperLauncher: invalid arguments");
            return false;
        }

        // Determine which app_process to use
        const char *app_process = APP_PROCESS64_PATH;
        struct stat st;
        if (stat(APP_PROCESS64_PATH, &st) != 0) {
            app_process = APP_PROCESS_PATH;
            if (stat(APP_PROCESS_PATH, &st) != 0) {
                FUTON_LOGE("JavaHelperLauncher: app_process not found");
                return false;
            }
        }

        FUTON_LOGD("JavaHelperLauncher: using %s", app_process);

        pid_t pid = fork();

        if (pid < 0) {
            FUTON_LOGE_ERRNO("JavaHelperLauncher: fork failed");
            return false;
        }

        if (pid == 0) {
            // Child process

            // Set CLASSPATH environment variable
            setenv("CLASSPATH", dex_path, 1);

            // Redirect stdout/stderr to /dev/null to avoid blocking
            int null_fd = open("/dev/null", O_RDWR);
            if (null_fd >= 0) {
                dup2(null_fd, STDIN_FILENO);
                dup2(null_fd, STDOUT_FILENO);
                dup2(null_fd, STDERR_FILENO);
                close(null_fd);
            }

            // Execute app_process
            // Format: app_process [options] <base-dir> <class-name> [args...]
            // We use /system/bin as base-dir and pass our class name
            execl(app_process,
                  app_process,
                  "/system/bin",      // base directory
                  "--nice-name=futon_helper",  // process name
                  class_name,         // main class
                  args,               // arguments
                  nullptr);

            // If execl returns, it failed
            FUTON_LOGE_ERRNO("JavaHelperLauncher: execl failed");
            _exit(127);
        }

        // Parent process
        *out_pid = pid;
        FUTON_LOGD("JavaHelperLauncher: forked child pid=%d", pid);
        return true;
    }

    bool JavaHelperLauncher::is_running(pid_t pid) {
        if (pid <= 0) {
            return false;
        }

        // Check if process exists without waiting
        int status;
        pid_t result = waitpid(pid, &status, WNOHANG);

        if (result == 0) {
            // Process still running
            return true;
        } else if (result == pid) {
            // Process exited
            return false;
        } else {
            // Error (process doesn't exist or not our child)
            return false;
        }
    }

    bool JavaHelperLauncher::terminate(pid_t pid) {
        if (pid <= 0) {
            return false;
        }

        // Send SIGTERM first
        if (kill(pid, SIGTERM) == 0) {
            FUTON_LOGD("JavaHelperLauncher: sent SIGTERM to pid=%d", pid);

            // Wait briefly for graceful exit
            usleep(100000); // 100ms

            if (!is_running(pid)) {
                return true;
            }

            // Force kill if still running
            if (kill(pid, SIGKILL) == 0) {
                FUTON_LOGD("JavaHelperLauncher: sent SIGKILL to pid=%d", pid);
                return true;
            }
        }

        FUTON_LOGW("JavaHelperLauncher: failed to terminate pid=%d", pid);
        return false;
    }

    int JavaHelperLauncher::wait_for_exit(pid_t pid, int timeout_ms) {
        if (pid <= 0) {
            return -1;
        }

        auto start = std::chrono::steady_clock::now();
        auto timeout = std::chrono::milliseconds(timeout_ms);

        while (true) {
            int status;
            pid_t result = waitpid(pid, &status, WNOHANG);

            if (result == pid) {
                if (WIFEXITED(status)) {
                    int exit_code = WEXITSTATUS(status);
                    FUTON_LOGD("JavaHelperLauncher: pid=%d exited with code %d", pid, exit_code);
                    return exit_code;
                } else if (WIFSIGNALED(status)) {
                    int sig = WTERMSIG(status);
                    FUTON_LOGD("JavaHelperLauncher: pid=%d killed by signal %d", pid, sig);
                    return -sig;
                }
                return -1;
            } else if (result < 0) {
                // Error or not our child
                return -1;
            }

            // Check timeout
            auto elapsed = std::chrono::steady_clock::now() - start;
            if (elapsed >= timeout) {
                FUTON_LOGW("JavaHelperLauncher: timeout waiting for pid=%d", pid);
                return -1;
            }

            // Sleep briefly before checking again
            usleep(10000); // 10ms
        }
    }

} // namespace futon::vision
