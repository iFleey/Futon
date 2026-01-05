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

#include "vision/capture/surface_control_capture.h"
#include "vision/display/display_transaction.h"
#include "core/error.h"

#include <android/hardware_buffer.h>
#include <time.h>

using namespace futon::core;

namespace futon::vision {

    SurfaceControlCapture::SurfaceControlCapture() = default;

    SurfaceControlCapture::~SurfaceControlCapture() {
        shutdown();
    }

    bool SurfaceControlCapture::is_available() {
        // Try DisplayAdapter first (new ELF scanner approach)
        DisplayAdapter &adapter = VirtualDisplay::get_adapter();
        if (adapter.initialize_auto()) {
            return true;
        }

        // Fall back to legacy loader
        SurfaceControlLoader &loader = VirtualDisplay::get_loader();
        if (!loader.is_loaded()) {
            if (!loader.load()) {
                return false;
            }
        }
        return loader.is_loaded();
    }

    bool SurfaceControlCapture::initialize(uint32_t width, uint32_t height) {
        if (initialized_) {
            FUTON_LOGW("SurfaceControlCapture: already initialized");
            return true;
        }

        // Get physical display dimensions if not specified
        uint32_t physical_width = 0;
        uint32_t physical_height = 0;

        DisplayConfig config;
        if (VirtualDisplay::get_physical_display_config(&config)) {
            physical_width = config.width;
            physical_height = config.height;
        } else {
            // Default fallback
            physical_width = 1080;
            physical_height = 2400;
        }

        if (width == 0 || height == 0) {
            width = physical_width;
            height = physical_height;
        }

        width_ = width;
        height_ = height;
        physical_width_ = physical_width;
        physical_height_ = physical_height;

        FUTON_LOGI("SurfaceControlCapture: initializing capture=%ux%u physical=%ux%u",
                   width_, height_, physical_width_, physical_height_);

        // Setup virtual display with mirroring flags
        if (!setup_virtual_display()) {
            FUTON_LOGE("SurfaceControlCapture: failed to setup virtual display");
            return false;
        }

        // Setup capture buffer
        if (!setup_capture_buffer()) {
            FUTON_LOGE("SurfaceControlCapture: failed to setup capture buffer");
            virtual_display_.destroy();
            return false;
        }

        // Configure display projection (source -> destination mapping)
        if (!setup_display_projection()) {
            FUTON_LOGW("SurfaceControlCapture: projection setup failed (continuing)");
            // Non-fatal: AUTO_MIRROR flag should handle basic mirroring
        }

        initialized_ = true;
        FUTON_LOGI("SurfaceControlCapture: initialized successfully");
        return true;
    }

    void SurfaceControlCapture::shutdown() {
        if (!initialized_) {
            return;
        }

        FUTON_LOGI("SurfaceControlCapture: shutting down (captured %lu frames)",
                   static_cast<unsigned long>(frame_count_));

        capture_buffer_.release();
        virtual_display_.destroy();

        initialized_ = false;
        width_ = 0;
        height_ = 0;
        physical_width_ = 0;
        physical_height_ = 0;
        frame_count_ = 0;
    }

    bool SurfaceControlCapture::setup_virtual_display() {
        // Create virtual display with required flags:
        // - OWN_CONTENT_ONLY: Prevents picture-in-picture recursion
        // - AUTO_MIRROR: Automatically mirrors the main display
        uint32_t flags = VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                         VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

        if (!virtual_display_.create(width_, height_, flags, "FutonCapture")) {
            FUTON_LOGE("setup_virtual_display: failed to create virtual display");
            return false;
        }

        FUTON_LOGI("Virtual display created: %ux%u flags=0x%x", width_, height_, flags);
        FUTON_LOGI("  OWN_CONTENT_ONLY: prevents recursion");
        FUTON_LOGI("  AUTO_MIRROR: mirrors main display");
        return true;
    }

    bool SurfaceControlCapture::setup_capture_buffer() {
        // Allocate AHardwareBuffer for capture
        // Format: RGBA_8888 (required for GPU processing)
        // Usage flags for zero-copy pipeline:
        // - GPU_SAMPLED_IMAGE: Can be sampled by GPU shaders
        // - CPU_READ_OFTEN: Can be read by CPU (for debugging)
        // - GPU_COLOR_OUTPUT: Can be written by GPU
        // - GPU_FRAMEBUFFER: Can be used as framebuffer attachment
        uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                         AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                         AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                         AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;

        if (!capture_buffer_.allocate(width_, height_,
                                      AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                                      usage)) {
            FUTON_LOGE("setup_capture_buffer: failed to allocate buffer");
            return false;
        }

        FUTON_LOGD("Capture buffer allocated: %ux%u RGBA_8888", width_, height_);
        return true;
    }

    bool SurfaceControlCapture::setup_display_projection() {
        // Configure display projection using Transaction API
        // This maps the physical screen region to the capture buffer

        DisplayTransaction transaction;
        if (!transaction.initialize()) {
            FUTON_LOGW("setup_display_projection: Transaction API not available");
            return false;
        }

        // Get display token from virtual display
        DisplayToken token;
        token.ptr = virtual_display_.get_display_token();

        if (!token.is_valid()) {
            FUTON_LOGW("setup_display_projection: invalid display token");
            return false;
        }

        // Configure projection:
        // Source = physical screen dimensions
        // Destination = capture buffer dimensions
        bool success = transaction.configure_display(
                token,
                virtual_display_.get_surface(),  // May be null if not using BufferQueue
                physical_width_, physical_height_,
                width_, height_
        );

        if (success) {
            FUTON_LOGI("Display projection configured: %ux%u -> %ux%u",
                       physical_width_, physical_height_, width_, height_);
        }

        return success;
    }

    Result <CaptureResult> SurfaceControlCapture::capture() {
        if (!initialized_) {
            FUTON_LOGE("capture: not initialized");
            return Result<CaptureResult>::error(FutonError::NotInitialized);
        }

        CaptureResult result;

        if (!acquire_frame(&result)) {
            FUTON_LOGE("capture: failed to acquire frame");
            return Result<CaptureResult>::error(FutonError::InternalError);
        }

        frame_count_++;
        return Result<CaptureResult>::ok(result);
    }

    bool SurfaceControlCapture::acquire_frame(CaptureResult *result) {
        if (!result) {
            return false;
        }

        // Get current timestamp
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        result->timestamp_ns = ts.tv_sec * 1000000000LL + ts.tv_nsec;

        // In a full implementation, this would:
        // 1. Dequeue buffer from BufferQueue
        // 2. Wait for GPU composition to complete (fence)
        // 3. Return buffer with fence for downstream sync
        //
        // For now, we return the pre-allocated buffer
        // The actual frame acquisition requires deeper integration
        // with Android's BufferQueue system

        result->buffer = capture_buffer_.get();
        result->fence_fd = -1;  // No fence in simplified implementation
        result->width = width_;
        result->height = height_;
        result->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;

        // Note: In production, this would use:
        // - ANativeWindow_dequeueBuffer() for buffer acquisition
        // - Fence from dequeue for GPU sync
        // - ANativeWindow_queueBuffer() after processing

        return result->buffer != nullptr;
    }

} // namespace futon::vision
