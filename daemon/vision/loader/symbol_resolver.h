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

#ifndef FUTON_VISION_LOADER_SYMBOL_RESOLVER_H
#define FUTON_VISION_LOADER_SYMBOL_RESOLVER_H

#include <cstdint>
#include <string>
#include <vector>
#include <functional>

namespace futon::vision {

/**
 * Android API level constants for symbol resolution.
 */
    enum class AndroidVersion : int {
        R = 30,      // Android 11
        S = 31,      // Android 12
        S_V2 = 32,   // Android 12L
        T = 33,      // Android 13
        U = 34,      // Android 14
        V = 35,      // Android 15
        B = 36,      // Android 16 (Baklava)
    };

/**
 * Symbol variant entry for version-specific symbol names.
 */
    struct SymbolVariant {
        const char *symbol_name;
        int min_api_level;
        int max_api_level;  // -1 means no upper bound
    };

/**
 * Result of symbol resolution.
 */
    struct ResolvedSymbol {
        void *address = nullptr;
        const char *symbol_name = nullptr;
        int api_level = 0;
        bool success = false;
    };

/**
 * SymbolResolver - Dynamic symbol resolution for Android Private APIs.
 *
 * Maintains symbol variant tables for Android 11 (R) through 16 (Baklava).
 * Strategy: Prioritize newest symbols, fallback to older versions.
 *
 * Key considerations:
 * - Android 12+ BLASTBufferQueue changes to createDisplay
 * - Android 14+ DisplayToken acquisition changes
 * - All variants fail -> PrivateApiUnavailable error
 */
    class SymbolResolver {
    public:
        SymbolResolver();

        ~SymbolResolver();

        /**
         * Initialize resolver with current device API level.
         * @return true if initialization successful
         */
        bool initialize();

        /**
         * Get current device API level.
         */
        int get_api_level() const { return api_level_; }

        /**
         * Check if running on Android 12+ (BLAST architecture).
         */
        bool is_blast_architecture() const {
            return api_level_ >= static_cast<int>(AndroidVersion::S);
        }

        /**
         * Check if running on Android 14+ (new DisplayToken).
         */
        bool is_new_display_token() const {
            return api_level_ >= static_cast<int>(AndroidVersion::U);
        }

        /**
         * Resolve a symbol from a library handle using variant table.
         * @param handle Library handle from dlopen
         * @param variants Array of symbol variants to try
         * @param variant_count Number of variants
         * @return Resolved symbol info
         */
        ResolvedSymbol resolve_symbol(void *handle,
                                      const SymbolVariant *variants,
                                      size_t variant_count);

        /**
         * Resolve symbol with automatic variant selection based on API level.
         * Tries newest compatible symbols first, falls back to older ones.
         */
        template<size_t N>
        ResolvedSymbol resolve_symbol(void *handle, const SymbolVariant (&variants)[N]) {
            return resolve_symbol(handle, variants, N);
        }

        /**
         * Log all attempted variants for debugging.
         */
        void log_resolution_attempts(const char *symbol_category,
                                     const SymbolVariant *variants,
                                     size_t variant_count,
                                     const ResolvedSymbol &result);

        // Pre-defined symbol variant tables for SurfaceControl APIs

        // SurfaceComposerClient::createDisplay variants
        static const SymbolVariant kCreateDisplayVariants[];
        static const size_t kCreateDisplayVariantCount;

        // SurfaceComposerClient::destroyDisplay variants
        static const SymbolVariant kDestroyDisplayVariants[];
        static const size_t kDestroyDisplayVariantCount;

        // SurfaceComposerClient::getPhysicalDisplayToken variants
        static const SymbolVariant kGetPhysicalDisplayTokenVariants[];
        static const size_t kGetPhysicalDisplayTokenVariantCount;

        // SurfaceComposerClient::getDisplayInfo variants
        static const SymbolVariant kGetDisplayInfoVariants[];
        static const size_t kGetDisplayInfoVariantCount;

        // SurfaceComposerClient::getActiveDisplayMode variants
        static const SymbolVariant kGetActiveDisplayModeVariants[];
        static const size_t kGetActiveDisplayModeVariantCount;

        // DisplayInfo struct size variants (for memory allocation)
        static size_t get_display_info_size(int api_level);

    private:
        int api_level_ = 0;
        bool initialized_ = false;

        int detect_api_level();

        bool is_variant_compatible(const SymbolVariant &variant) const;
    };

} // namespace futon::vision

#endif // FUTON_VISION_LOADER_SYMBOL_RESOLVER_H
