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

#ifndef FUTON_VISION_CAPTURE_VIRTUAL_DISPLAY_H
#define FUTON_VISION_CAPTURE_VIRTUAL_DISPLAY_H

#include "vision/loader/surface_control_loader.h"
#include "vision/display/display_adapter.h"
#include "vision/buffer/hardware_buffer_wrapper.h"
#include <cstdint>
#include <memory>
#include <string>

namespace futon::vision {

/**
 * Virtual display flags.
 * These match Android's DisplayManager.VIRTUAL_DISPLAY_FLAG_* constants.
 */
    enum VirtualDisplayFlags : uint32_t {
        // Public display (visible to other apps)
        VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0,
        // Presentation display
        VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1,
        // Secure display (DRM content)
        VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2,
        // Display owns its content (prevents picture-in-picture recursion)
        VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3,
        // Auto-mirror the default display
        VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4,
    };

/**
 * Display information structure.
 */
    struct DisplayConfig {
        uint32_t width = 0;
        uint32_t height = 0;
        float density_dpi = 0.0f;
        float refresh_rate = 0.0f;
        int32_t orientation = 0;
    };

/**
 * VirtualDisplay - Unified wrapper for Android virtual display creation.
 *
 * Creates a virtual display that mirrors the main screen using:
 * - VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY (prevents recursion)
 * - VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR (auto-mirrors main display)
 *
 * Supports Android 11-16 via DisplayAdapter trampolines.
 */
    class VirtualDisplay {
    public:
        VirtualDisplay();

        ~VirtualDisplay();

        // Disable copy
        VirtualDisplay(const VirtualDisplay &) = delete;

        VirtualDisplay &operator=(const VirtualDisplay &) = delete;

        // Move semantics
        VirtualDisplay(VirtualDisplay &&other) noexcept;

        VirtualDisplay &operator=(VirtualDisplay &&other) noexcept;

        /**
         * Create a virtual display with specified dimensions.
         * Uses VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR.
         *
         * @param width Display width in pixels
         * @param height Display height in pixels
         * @param name Display name (for debugging)
         * @return true on success
         */
        bool create(uint32_t width, uint32_t height, const char *name = "FutonCapture");

        /**
         * Create a virtual display with custom flags.
         */
        bool
        create(uint32_t width, uint32_t height, uint32_t flags, const char *name = "FutonCapture");

        /**
         * Destroy the virtual display.
         */
        void destroy();

        /**
         * Check if display is created.
         */
        bool is_valid() const { return display_token_.is_valid(); }

        /**
         * Get display width.
         */
        uint32_t get_width() const { return width_; }

        /**
         * Get display height.
         */
        uint32_t get_height() const { return height_; }

        /**
         * Get the display token (IBinder).
         */
        void *get_display_token() const { return display_token_.ptr; }

        /**
         * Get the surface for rendering.
         */
        void *get_surface() const { return surface_; }

        /**
         * Get the buffer producer for frame capture.
         */
        void *get_buffer_producer() const { return buffer_producer_; }

        /**
         * Set the buffer producer for this virtual display.
         * This connects a BufferQueue producer to receive composited frames.
         *
         * @param producer IGraphicBufferProducer from BufferQueue
         * @param source_width Physical screen width
         * @param source_height Physical screen height
         * @return true on success
         */
        bool set_buffer_producer(void *producer, uint32_t source_width, uint32_t source_height);

        /**
         * Get physical display configuration.
         * @param config Output configuration
         * @return true on success
         */
        static bool get_physical_display_config(DisplayConfig *config);

        /**
         * Get the SurfaceControlLoader instance (legacy).
         */
        static SurfaceControlLoader &get_loader();

        /**
         * Get the DisplayAdapter instance.
         */
        static DisplayAdapter &get_adapter();

        explicit operator bool() const { return is_valid(); }

    private:
        DisplayToken display_token_;
        void *surface_ = nullptr;
        void *buffer_producer_ = nullptr;
        uint32_t width_ = 0;
        uint32_t height_ = 0;
        uint32_t flags_ = 0;
        std::string name_;

        static std::unique_ptr<SurfaceControlLoader> s_loader_;
        static std::unique_ptr<DisplayAdapter> s_adapter_;
        static bool s_initialized_;

        bool ensure_initialized();

        bool create_with_adapter(uint32_t width, uint32_t height, uint32_t flags, const char *name);

        bool create_with_loader(uint32_t width, uint32_t height, uint32_t flags, const char *name);

        // Legacy methods for fallback
        bool create_display_v16(uint32_t width, uint32_t height, uint32_t flags, const char *name);

        bool create_display_v14(uint32_t width, uint32_t height, uint32_t flags, const char *name);

        bool create_display_v11(uint32_t width, uint32_t height, uint32_t flags, const char *name);

        void *get_physical_display_token();
    };

} // namespace futon::vision

#endif // FUTON_VISION_CAPTURE_VIRTUAL_DISPLAY_H
