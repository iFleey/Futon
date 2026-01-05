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

#include "vision/loader/symbol_resolver.h"
#include "core/error.h"

#include <dlfcn.h>
#include <sys/system_properties.h>
#include <cstdlib>
#include <cstring>

using namespace futon::core;

namespace futon::vision {

// Symbol variant tables for SurfaceComposerClient APIs
// Ordered by API level (newest first) for priority resolution

// createDisplay / createVirtualDisplay - Creates a virtual display
// Android 16+ renamed to createVirtualDisplay with new signature
// Android 12+ uses BLASTBufferQueue, signature changes
    const SymbolVariant SymbolResolver::kCreateDisplayVariants[] = {
            // Android 16+ (B): Renamed to createVirtualDisplay with std::string
            // _ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f
            {"_ZN7android21SurfaceComposerClient20createVirtualDisplayERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEbbS9_f", 36, -1},
            // Android 14-15 (U/V): New signature with DisplayId
            {"_ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8EbNS_2ui9DisplayIdE",                                                34, 35},
            // Android 12-13 (S/T): BLAST architecture
            {"_ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Eb",                                                                 31, 33},
            // Android 11 (R): Legacy signature
            {"_ZN7android21SurfaceComposerClient13createDisplayERKNS_7String8Eb",                                                                 30, 30},
    };
    const size_t SymbolResolver::kCreateDisplayVariantCount =
            sizeof(kCreateDisplayVariants) / sizeof(kCreateDisplayVariants[0]);

// destroyDisplay / destroyVirtualDisplay - Destroys a virtual display
    const SymbolVariant SymbolResolver::kDestroyDisplayVariants[] = {
            // Android 16+: Renamed to destroyVirtualDisplay
            {"_ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE", 36, -1},
            // Android 14-15
            {"_ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE",        34, 35},
            // Android 11-13
            {"_ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE",        30, 33},
    };
    const size_t SymbolResolver::kDestroyDisplayVariantCount =
            sizeof(kDestroyDisplayVariants) / sizeof(kDestroyDisplayVariants[0]);

// getPhysicalDisplayToken - Gets the display token for physical display
// Android 14+ changed how display tokens are obtained
    const SymbolVariant SymbolResolver::kGetPhysicalDisplayTokenVariants[] = {
            // Android 16+ (B): Symbol found on device with 23-char name encoding
            {"_ZN7android21SurfaceComposerClient23getPhysicalDisplayTokenENS_17PhysicalDisplayIdE", 36, -1},
            // Android 14-15 (U/V): Uses PhysicalDisplayId with 24-char name encoding
            {"_ZN7android21SurfaceComposerClient24getPhysicalDisplayTokenENS_17PhysicalDisplayIdE", 34, 35},
            // Android 12-13 (S/T): Uses DisplayId
            {"_ZN7android21SurfaceComposerClient24getPhysicalDisplayTokenENS_2ui9DisplayIdE",       31, 33},
            // Android 11 (R): Uses int64_t
            {"_ZN7android21SurfaceComposerClient24getPhysicalDisplayTokenEy",                       30, 30},
            // Alternative: getInternalDisplayToken (some ROMs)
            {"_ZN7android21SurfaceComposerClient23getInternalDisplayTokenEv",                       30, -1},
            // Alternative: getBuiltInDisplay (legacy)
            {"_ZN7android21SurfaceComposerClient17getBuiltInDisplayEi",                             30, 33},
    };
    const size_t SymbolResolver::kGetPhysicalDisplayTokenVariantCount =
            sizeof(kGetPhysicalDisplayTokenVariants) / sizeof(kGetPhysicalDisplayTokenVariants[0]);

// getDisplayInfo - Gets display information
    const SymbolVariant SymbolResolver::kGetDisplayInfoVariants[] = {
            // Android 14+: New DisplayInfo structure
            {"_ZN7android21SurfaceComposerClient14getDisplayInfoERKNS_2spINS_7IBinderEEEPNS_2ui11DisplayInfoE", 34, -1},
            // Android 12-13: ui::DisplayInfo
            {"_ZN7android21SurfaceComposerClient14getDisplayInfoERKNS_2spINS_7IBinderEEEPNS_2ui11DisplayInfoE", 31, 33},
            // Android 11: DisplayInfo
            {"_ZN7android21SurfaceComposerClient14getDisplayInfoERKNS_2spINS_7IBinderEEEPNS_11DisplayInfoE",    30, 30},
    };
    const size_t SymbolResolver::kGetDisplayInfoVariantCount =
            sizeof(kGetDisplayInfoVariants) / sizeof(kGetDisplayInfoVariants[0]);

// getActiveDisplayMode - Gets active display mode (resolution, refresh rate)
    const SymbolVariant SymbolResolver::kGetActiveDisplayModeVariants[] = {
            // Android 14+
            {"_ZN7android21SurfaceComposerClient20getActiveDisplayModeERKNS_2spINS_7IBinderEEEPNS_2ui11DisplayModeE", 34, -1},
            // Android 12-13
            {"_ZN7android21SurfaceComposerClient20getActiveDisplayModeERKNS_2spINS_7IBinderEEEPNS_2ui11DisplayModeE", 31, 33},
            // Android 11: getActiveConfig
            {"_ZN7android21SurfaceComposerClient15getActiveConfigERKNS_2spINS_7IBinderEEE",                           30, 30},
    };
    const size_t SymbolResolver::kGetActiveDisplayModeVariantCount =
            sizeof(kGetActiveDisplayModeVariants) / sizeof(kGetActiveDisplayModeVariants[0]);


