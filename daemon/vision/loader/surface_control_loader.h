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

#ifndef FUTON_VISION_LOADER_SURFACE_CONTROL_LOADER_H
#define FUTON_VISION_LOADER_SURFACE_CONTROL_LOADER_H

#include "vision/loader/symbol_resolver.h"
#include <cstdint>
#include <string>

namespace futon::vision {

/**
 * Function pointer types for SurfaceComposerClient APIs.
 * These match the C++ mangled signatures from libgui.so.
 */

// Opaque types for Android internal classes
    struct IBinder;
    struct String8;
    struct DisplayInfo;
    struct DisplayMode;
    struct Surface;
    struct IGraphicBufferProducer;

// sp<T> smart pointer (simplified for function signatures)
    template<typename T>
    struct sp {
        T *ptr;
    };

// DisplayId types
    using DisplayId = uint64_t;
    using PhysicalDisplayId = uint64_t;

// ISurfaceComposer::OptimizationPolicy enum (Android 16+)
    enum class OptimizationPolicy : int32_t {
        NONE = 0,
        GAME = 1,
    };

/**
 * Function pointer types for resolved symbols.
 */

// createDisplay / createVirtualDisplay: Creates a virtual display
// Android 16+: sp<IBinder> createVirtualDisplay(const std::string& name, bool secure, bool receiveFrameUsedExclusively, const std::string& uniqueId, float requestedRefreshRate)
// Android 14-15: sp<IBinder> createDisplay(const String8& name, bool secure, DisplayId)
// Android 11-13: sp<IBinder> createDisplay(const String8& name, bool secure)
//
// Note: sp<IBinder> is returned by value. On ARM64 ABI, non-trivial return types
// are returned via a hidden first parameter (sret). The caller passes a pointer
// to uninitialized memory where the result will be constructed.
    using CreateVirtualDisplayFn_v16 = void (*)(sp<IBinder> *result, const std::string &name,
                                                bool secure, bool receiveFrameUsedExclusively,
                                                const std::string &uniqueId,
                                                float requestedRefreshRate);
    using CreateDisplayFn_v14 = void (*)(sp<IBinder> *result, const String8 &name, bool secure,
                                         DisplayId displayId);
    using CreateDisplayFn_v11 = void (*)(sp<IBinder> *result, const String8 &name, bool secure);

// destroyDisplay / destroyVirtualDisplay: Destroys a virtual display
    using DestroyVirtualDisplayFn = void (*)(const sp<IBinder> &display);
    using DestroyDisplayFn = void (*)(const sp<IBinder> &display);

// mirrorDisplay: Mirror a display (Android 16+)
    using MirrorDisplayFn = sp<IBinder>(*)(DisplayId displayId);

// getPhysicalDisplayToken: Gets token for physical display
// Android 14-15: sp<IBinder> getPhysicalDisplayToken(PhysicalDisplayId)
// Android 12-13: sp<IBinder> getPhysicalDisplayToken(DisplayId)
// Android 11: sp<IBinder> getPhysicalDisplayToken(uint64_t)
    using GetPhysicalDisplayTokenFn_v14 = sp<IBinder>(*)(PhysicalDisplayId id);
    using GetPhysicalDisplayTokenFn_v12 = sp<IBinder>(*)(DisplayId id);
    using GetPhysicalDisplayTokenFn_v11 = sp<IBinder>(*)(uint64_t id);
    using GetInternalDisplayTokenFn = sp<IBinder>(*)();
    using GetBuiltInDisplayFn = sp<IBinder>(*)(int32_t id);

// getDisplayInfo: Gets display information
    using GetDisplayInfoFn = int (*)(const sp<IBinder> &display, DisplayInfo *info);

// getActiveDisplayMode: Gets active display mode
    using GetActiveDisplayModeFn = int (*)(const sp<IBinder> &display, DisplayMode *mode);
    using GetActiveConfigFn = int (*)(const sp<IBinder> &display);

/**
 * Loaded symbols container.
 */
    struct SurfaceControlSymbols {
        // Library handles
        void *libgui_handle = nullptr;
        void *libui_handle = nullptr;

        // createDisplay / createVirtualDisplay variants
        union {
            void *create_display_raw = nullptr;
            CreateVirtualDisplayFn_v16 create_virtual_display_v16;
            CreateDisplayFn_v14 create_display_v14;
            CreateDisplayFn_v11 create_display_v11;
        };
        int create_display_api_level = 0;
        bool use_virtual_display_api = false;  // Android 16+ uses createVirtualDisplay

        // destroyDisplay / destroyVirtualDisplay
        union {
            DestroyDisplayFn destroy_display;
            DestroyVirtualDisplayFn destroy_virtual_display;
        };

        // mirrorDisplay (Android 16+)
        MirrorDisplayFn mirror_display = nullptr;

        // getPhysicalDisplayToken variants
        union {
            void *get_physical_display_token_raw = nullptr;
            GetPhysicalDisplayTokenFn_v14 get_physical_display_token_v14;
            GetPhysicalDisplayTokenFn_v12 get_physical_display_token_v12;
            GetPhysicalDisplayTokenFn_v11 get_physical_display_token_v11;
            GetInternalDisplayTokenFn get_internal_display_token;
            GetBuiltInDisplayFn get_built_in_display;
        };
        int get_display_token_api_level = 0;
        bool use_internal_display_token = false;
        bool use_built_in_display = false;

        // getDisplayInfo
        GetDisplayInfoFn get_display_info = nullptr;

        // getActiveDisplayMode / getActiveConfig
        union {
            void *get_active_mode_raw = nullptr;
            GetActiveDisplayModeFn get_active_display_mode;
            GetActiveConfigFn get_active_config;
        };
        bool use_active_config = false;

        bool is_loaded() const {
            return libgui_handle != nullptr &&
                   create_display_raw != nullptr &&
                   get_physical_display_token_raw != nullptr;
        }
    };

/**
 * SurfaceControlLoader - Dynamic loader for SurfaceComposerClient APIs.
 *
 * Uses dlopen/dlsym to load libgui.so and libui.so at runtime.
 * Resolves symbols using SymbolResolver for Android 11-16 compatibility.
 */
    class SurfaceControlLoader {
    public:
        SurfaceControlLoader();

        ~SurfaceControlLoader();

        // Disable copy
        SurfaceControlLoader(const SurfaceControlLoader &) = delete;

        SurfaceControlLoader &operator=(const SurfaceControlLoader &) = delete;

        /**
         * Load libraries and resolve symbols.
         * @return true if all required symbols resolved
         */
        bool load();

        /**
         * Unload libraries.
         */
        void unload();

        /**
         * Check if symbols are loaded.
         */
        bool is_loaded() const { return symbols_.is_loaded(); }

        /**
         * Get loaded symbols.
         */
        const SurfaceControlSymbols &symbols() const { return symbols_; }

        /**
         * Get symbol resolver.
         */
        const SymbolResolver &resolver() const { return resolver_; }

    private:
        SymbolResolver resolver_;
        SurfaceControlSymbols symbols_;

        bool load_libgui();

        bool load_libui();

        bool resolve_create_display();

        bool resolve_destroy_display();

        bool resolve_get_display_token();

        bool resolve_get_display_info();

        bool resolve_get_active_mode();
    };

} // namespace futon::vision

#endif // FUTON_VISION_LOADER_SURFACE_CONTROL_LOADER_H
