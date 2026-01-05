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

#include "fence_sync.h"
#include "core/error.h"

#include <android/sync.h>
#include <GLES3/gl3.h>
#include <unistd.h>
#include <poll.h>
#include <errno.h>
#include <cstring>

namespace futon::inference {

    FenceSync::FenceSync() = default;

    FenceSync::~FenceSync() {
        shutdown();
    }

    FenceSync::FenceSync(FenceSync &&other) noexcept
            : display_(other.display_), initialized_(other.initialized_),
              eglCreateSyncKHR_(other.eglCreateSyncKHR_),
              eglDestroySyncKHR_(other.eglDestroySyncKHR_),
              eglClientWaitSyncKHR_(other.eglClientWaitSyncKHR_),
              eglDupNativeFenceFDANDROID_(other.eglDupNativeFenceFDANDROID_) {
        other.display_ = EGL_NO_DISPLAY;
        other.initialized_ = false;
        other.eglCreateSyncKHR_ = nullptr;
        other.eglDestroySyncKHR_ = nullptr;
        other.eglClientWaitSyncKHR_ = nullptr;
        other.eglDupNativeFenceFDANDROID_ = nullptr;
    }

    FenceSync &FenceSync::operator=(FenceSync &&other) noexcept {
        if (this != &other) {
            shutdown();
            display_ = other.display_;
            initialized_ = other.initialized_;
            eglCreateSyncKHR_ = other.eglCreateSyncKHR_;
            eglDestroySyncKHR_ = other.eglDestroySyncKHR_;
            eglClientWaitSyncKHR_ = other.eglClientWaitSyncKHR_;
            eglDupNativeFenceFDANDROID_ = other.eglDupNativeFenceFDANDROID_;

            other.display_ = EGL_NO_DISPLAY;
            other.initialized_ = false;
            other.eglCreateSyncKHR_ = nullptr;
            other.eglDestroySyncKHR_ = nullptr;
            other.eglClientWaitSyncKHR_ = nullptr;
            other.eglDupNativeFenceFDANDROID_ = nullptr;
        }
        return *this;
    }

