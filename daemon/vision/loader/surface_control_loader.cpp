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

#include "vision/loader/surface_control_loader.h"
#include "core/error.h"

#include <dlfcn.h>

using namespace futon::core;

namespace futon::vision {

// Library paths to try
    static const char *kLibGuiPaths[] = {
            "libgui.so",
            "/system/lib64/libgui.so",
            "/system/lib/libgui.so",
            "/vendor/lib64/libgui.so",
            "/vendor/lib/libgui.so",
    };

    static const char *kLibUiPaths[] = {
            "libui.so",
            "/system/lib64/libui.so",
            "/system/lib/libui.so",
            "/vendor/lib64/libui.so",
            "/vendor/lib/libui.so",
    };

    SurfaceControlLoader::SurfaceControlLoader() = default;

    SurfaceControlLoader::~SurfaceControlLoader() {
        unload();
    }

    bool SurfaceControlLoader::load() {
        if (symbols_.is_loaded()) {
            FUTON_LOGW("SurfaceControlLoader: already loaded");
            return true;
        }

        // Initialize symbol resolver
        if (!resolver_.initialize()) {
            FUTON_LOGE("SurfaceControlLoader: failed to initialize symbol resolver");
            return false;
        }

        // Load libraries
        if (!load_libgui()) {
            FUTON_LOGE("SurfaceControlLoader: failed to load libgui.so");
            return false;
        }

        if (!load_libui()) {
            FUTON_LOGW("SurfaceControlLoader: failed to load libui.so (optional)");
            // libui is optional, continue
        }

        // Resolve required symbols
        if (!resolve_create_display()) {
            FUTON_LOGE("SurfaceControlLoader: failed to resolve createDisplay");
            unload();
            return false;
        }

        if (!resolve_destroy_display()) {
            FUTON_LOGW("SurfaceControlLoader: failed to resolve destroyDisplay (optional)");
            // destroyDisplay is optional
        }

        if (!resolve_get_display_token()) {
            FUTON_LOGE("SurfaceControlLoader: failed to resolve getPhysicalDisplayToken");
            unload();
            return false;
        }

        if (!resolve_get_display_info()) {
            FUTON_LOGW("SurfaceControlLoader: failed to resolve getDisplayInfo (optional)");
            // getDisplayInfo is optional
        }

        if (!resolve_get_active_mode()) {
            FUTON_LOGW("SurfaceControlLoader: failed to resolve getActiveDisplayMode (optional)");
            // getActiveDisplayMode is optional
        }

        FUTON_LOGI("SurfaceControlLoader: all required symbols loaded successfully");
        return true;
    }

    void SurfaceControlLoader::unload() {
        if (symbols_.libgui_handle) {
            dlclose(symbols_.libgui_handle);
        }
        if (symbols_.libui_handle) {
            dlclose(symbols_.libui_handle);
        }
        symbols_ = SurfaceControlSymbols{};
        FUTON_LOGD("SurfaceControlLoader: unloaded");
    }

    bool SurfaceControlLoader::load_libgui() {
        // Clear previous errors
        dlerror();

        for (const char *path: kLibGuiPaths) {
            symbols_.libgui_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
            if (symbols_.libgui_handle) {
                FUTON_LOGI("Loaded libgui.so from: %s", path);
                return true;
            }
            FUTON_LOGD("Failed to load %s: %s", path, dlerror());
        }

        FUTON_LOGE("Failed to load libgui.so from any path");
        return false;
    }

    bool SurfaceControlLoader::load_libui() {
        dlerror();

        for (const char *path: kLibUiPaths) {
            symbols_.libui_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
            if (symbols_.libui_handle) {
                FUTON_LOGI("Loaded libui.so from: %s", path);
                return true;
            }
            FUTON_LOGD("Failed to load %s: %s", path, dlerror());
        }

        FUTON_LOGW("Failed to load libui.so from any path");
        return false;
    }

