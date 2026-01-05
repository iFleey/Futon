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

#ifndef FUTON_VISION_CAPTURE_SURFACE_CONTROL_CAPTURE_H
#define FUTON_VISION_CAPTURE_SURFACE_CONTROL_CAPTURE_H

#include "vision/capture/virtual_display.h"
#include "vision/display/display_adapter.h"
#include "vision/buffer/hardware_buffer_wrapper.h"
#include "core/error.h"

#include <android/hardware_buffer.h>
#include <memory>

namespace futon::vision {

/**
 * Capture result containing buffer and fence.
 */
    struct CaptureResult {
        AHardwareBuffer *buffer = nullptr;
        int fence_fd = -1;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t format = 0;
        int64_t timestamp_ns = 0;
    };

/**
 * SurfaceControlCapture - Zero-copy screen capture via Private API.
 *
 * Uses SurfaceControl to create a virtual display that mirrors the
 * physical display, capturing frames into AHardwareBuffer without
 * CPU memcpy operations.
 */
    class SurfaceControlCapture {
    public:
        SurfaceControlCapture();

        ~SurfaceControlCapture();

        // Disable copy
        SurfaceControlCapture(const SurfaceControlCapture &) = delete;

        SurfaceControlCapture &operator=(const SurfaceControlCapture &) = delete;

        /**
         * Initialize capture with specified dimensions.
         * @param width Capture width (0 = physical display width)
         * @param height Capture height (0 = physical display height)
         * @return true on success
         */
        bool initialize(uint32_t width = 0, uint32_t height = 0);

        /**
         * Shutdown capture and release resources.
         */
        void shutdown();

        /**
         * Check if capture is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Capture a frame into AHardwareBuffer.
         * @param out_buffer Output buffer pointer
         * @param out_fence_fd Output fence fd for synchronization
         * @return Result with error code
         */
        core::Result <CaptureResult> capture();

        /**
         * Get capture width.
         */
        uint32_t get_width() const { return width_; }

        /**
         * Get capture height.
         */
        uint32_t get_height() const { return height_; }

        /**
         * Get buffer format (always RGBA_8888).
         */
        uint32_t get_format() const { return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM; }

        /**
         * Check if Private API is available.
         */
        static bool is_available();

    private:
        bool initialized_ = false;
        uint32_t width_ = 0;
        uint32_t height_ = 0;
        uint32_t physical_width_ = 0;
        uint32_t physical_height_ = 0;

        VirtualDisplay virtual_display_;
        HardwareBufferWrapper capture_buffer_;

        // Frame counter for debugging
        uint64_t frame_count_ = 0;

        bool setup_virtual_display();

        bool setup_capture_buffer();

        bool setup_display_projection();

        bool acquire_frame(CaptureResult *result);
    };

} // namespace futon::vision

#endif // FUTON_VISION_CAPTURE_SURFACE_CONTROL_CAPTURE_H