    bool FenceSync::initialize(EGLDisplay egl_display) {
        if (initialized_) {
            FUTON_LOGW("FenceSync already initialized");
            return true;
        }

        if (egl_display == EGL_NO_DISPLAY) {
            FUTON_LOGE("FenceSync::initialize: Invalid EGL display");
            return false;
        }

        display_ = egl_display;

        if (!load_egl_extensions()) {
            FUTON_LOGE("FenceSync::initialize: Failed to load EGL extensions");
            display_ = EGL_NO_DISPLAY;
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("FenceSync initialized successfully");
        return true;
    }

    void FenceSync::shutdown() {
        if (!initialized_) {
            return;
        }

        display_ = EGL_NO_DISPLAY;
        eglCreateSyncKHR_ = nullptr;
        eglDestroySyncKHR_ = nullptr;
        eglClientWaitSyncKHR_ = nullptr;
        eglDupNativeFenceFDANDROID_ = nullptr;
        initialized_ = false;

        FUTON_LOGI("FenceSync shutdown complete");
    }

    bool FenceSync::is_initialized() const {
        return initialized_;
    }

    bool FenceSync::load_egl_extensions() {
        // Check for required extensions
        const char *extensions = eglQueryString(display_, EGL_EXTENSIONS);
        if (!extensions) {
            FUTON_LOGE("Failed to query EGL extensions");
            return false;
        }

        // Check for EGL_KHR_fence_sync
        if (strstr(extensions, "EGL_KHR_fence_sync") == nullptr) {
            FUTON_LOGW("EGL_KHR_fence_sync not supported");
        }

        // Check for EGL_ANDROID_native_fence_sync
        if (strstr(extensions, "EGL_ANDROID_native_fence_sync") == nullptr) {
            FUTON_LOGW("EGL_ANDROID_native_fence_sync not supported");
        }

        // Load function pointers
        eglCreateSyncKHR_ = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
                eglGetProcAddress("eglCreateSyncKHR"));
        eglDestroySyncKHR_ = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
                eglGetProcAddress("eglDestroySyncKHR"));
        eglClientWaitSyncKHR_ = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
                eglGetProcAddress("eglClientWaitSyncKHR"));
        eglDupNativeFenceFDANDROID_ = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
                eglGetProcAddress("eglDupNativeFenceFDANDROID"));

        // eglClientWaitSyncKHR is required for fence waiting
        if (!eglClientWaitSyncKHR_) {
            FUTON_LOGE("eglClientWaitSyncKHR not available");
            return false;
        }

        FUTON_LOGD("EGL fence extensions loaded: CreateSync=%p, DestroySync=%p, "
                   "ClientWaitSync=%p, DupNativeFenceFD=%p",
                   eglCreateSyncKHR_, eglDestroySyncKHR_,
                   eglClientWaitSyncKHR_, eglDupNativeFenceFDANDROID_);

        return true;
    }

    bool FenceSync::sync_wait_native(int fence_fd, int timeout_ms) {
        if (fence_fd < 0) {
            return true;  // No fence to wait for
        }

        // Use poll() for fence waiting (more portable than sync_wait)
        struct pollfd pfd;
        pfd.fd = fence_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;

        int ret = poll(&pfd, 1, timeout_ms);
        if (ret < 0) {
            FUTON_LOGE("sync_wait_native: poll failed: %s", strerror(errno));
            return false;
        } else if (ret == 0) {
            FUTON_LOGW("sync_wait_native: timeout after %dms", timeout_ms);
            return false;
        }

        // Check for errors
        if (pfd.revents & (POLLERR | POLLNVAL)) {
            FUTON_LOGE("sync_wait_native: poll error (revents=0x%x)", pfd.revents);
            return false;
        }

        return true;
    }

    bool FenceSync::wait_for_gpu(int fence_fd, int timeout_ms) {
        if (!initialized_) {
            FUTON_LOGE("FenceSync::wait_for_gpu: Not initialized");
            return false;
        }

        // If no fence provided, nothing to wait for
        if (fence_fd < 0) {
            FUTON_LOGD("FenceSync::wait_for_gpu: No fence to wait for");
            return true;
        }

        // Try native sync_wait first (more efficient)
        if (sync_wait_native(fence_fd, timeout_ms)) {
            FUTON_LOGD("FenceSync::wait_for_gpu: Native sync completed");
            return true;
        }

        // If native wait failed due to timeout, return false
        FUTON_LOGW("FenceSync::wait_for_gpu: Fence wait timeout (%dms)", timeout_ms);
        return false;
    }

    int FenceSync::create_fence() {
        if (!initialized_) {
            FUTON_LOGE("FenceSync::create_fence: Not initialized");
            return -1;
        }

        if (!eglCreateSyncKHR_ || !eglDupNativeFenceFDANDROID_) {
            FUTON_LOGE("FenceSync::create_fence: Required EGL functions not available");
            return -1;
        }

        // Create EGL sync object
        EGLint attribs[] = {EGL_NONE};
        EGLSyncKHR sync = eglCreateSyncKHR_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
        if (sync == EGL_NO_SYNC_KHR) {
            FUTON_LOGE("FenceSync::create_fence: eglCreateSyncKHR failed (error=0x%x)",
                       eglGetError());
            return -1;
        }

        // Flush to ensure sync is created
        glFlush();

        // Export as native fence FD
        int fence_fd = eglDupNativeFenceFDANDROID_(display_, sync);

        // Destroy the EGL sync object (fence FD is independent)
        if (eglDestroySyncKHR_) {
            eglDestroySyncKHR_(display_, sync);
        }

        if (fence_fd < 0) {
            FUTON_LOGE("FenceSync::create_fence: eglDupNativeFenceFDANDROID failed");
            return -1;
        }

        FUTON_LOGD("FenceSync::create_fence: Created fence fd=%d", fence_fd);
        return fence_fd;
    }

} // namespace futon::inference