    bool SurfaceControlLoader::resolve_create_display() {
        ResolvedSymbol result = resolver_.resolve_symbol(
                symbols_.libgui_handle,
                SymbolResolver::kCreateDisplayVariants,
                SymbolResolver::kCreateDisplayVariantCount
        );

        if (!result.success) {
            resolver_.log_resolution_attempts(
                    "createDisplay/createVirtualDisplay",
                    SymbolResolver::kCreateDisplayVariants,
                    SymbolResolver::kCreateDisplayVariantCount,
                    result
            );
            return false;
        }

        symbols_.create_display_raw = result.address;
        symbols_.create_display_api_level = result.api_level;

        // Check if we're using Android 16+ createVirtualDisplay API
        if (result.symbol_name && strstr(result.symbol_name, "createVirtualDisplay")) {
            symbols_.use_virtual_display_api = true;
            FUTON_LOGI("Resolved createVirtualDisplay (Android 16+): API level %d",
                       result.api_level);
        } else {
            symbols_.use_virtual_display_api = false;
            FUTON_LOGI("Resolved createDisplay: API level %d", result.api_level);
        }
        return true;
    }

    bool SurfaceControlLoader::resolve_destroy_display() {
        ResolvedSymbol result = resolver_.resolve_symbol(
                symbols_.libgui_handle,
                SymbolResolver::kDestroyDisplayVariants,
                SymbolResolver::kDestroyDisplayVariantCount
        );

        if (!result.success) {
            return false;
        }

        symbols_.destroy_display = reinterpret_cast<DestroyDisplayFn>(result.address);
        FUTON_LOGI("Resolved destroyDisplay");
        return true;
    }

    bool SurfaceControlLoader::resolve_get_display_token() {
        ResolvedSymbol result = resolver_.resolve_symbol(
                symbols_.libgui_handle,
                SymbolResolver::kGetPhysicalDisplayTokenVariants,
                SymbolResolver::kGetPhysicalDisplayTokenVariantCount
        );

        if (!result.success) {
            resolver_.log_resolution_attempts(
                    "getPhysicalDisplayToken",
                    SymbolResolver::kGetPhysicalDisplayTokenVariants,
                    SymbolResolver::kGetPhysicalDisplayTokenVariantCount,
                    result
            );
            return false;
        }

        symbols_.get_physical_display_token_raw = result.address;
        symbols_.get_display_token_api_level = result.api_level;

        // Check which variant was resolved
        const char *sym_name = result.symbol_name;
        if (sym_name) {
            if (strstr(sym_name, "getInternalDisplayToken")) {
                symbols_.use_internal_display_token = true;
                FUTON_LOGI("Using getInternalDisplayToken variant");
            } else if (strstr(sym_name, "getBuiltInDisplay")) {
                symbols_.use_built_in_display = true;
                FUTON_LOGI("Using getBuiltInDisplay variant");
            }
        }

        FUTON_LOGI("Resolved getPhysicalDisplayToken: API level %d", result.api_level);
        return true;
    }

    bool SurfaceControlLoader::resolve_get_display_info() {
        ResolvedSymbol result = resolver_.resolve_symbol(
                symbols_.libgui_handle,
                SymbolResolver::kGetDisplayInfoVariants,
                SymbolResolver::kGetDisplayInfoVariantCount
        );

        if (!result.success) {
            return false;
        }

        symbols_.get_display_info = reinterpret_cast<GetDisplayInfoFn>(result.address);
        FUTON_LOGI("Resolved getDisplayInfo");
        return true;
    }

    bool SurfaceControlLoader::resolve_get_active_mode() {
        ResolvedSymbol result = resolver_.resolve_symbol(
                symbols_.libgui_handle,
                SymbolResolver::kGetActiveDisplayModeVariants,
                SymbolResolver::kGetActiveDisplayModeVariantCount
        );

        if (!result.success) {
            return false;
        }

        symbols_.get_active_mode_raw = result.address;

        // Check if we got getActiveConfig (Android 11) or getActiveDisplayMode (12+)
        if (result.symbol_name && strstr(result.symbol_name, "getActiveConfig")) {
            symbols_.use_active_config = true;
            FUTON_LOGI("Resolved getActiveConfig (Android 11)");
        } else {
            FUTON_LOGI("Resolved getActiveDisplayMode");
        }

        return true;
    }

} // namespace futon::vision
