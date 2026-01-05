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

#include "process_init.h"
#include "error.h"

#include <sys/mman.h>
#include <sys/resource.h>
#include <sched.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <thread>

namespace futon::core {

    const char *ProcessInit::s_pid_file_path = nullptr;

    static bool s_binder_initialized = false;
    static std::thread s_binder_thread;

// Function pointers for Binder NDK functions
    using ABinderProcess_setThreadPoolMaxThreadCount_fn = bool (*)(uint32_t);
    using ABinderProcess_startThreadPool_fn = void (*)();
    using ABinderProcess_joinThreadPool_fn = void (*)();

    static ABinderProcess_setThreadPoolMaxThreadCount_fn fn_setThreadPoolMaxThreadCount = nullptr;
    static ABinderProcess_startThreadPool_fn fn_startThreadPool = nullptr;
    static ABinderProcess_joinThreadPool_fn fn_joinThreadPool = nullptr;

    static bool init_binder_thread_pool() {
        if (s_binder_initialized) {
            return true;
        }

        // Load libbinder_ndk.so
        void *libbinder_ndk = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (!libbinder_ndk) {
            FUTON_LOGW("Failed to load libbinder_ndk.so: %s", dlerror());
            return false;
        }

        // Resolve thread pool functions
        fn_setThreadPoolMaxThreadCount = reinterpret_cast<ABinderProcess_setThreadPoolMaxThreadCount_fn>(
                dlsym(libbinder_ndk, "ABinderProcess_setThreadPoolMaxThreadCount"));
        fn_startThreadPool = reinterpret_cast<ABinderProcess_startThreadPool_fn>(
                dlsym(libbinder_ndk, "ABinderProcess_startThreadPool"));
        fn_joinThreadPool = reinterpret_cast<ABinderProcess_joinThreadPool_fn>(
                dlsym(libbinder_ndk, "ABinderProcess_joinThreadPool"));

        if (!fn_setThreadPoolMaxThreadCount || !fn_startThreadPool) {
            FUTON_LOGW("Failed to resolve Binder thread pool functions");
            FUTON_LOGW("  setThreadPoolMaxThreadCount: %p", fn_setThreadPoolMaxThreadCount);
            FUTON_LOGW("  startThreadPool: %p", fn_startThreadPool);
            return false;
        }

        // Set thread pool max size (required before any Binder operations)
        // This enables linkToDeath and other callback mechanisms
        // Use 8 threads to handle concurrent requests from app (perception, input, etc.)
        bool result = fn_setThreadPoolMaxThreadCount(8);
        if (!result) {
            FUTON_LOGW("setThreadPoolMaxThreadCount returned false");
        }

        // Start the thread pool in a background thread
        s_binder_thread = std::thread([]() {
            FUTON_LOGI("Binder thread pool starting...");
            if (fn_startThreadPool) {
                fn_startThreadPool();
            }
        });

        // Give the thread pool a moment to initialize
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        FUTON_LOGI("Binder thread pool initialized with max 8 threads");
        s_binder_initialized = true;
        return true;
    }

    bool ProcessInit::init_binder() {
        return init_binder_thread_pool();
    }

    bool ProcessInit::initialize(const ProcessConfig &config) {
        FUTON_LOGI("Initializing process with priority=%d, lock_memory=%d",
                   config.sched_priority, config.lock_memory);

        // Initialize binder thread pool (required for SurfaceFlinger communication)
        if (!init_binder_thread_pool()) {
            FUTON_LOGW("Binder thread pool init failed, SurfaceControl may not work");
        }

        // Lock memory to prevent ZRAM swap
        if (config.lock_memory) {
            if (!lock_memory()) {
                FUTON_LOGW("Memory lock failed, continuing without memory lock");
            }
        }

        // Set real-time scheduling priority
        if (!set_realtime_priority(config.sched_priority)) {
            FUTON_LOGW("Real-time priority setup failed, using default scheduling");
        }

        // Write PID file
        if (config.pid_file != nullptr) {
            if (!write_pid_file(config.pid_file)) {
                FUTON_LOGE("Failed to write PID file: %s", config.pid_file);
                return false;
            }
            s_pid_file_path = config.pid_file;
        }

        FUTON_LOGI("Process initialization complete");
        return true;
    }

    void ProcessInit::cleanup() {
        FUTON_LOGI("Cleaning up process resources");

        if (s_pid_file_path != nullptr) {
            remove_pid_file(s_pid_file_path);
            s_pid_file_path = nullptr;
        }

        // The binder thread runs indefinitely, so we must detach it before exit
        // to avoid std::terminate() being called when the thread object is destroyed
        if (s_binder_thread.joinable()) {
            FUTON_LOGD("Detaching binder thread pool thread");
            s_binder_thread.detach();
        }
        s_binder_initialized = false;

        FUTON_LOGI("Process cleanup complete");
    }

    bool ProcessInit::lock_memory() {
        // Lock all current and future memory pages
        if (mlockall(MCL_CURRENT | MCL_FUTURE) == -1) {
            FUTON_LOGE_ERRNO("mlockall failed");
            return false;
        }
        FUTON_LOGI("Memory locked successfully");
        return true;
    }

    bool ProcessInit::set_realtime_priority(int priority) {
        // Clamp priority to valid range (1-99 for SCHED_FIFO)
        if (priority < 1) priority = 1;
        if (priority > 99) priority = 99;

        struct sched_param param;
        param.sched_priority = priority;

        // Try SCHED_FIFO first (highest priority real-time)
        if (sched_setscheduler(0, SCHED_FIFO, &param) == 0) {
            FUTON_LOGI("Set SCHED_FIFO with priority %d", priority);
            return true;
        }
        FUTON_LOGW("SCHED_FIFO failed: %s, trying SCHED_RR", strerror(errno));

        // Fallback to SCHED_RR (round-robin real-time)
        if (sched_setscheduler(0, SCHED_RR, &param) == 0) {
            FUTON_LOGI("Set SCHED_RR with priority %d", priority);
            return true;
        }
        FUTON_LOGW("SCHED_RR failed: %s, trying nice", strerror(errno));

        // Final fallback: use nice to set high priority
        if (setpriority(PRIO_PROCESS, 0, -20) == 0) {
            FUTON_LOGI("Set nice priority to -20");
            return true;
        }
        FUTON_LOGE_ERRNO("All priority methods failed");

        return false;
    }

    bool ProcessInit::write_pid_file(const char *path) {
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd < 0) {
            FUTON_LOGE_ERRNO("Failed to open PID file");
            return false;
        }

        char buf[32];
        int len = snprintf(buf, sizeof(buf), "%d\n", getpid());
        if (len < 0 || len >= static_cast<int>(sizeof(buf))) {
            close(fd);
            return false;
        }

        ssize_t written = write(fd, buf, len);
        close(fd);

        if (written != len) {
            FUTON_LOGE_ERRNO("Failed to write PID");
            return false;
        }

        FUTON_LOGI("PID file written: %s (pid=%d)", path, getpid());
        return true;
    }

    bool ProcessInit::remove_pid_file(const char *path) {
        if (unlink(path) == -1) {
            FUTON_LOGE_ERRNO("Failed to remove PID file");
            return false;
        }
        FUTON_LOGI("PID file removed: %s", path);
        return true;
    }

} // namespace futon::core
