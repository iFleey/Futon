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

#ifndef FUTON_INFERENCE_FENCE_SYNC_H
#define FUTON_INFERENCE_FENCE_SYNC_H

#include <EGL/egl.h>
#include <EGL/eglext.h>

namespace futon::inference {

/**
 * FenceSync - GPU/DSP synchronization primitive
 *
 * Provides fence-based synchronization for zero-copy data transfer
 * between GPU and DSP. Uses EGL sync objects for GPU fence operations
 * and Android sync_wait for native fence FDs.
 *
 * Key operations:
 * - wait_for_gpu(): Wait for GPU write completion before DSP read
 * - create_fence(): Create output fence after processing
 */
    class FenceSync {
    public:
        FenceSync();

        ~FenceSync();

        // Disable copy
        FenceSync(const FenceSync &) = delete;

        FenceSync &operator=(const FenceSync &) = delete;

        // Move operations
        FenceSync(FenceSync &&other) noexcept;

        FenceSync &operator=(FenceSync &&other) noexcept;

        /**
         * Initialize with EGL display
         * @param egl_display EGL display handle (EGLDisplay)
         * @return true on success
         */
        bool initialize(EGLDisplay egl_display);

        /**
         * Shutdown and release resources
         */
        void shutdown();

        /**
         * Check if initialized
         */
        bool is_initialized() const;

        /**
         * Wait for GPU write to complete before DSP read
         *
         * Uses eglClientWaitSyncKHR for EGL sync objects or
         * sync_wait() for native Android fence FDs.
         *
         * @param fence_fd Native fence file descriptor (-1 if no fence)
         * @param timeout_ms Timeout in milliseconds (default 100ms)
         * @return true if fence signaled, false on timeout or error
         */
        bool wait_for_gpu(int fence_fd, int timeout_ms = 100);

        /**
         * Create output fence after processing
         *
         * Creates an EGL_SYNC_NATIVE_FENCE_ANDROID sync object and
         * exports it as a native fence FD.
         *
         * @return Native fence FD, or -1 on error
         */
        int create_fence();

    private:
        EGLDisplay display_ = EGL_NO_DISPLAY;
        bool initialized_ = false;

        // EGL extension function pointers
        PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR_ = nullptr;
        PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR_ = nullptr;
        PFNEGLCLIENTWAITSYNCKHRPROC eglClientWaitSyncKHR_ = nullptr;
        PFNEGLDUPNATIVEFENCEFDANDROIDPROC eglDupNativeFenceFDANDROID_ = nullptr;

        /**
         * Load EGL extension function pointers
         */
        bool load_egl_extensions();

        /**
         * Wait using native sync_wait syscall
         */
        bool sync_wait_native(int fence_fd, int timeout_ms);
    };

} // namespace futon::inference

#endif // FUTON_INFERENCE_FENCE_SYNC_H
