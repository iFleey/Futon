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

#ifndef FUTON_VISION_DISPLAY_DISPLAY_TRANSACTION_H
#define FUTON_VISION_DISPLAY_DISPLAY_TRANSACTION_H

#include "vision/display/display_adapter.h"
#include <cstdint>
#include <string>

namespace futon::vision {

/**
 * Rectangle structure for display regions.
 */
    struct Rect {
        int32_t left = 0;
        int32_t top = 0;
        int32_t right = 0;
        int32_t bottom = 0;

        Rect() = default;

        Rect(int32_t l, int32_t t, int32_t r, int32_t b)
                : left(l), top(t), right(r), bottom(b) {}

        int32_t width() const { return right - left; }

        int32_t height() const { return bottom - top; }

        bool is_empty() const { return width() <= 0 || height() <= 0; }
    };

/**
 * Display orientation constants.
 */
    enum class DisplayOrientation : int32_t {
        ROTATION_0 = 0,
        ROTATION_90 = 1,
        ROTATION_180 = 2,
        ROTATION_270 = 3,
    };

/**
 * Display projection configuration.
 */
    struct DisplayProjection {
        Rect source_rect;      // Physical screen region to capture
        Rect dest_rect;        // Target buffer region
        DisplayOrientation orientation = DisplayOrientation::ROTATION_0;
    };

/**
 * DisplayTransaction - Wrapper for SurfaceComposerClient::Transaction.
 *
 * Configures virtual display surface and projection:
 * - setDisplaySurface: Connects display to a Surface/BufferQueue
 * - setDisplayProjection: Maps source region to destination region
 *
 * Supports Android 11-16 via dynamic symbol resolution.
 */
    class DisplayTransaction {
    public:
        DisplayTransaction();

        ~DisplayTransaction();

        /**
         * Initialize the transaction system.
         * @return true if initialization successful
         */
        bool initialize();

        /**
         * Check if transaction system is available.
         */
        bool is_available() const { return initialized_; }

        /**
         * Set the display surface (connects display to BufferQueue).
         * @param display_token Display token from VirtualDisplay
         * @param surface Surface to render to (from BufferQueue producer)
         * @return true on success
         */
        bool set_display_surface(const DisplayToken &display_token, void *surface);

        /**
         * Set the display projection (source to destination mapping).
         * @param display_token Display token from VirtualDisplay
         * @param projection Projection configuration
         * @return true on success
         */
        bool set_display_projection(const DisplayToken &display_token,
                                    const DisplayProjection &projection);

        /**
         * Configure display with surface and projection in one call.
         * @param display_token Display token
         * @param surface Surface to render to
         * @param source_width Physical screen width
         * @param source_height Physical screen height
         * @param dest_width Capture buffer width
         * @param dest_height Capture buffer height
         * @return true on success
         */
        bool configure_display(const DisplayToken &display_token,
                               void *surface,
                               uint32_t source_width, uint32_t source_height,
                               uint32_t dest_width, uint32_t dest_height);

        /**
         * Apply all pending transaction changes.
         * @return true on success
         */
        bool apply();

    private:
        bool initialized_ = false;
        void *libgui_handle_ = nullptr;
        void *transaction_obj_ = nullptr;

        // Function pointers for Transaction API
        void *transaction_ctor_ = nullptr;
        void *transaction_dtor_ = nullptr;
        void *set_display_surface_fn_ = nullptr;
        void *set_display_projection_fn_ = nullptr;
        void *apply_fn_ = nullptr;

        bool resolve_transaction_symbols();

        bool create_transaction();

        void destroy_transaction();
    };

} // namespace futon::vision

#endif // FUTON_VISION_DISPLAY_DISPLAY_TRANSACTION_H