    SymbolResolver::SymbolResolver() = default;

    SymbolResolver::~SymbolResolver() = default;

    bool SymbolResolver::initialize() {
        if (initialized_) {
            return true;
        }

        api_level_ = detect_api_level();
        if (api_level_ < static_cast<int>(AndroidVersion::R)) {
            FUTON_LOGE("SymbolResolver: Android %d not supported (minimum: Android 11/R)",
                       api_level_);
            return false;
        }

        FUTON_LOGI("SymbolResolver initialized: API level %d, BLAST=%s, NewDisplayToken=%s",
                   api_level_,
                   is_blast_architecture() ? "yes" : "no",
                   is_new_display_token() ? "yes" : "no");

        initialized_ = true;
        return true;
    }

    int SymbolResolver::detect_api_level() {
        char value[PROP_VALUE_MAX] = {0};
        int len = __system_property_get("ro.build.version.sdk", value);
        if (len > 0) {
            return atoi(value);
        }

        // Fallback: try alternative property
        len = __system_property_get("ro.system.build.version.sdk", value);
        if (len > 0) {
            return atoi(value);
        }

        FUTON_LOGW("Failed to detect API level, assuming Android 11");
        return static_cast<int>(AndroidVersion::R);
    }

    bool SymbolResolver::is_variant_compatible(const SymbolVariant &variant) const {
        if (api_level_ < variant.min_api_level) {
            return false;
        }
        if (variant.max_api_level != -1 && api_level_ > variant.max_api_level) {
            return false;
        }
        return true;
    }

    ResolvedSymbol SymbolResolver::resolve_symbol(void *handle,
                                                  const SymbolVariant *variants,
                                                  size_t variant_count) {
        ResolvedSymbol result;

        if (!handle || !variants || variant_count == 0) {
            FUTON_LOGE("resolve_symbol: invalid arguments");
            return result;
        }

        // Clear any previous dlerror
        dlerror();

        // Try variants in order (newest first)
        for (size_t i = 0; i < variant_count; ++i) {
            const SymbolVariant &variant = variants[i];

            // Skip incompatible variants
            if (!is_variant_compatible(variant)) {
                continue;
            }

            void *sym = dlsym(handle, variant.symbol_name);
            if (sym) {
                result.address = sym;
                result.symbol_name = variant.symbol_name;
                result.api_level = variant.min_api_level;
                result.success = true;
                FUTON_LOGD("Symbol resolved: %s (API %d-%d)",
                           variant.symbol_name,
                           variant.min_api_level,
                           variant.max_api_level);
                return result;
            }
        }

        // All variants failed
        const char *error = dlerror();
        FUTON_LOGW("Symbol resolution failed: %s", error ? error : "unknown error");
        return result;
    }

    void SymbolResolver::log_resolution_attempts(const char *symbol_category,
                                                 const SymbolVariant *variants,
                                                 size_t variant_count,
                                                 const ResolvedSymbol &result) {
        FUTON_LOGI("=== Symbol Resolution: %s ===", symbol_category);
        FUTON_LOGI("Device API level: %d (Android %s)", api_level_,
                   api_level_ >= 36 ? "16/Baklava" :
                   api_level_ >= 35 ? "15/V" :
                   api_level_ >= 34 ? "14/U" :
                   api_level_ >= 33 ? "13/T" :
                   api_level_ >= 32 ? "12L/S_V2" :
                   api_level_ >= 31 ? "12/S" :
                   api_level_ >= 30 ? "11/R" : "Unknown");

        int tried_count = 0;
        int skipped_count = 0;

        for (size_t i = 0; i < variant_count; ++i) {
            const SymbolVariant &v = variants[i];
            bool compatible = is_variant_compatible(v);
            bool is_resolved = result.success && result.symbol_name == v.symbol_name;

            if (compatible) {
                tried_count++;
            } else {
                skipped_count++;
            }

            const char *api_range = v.max_api_level == -1 ? "+" : "";
            FUTON_LOGI("  [%s] %s (API %d%s%s) %s",
                       is_resolved ? "OK" : (compatible ? "FAIL" : "SKIP"),
                       v.symbol_name,
                       v.min_api_level,
                       v.max_api_level != -1 ? "-" : "",
                       v.max_api_level != -1 ? std::to_string(v.max_api_level).c_str() : api_range,
                       compatible ? "" : "(incompatible with device)");
        }

        FUTON_LOGI("Attempted: %d variants, Skipped: %d variants", tried_count, skipped_count);

        if (result.success) {
            FUTON_LOGI("Result: SUCCESS - %s", result.symbol_name);
        } else {
            FUTON_LOGE("Result: FAILED - PrivateApiUnavailable");
            FUTON_LOGE("All %d compatible symbol variants failed to resolve", tried_count);
            FUTON_LOGE("This may indicate:");
            FUTON_LOGE("  - ROM has stripped Private APIs (common in custom ROMs)");
            FUTON_LOGE("  - Symbol names changed in this Android version");
            FUTON_LOGE("  - Library not accessible due to linker namespace restrictions");
        }
    }

    size_t SymbolResolver::get_display_info_size(int api_level) {
        // DisplayInfo structure size varies by Android version
        // These are approximate sizes; actual size depends on ROM
        if (api_level >= 34) {
            return 256;  // Android 14+: larger structure
        } else if (api_level >= 31) {
            return 192;  // Android 12-13: ui::DisplayInfo
        } else {
            return 128;  // Android 11: DisplayInfo
        }
    }

} // namespace futon::vision
