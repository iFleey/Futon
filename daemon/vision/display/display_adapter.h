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

#ifndef FUTON_VISION_DISPLAY_DISPLAY_ADAPTER_H
#define FUTON_VISION_DISPLAY_DISPLAY_ADAPTER_H

#include "vision/loader/elf_symbol_scanner.h"
#include <cstdint>
#include <string>
#include <memory>

namespace futon::vision {

/**
 * Android version enum for adapter selection.
 */
    enum class AndroidApiLevel : int {
        R = 30,      // Android 11
        S = 31,      // Android 12
        S_V2 = 32,   // Android 12L
        T = 33,      // Android 13
        U = 34,      // Android 14
        V = 35,      // Android 15
        B = 36,      // Android 16 (Baklava)
    };

/**
 * Adapter type based on API signature.
 *
 * Trampoline adapters for different Android versions:
 * - Adapter_R: Android 11 (R) - createDisplay(String8, bool)
 * - Adapter_S: Android 12-13 (S/T) - createDisplay(String8, bool)
 * - Adapter_U: Android 14-15 (U/V) - createDisplay(String8, bool, DisplayId)
 * - Adapter_B: Android 16 (B) - createVirtualDisplay(std::string, bool, bool, std::string, float)
 */
    enum class AdapterType {
        Unknown,
        Adapter_R,   // Android 11 (R): createDisplay(String8, bool)
        Adapter_S,   // Android 12-13 (S/T): createDisplay(String8, bool)
        Adapter_U,   // Android 14-15 (U/V): createDisplay(String8, bool, DisplayId)
        Adapter_B,   // Android 16 (B): createVirtualDisplay(std::string, bool, bool, std::string, float)
    };

/**
 * Opaque handle for display token (IBinder).
 */
    struct DisplayToken {
        void *ptr = nullptr;

        bool is_valid() const { return ptr != nullptr; }

        explicit operator bool() const { return is_valid(); }
    };

/**
 * Parameters for creating a virtual display.
 */
    struct CreateDisplayParams {
        std::string name = "FutonCapture";
        bool secure = false;
        uint64_t display_id = 0;
        bool receive_frame_used_exclusively = true;
        std::string unique_id;
        float requested_refresh_rate = 60.0f;
    };

/**
 * DisplayAdapter - Trampoline adapters for SurfaceComposerClient APIs.
 *
 * Provides a unified interface to call createDisplay/createVirtualDisplay
 * across different Android versions (11-16).
 *
 * Adapter signatures:
 * - Android 11 (R): Adapter_R(func_ptr, String8, bool)
 * - Android 12-13 (S/T): Adapter_S(func_ptr, String8, bool)
 * - Android 14-15 (U/V): Adapter_U(func_ptr, String8, bool, DisplayId)
 * - Android 16 (B): Adapter_B(func_ptr, std::string, bool, bool, std::string, float)
 */
    class DisplayAdapter {
    public:
        DisplayAdapter();

        ~DisplayAdapter();

        /**
         * Initialize the adapter with a discovered symbol.
         * @param symbol Symbol discovered by ElfSymbolScanner
         * @param api_level Current Android API level
         * @return true if adapter initialized successfully
         */
        bool initialize(const DiscoveredSymbol &symbol, int api_level);

        /**
         * Initialize the adapter by auto-detecting from libgui.so.
         * @return true if adapter initialized successfully
         */
        bool initialize_auto();

        /**
         * Check if adapter is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Get the adapter type.
         */
        AdapterType get_adapter_type() const { return adapter_type_; }

        /**
         * Get the API level.
         */
        int get_api_level() const { return api_level_; }

        /**
         * Create a virtual display using the appropriate adapter.
         * @param params Display creation parameters
         * @return Display token, or invalid token on failure
         */
        DisplayToken create_display(const CreateDisplayParams &params);

        /**
         * Destroy a virtual display.
         * @param token Display token to destroy
         */
        void destroy_display(const DisplayToken &token);

        std::string get_description() const;

    private:
        bool initialized_ = false;
        AdapterType adapter_type_ = AdapterType::Unknown;
        int api_level_ = 0;
        void *create_display_fn_ = nullptr;
        void *destroy_display_fn_ = nullptr;
        void *libgui_handle_ = nullptr;

        // Adapter implementations
        DisplayToken call_adapter_r(const CreateDisplayParams &params);

        DisplayToken call_adapter_s(const CreateDisplayParams &params);

        DisplayToken call_adapter_u(const CreateDisplayParams &params);

        DisplayToken call_adapter_b(const CreateDisplayParams &params);

        AdapterType detect_adapter_type(const DiscoveredSymbol &symbol, int api_level);

        bool resolve_destroy_display();
    };

/**
 * String8 structure compatible with Android's String8.
 * Used for Android 11-15 APIs.
 */
    struct AndroidString8 {
        // String8 internal structure (simplified)
        // The actual structure is more complex, but we only need the data pointer
        char mString[256];
        size_t mLength;

        AndroidString8() : mLength(0) {
            mString[0] = '\0';
        }

        explicit AndroidString8(const char *str) {
            if (str) {
                mLength = strlen(str);
                if (mLength >= sizeof(mString)) {
                    mLength = sizeof(mString) - 1;
                }
                memcpy(mString, str, mLength);
                mString[mLength] = '\0';
            } else {
                mLength = 0;
                mString[0] = '\0';
            }
        }

        explicit AndroidString8(const std::string &str) : AndroidString8(str.c_str()) {}

        const char *c_str() const { return mString; }

        size_t length() const { return mLength; }
    };

} // namespace futon::vision

#endif // FUTON_VISION_DISPLAY_DISPLAY_ADAPTER_H
