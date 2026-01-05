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

#include "vision/display/display_adapter.h"
#include "core/error.h"

#include <dlfcn.h>
#include <sys/system_properties.h>
#include <cstring>

using namespace futon::core;

namespace futon::vision {

// Forward declarations for Android internal types
    struct IBinder;

/**
 * Android sp<T> smart pointer structure.
 *
 * In Android's libutils, sp<T> is a reference-counted smart pointer.
 * The actual structure contains just a pointer to the managed object.
 *
 * CRITICAL ARM64 ABI NOTE:
 * Android's real sp<T> has a non-trivial destructor (calls decStrong).
 * According to AAPCS64 (ARM64 ABI), types with non-trivial destructors
 * are returned via the x8 register (sret), NOT in x0.
 *
 * We MUST have a non-trivial destructor to match the ABI, otherwise:
 * - Our code expects return in x0
 * - System function writes to address in x8 (garbage!)
 * - CRASH!
 */
    template<typename T>
    struct sp {
        T *ptr;

        sp() : ptr(nullptr) {}

        explicit sp(T *p) : ptr(p) {}

        // Non-trivial destructor forces ARM64 ABI to use x8 (sret) for return value.
        // We don't actually decrement ref count - we don't own the reference.
        // This destructor exists solely to match the ABI of Android's sp<T>.
        ~sp() {
            // Intentionally empty - prevents compiler from treating this as trivially copyable
            // The asm volatile prevents the compiler from optimizing this away
            __asm__ volatile("":: : "memory");
        }

        // Non-trivial copy constructor (also required for ABI compatibility)
        sp(const sp &other) : ptr(other.ptr) {
            __asm__ volatile("":: : "memory");
        }

        sp &operator=(const sp &other) {
            ptr = other.ptr;
            return *this;
        }

        T *get() const { return ptr; }

        explicit operator bool() const { return ptr != nullptr; }
    };

// Verify sp<IBinder> is pointer-sized (but NOT trivially copyable due to destructor)
    static_assert(sizeof(sp<IBinder>) == sizeof(void *), "sp<IBinder> must be pointer-sized");

/**
 * Function pointer types for different Android versions.
 *
 * ARM64 ABI Notes (AAPCS64):
 * - sp<IBinder> has non-trivial destructor, so it's returned via x8 register (sret)
 * - String8/std::string passed by const reference (pointer in register)
 * - bool is passed in register (w0-w7)
 * - uint64_t is passed in register (x0-x7)
 * - float is passed in s0-s7 (SIMD registers)
 *
 * Our sp<T> struct has a non-trivial destructor to match Android's ABI.
 * The compiler automatically handles the x8 sret convention.
 */

// Android 11 (R): sp<IBinder> createDisplay(const String8& name, bool secure)
    using CreateDisplayFn_R = sp<IBinder>(*)(const AndroidString8 &name, bool secure);

// Android 12-13 (S/T): Same signature as R
    using CreateDisplayFn_S = sp<IBinder>(*)(const AndroidString8 &name, bool secure);

// Android 14-15 (U/V): sp<IBinder> createDisplay(const String8& name, bool secure, DisplayId)
    using CreateDisplayFn_U = sp<IBinder>(*)(const AndroidString8 &name, bool secure,
                                             uint64_t displayId);

// Android 16 (B): sp<IBinder> createVirtualDisplay(const std::string& name, bool secure,
//                                                   bool receiveFrameUsedExclusively,
//                                                   const std::string& uniqueId,
//                                                   float requestedRefreshRate)
    using CreateVirtualDisplayFn_B = sp<IBinder>(*)(const std::string &name,
                                                    bool secure,
                                                    bool receiveFrameUsedExclusively,
                                                    const std::string &uniqueId,
                                                    float requestedRefreshRate);

// destroyDisplay / destroyVirtualDisplay
    using DestroyDisplayFn = void (*)(const sp<IBinder> &display);


    DisplayAdapter::DisplayAdapter() = default;

    DisplayAdapter::~DisplayAdapter() {
        if (libgui_handle_) {
            dlclose(libgui_handle_);
            libgui_handle_ = nullptr;
        }
    }

    static int get_device_api_level() {
        char value[PROP_VALUE_MAX] = {0};
        int len = __system_property_get("ro.build.version.sdk", value);
        if (len > 0) {
            return atoi(value);
        }
        return 30;  // Default to Android 11
    }

    bool DisplayAdapter::initialize(const DiscoveredSymbol &symbol, int api_level) {
        if (initialized_) {
            FUTON_LOGW("DisplayAdapter: already initialized");
            return true;
        }

        if (!symbol.address) {
            FUTON_LOGE("DisplayAdapter: invalid symbol address");
            return false;
        }

        api_level_ = api_level;
        create_display_fn_ = symbol.address;
        adapter_type_ = detect_adapter_type(symbol, api_level);

        if (adapter_type_ == AdapterType::Unknown) {
            FUTON_LOGE("DisplayAdapter: failed to detect adapter type");
            return false;
        }

        // Try to resolve destroyDisplay
        resolve_destroy_display();

        initialized_ = true;
        FUTON_LOGI("DisplayAdapter: initialized with %s", get_description().c_str());
        return true;
    }

    bool DisplayAdapter::initialize_auto() {
        if (initialized_) {
            return true;
        }

        api_level_ = get_device_api_level();
        FUTON_LOGI("DisplayAdapter: auto-initializing for API level %d", api_level_);

        // Load libgui.so
        libgui_handle_ = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
        if (!libgui_handle_) {
            // Try full path
            libgui_handle_ = dlopen("/system/lib64/libgui.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!libgui_handle_) {
            FUTON_LOGE("DisplayAdapter: failed to load libgui.so: %s", dlerror());
            return false;
        }

        // Use ELF scanner to find the symbol
        ElfSymbolScanner scanner;
        LibraryMapping mapping = scanner.find_library("libgui.so");

        if (mapping.base_address == 0) {
            FUTON_LOGE("DisplayAdapter: libgui.so not found in memory maps");
            return false;
        }

        DiscoveredSymbol symbol = scanner.find_create_display_symbol(mapping);
        if (!symbol.address) {
            FUTON_LOGE("DisplayAdapter: createDisplay symbol not found");
            return false;
        }

        return initialize(symbol, api_level_);
    }

    AdapterType DisplayAdapter::detect_adapter_type(const DiscoveredSymbol &symbol, int api_level) {
        const std::string &demangled = symbol.demangled_name;
        int param_count = symbol.param_count;

        FUTON_LOGD("detect_adapter_type: demangled=%s, params=%d, api=%d",
                   demangled.c_str(), param_count, api_level);

        // Check for createVirtualDisplay (Android 16+)
        if (demangled.find("createVirtualDisplay") != std::string::npos) {
            FUTON_LOGI("Detected Android 16+ createVirtualDisplay API");
            return AdapterType::Adapter_B;
        }

        // Check for createDisplay with DisplayId parameter (Android 14+)
        if (demangled.find("DisplayId") != std::string::npos || param_count >= 3) {
            FUTON_LOGI("Detected Android 14+ createDisplay API with DisplayId");
            return AdapterType::Adapter_U;
        }

        // Android 12-13 vs Android 11 have same signature
        // Differentiate by API level
        if (api_level >= static_cast<int>(AndroidApiLevel::S)) {
            FUTON_LOGI("Detected Android 12-13 createDisplay API");
            return AdapterType::Adapter_S;
        }

        FUTON_LOGI("Detected Android 11 createDisplay API");
        return AdapterType::Adapter_R;
    }

    bool DisplayAdapter::resolve_destroy_display() {
        if (!libgui_handle_) {
            // Try to get handle from dlopen
            libgui_handle_ = dlopen("libgui.so", RTLD_NOW | RTLD_NOLOAD);
            if (!libgui_handle_) {
                return false;
            }
        }

        // Try different symbol names
        const char *destroy_symbols[] = {
                "_ZN7android21SurfaceComposerClient21destroyVirtualDisplayERKNS_2spINS_7IBinderEEE",
                "_ZN7android21SurfaceComposerClient14destroyDisplayERKNS_2spINS_7IBinderEEE",
        };

        for (const char *sym: destroy_symbols) {
            destroy_display_fn_ = dlsym(libgui_handle_, sym);
            if (destroy_display_fn_) {
                FUTON_LOGD("Resolved destroyDisplay: %s", sym);
                return true;
            }
        }

        FUTON_LOGW("destroyDisplay symbol not found (optional)");
        return false;
    }

    DisplayToken DisplayAdapter::create_display(const CreateDisplayParams &params) {
        if (!initialized_) {
            FUTON_LOGE("DisplayAdapter: not initialized");
            return DisplayToken{};
        }

        switch (adapter_type_) {
            case AdapterType::Adapter_R:
                return call_adapter_r(params);
            case AdapterType::Adapter_S:
                return call_adapter_s(params);
            case AdapterType::Adapter_U:
                return call_adapter_u(params);
            case AdapterType::Adapter_B:
                return call_adapter_b(params);
            default:
                FUTON_LOGE("DisplayAdapter: unknown adapter type");
                return DisplayToken{};
        }
    }


    DisplayToken DisplayAdapter::call_adapter_r(const CreateDisplayParams &params) {
        FUTON_LOGI("=== Adapter_R (Android 11) ===");
        FUTON_LOGI("  name=%s, secure=%d", params.name.c_str(), params.secure);

        auto fn = reinterpret_cast<CreateDisplayFn_R>(create_display_fn_);
        if (!fn) {
            FUTON_LOGE("call_adapter_r: null function pointer");
            return DisplayToken{};
        }

        AndroidString8 name(params.name);

        // Call createDisplay with Android 11 signature
        // ARM64 ABI: sp<IBinder> (trivially copyable, 8 bytes) returned in x0
        // Signature: sp<IBinder> createDisplay(const String8& name, bool secure)
        FUTON_LOGD("Calling createDisplay(String8(\"%s\"), %s)",
                   name.c_str(), params.secure ? "true" : "false");

        sp<IBinder> result = fn(name, params.secure);

        if (!result.ptr) {
            FUTON_LOGE("call_adapter_r: createDisplay returned null");
            FUTON_LOGE("  This may indicate:");
            FUTON_LOGE("  - Insufficient permissions (need root/shell)");
            FUTON_LOGE("  - SELinux denial (check dmesg for avc)");
            FUTON_LOGE("  - SurfaceFlinger service not available");
            return DisplayToken{};
        }

        FUTON_LOGI("call_adapter_r: success, token=%p", result.ptr);
        return DisplayToken{result.ptr};
    }

    DisplayToken DisplayAdapter::call_adapter_s(const CreateDisplayParams &params) {
        FUTON_LOGI("=== Adapter_S (Android 12-13) ===");
        FUTON_LOGI("  name=%s, secure=%d", params.name.c_str(), params.secure);

        auto fn = reinterpret_cast<CreateDisplayFn_S>(create_display_fn_);
        if (!fn) {
            FUTON_LOGE("call_adapter_s: null function pointer");
            return DisplayToken{};
        }

        AndroidString8 name(params.name);

        // Call createDisplay with Android 12-13 signature
        // Same as Android 11, but internal implementation uses BLAST architecture
        // ARM64 ABI: sp<IBinder> (trivially copyable, 8 bytes) returned in x0
        // Signature: sp<IBinder> createDisplay(const String8& name, bool secure)
        FUTON_LOGD("Calling createDisplay(String8(\"%s\"), %s)",
                   name.c_str(), params.secure ? "true" : "false");

        sp<IBinder> result = fn(name, params.secure);

        if (!result.ptr) {
            FUTON_LOGE("call_adapter_s: createDisplay returned null");
            FUTON_LOGE("  This may indicate:");
            FUTON_LOGE("  - Insufficient permissions (need root/shell)");
            FUTON_LOGE("  - SELinux denial (check dmesg for avc)");
            FUTON_LOGE("  - SurfaceFlinger service not available");
            return DisplayToken{};
        }

        FUTON_LOGI("call_adapter_s: success, token=%p", result.ptr);
        return DisplayToken{result.ptr};
    }

    DisplayToken DisplayAdapter::call_adapter_u(const CreateDisplayParams &params) {
        FUTON_LOGI("=== Adapter_U (Android 14-15) ===");
        FUTON_LOGI("  name=%s, secure=%d, displayId=%lu",
                   params.name.c_str(), params.secure,
                   static_cast<unsigned long>(params.display_id));

        auto fn = reinterpret_cast<CreateDisplayFn_U>(create_display_fn_);
        if (!fn) {
            FUTON_LOGE("call_adapter_u: null function pointer");
            return DisplayToken{};
        }

        AndroidString8 name(params.name);

        // Call createDisplay with Android 14-15 signature
        // New parameter: DisplayId for display association
        // ARM64 ABI: sp<IBinder> (trivially copyable, 8 bytes) returned in x0
        // Signature: sp<IBinder> createDisplay(const String8& name, bool secure, DisplayId displayId)
        FUTON_LOGD("Calling createDisplay(String8(\"%s\"), %s, %lu)",
                   name.c_str(), params.secure ? "true" : "false",
                   static_cast<unsigned long>(params.display_id));

        sp<IBinder> result = fn(name, params.secure, params.display_id);

        if (!result.ptr) {
            FUTON_LOGE("call_adapter_u: createDisplay returned null");
            FUTON_LOGE("  This may indicate:");
            FUTON_LOGE("  - Insufficient permissions (need root/shell)");
            FUTON_LOGE("  - SELinux denial (check dmesg for avc)");
            FUTON_LOGE("  - Invalid DisplayId");
            FUTON_LOGE("  - SurfaceFlinger service not available");
            return DisplayToken{};
        }

        FUTON_LOGI("call_adapter_u: success, token=%p", result.ptr);
        return DisplayToken{result.ptr};
    }

/**
 * Android 16+ createVirtualDisplay adapter.
 *
 * IMPORTANT: This code MUST be built with -DANDROID_STL=c++_shared
 * to ensure ABI compatibility with system's libgui.so.
 *
 * ARM64 ABI: sp<IBinder> has a non-trivial destructor, so it's returned
 * via the x8 register (sret), not in x0. Our sp<T> struct has a non-trivial
 * destructor to match this ABI - the compiler handles x8 automatically.
 */
    DisplayToken DisplayAdapter::call_adapter_b(const CreateDisplayParams &params) {
        FUTON_LOGI("=== Adapter_B (Android 16+) ===");
        FUTON_LOGI("  name=%s, secure=%d, exclusive=%d, uniqueId=%s, fps=%.1f",
                   params.name.c_str(), params.secure,
                   params.receive_frame_used_exclusively,
                   params.unique_id.c_str(), params.requested_refresh_rate);

        if (!create_display_fn_) {
            FUTON_LOGE("call_adapter_b: null function pointer");
            return DisplayToken{};
        }

        // Prepare unique_id
        std::string unique_id_str = params.unique_id;
        if (unique_id_str.empty()) {
            unique_id_str = "futon_" + params.name;
        }

        FUTON_LOGD("Calling createVirtualDisplay(\"%s\", %s, %s, \"%s\", %.1f)",
                   params.name.c_str(),
                   params.secure ? "true" : "false",
                   params.receive_frame_used_exclusively ? "true" : "false",
                   unique_id_str.c_str(),
                   params.requested_refresh_rate);

        // Cast to function pointer type
        // Our sp<T> has non-trivial destructor, so ARM64 ABI uses x8 (sret) for return
        // The compiler handles this automatically when we declare the return type as sp<IBinder>
        auto fn = reinterpret_cast<CreateVirtualDisplayFn_B>(create_display_fn_);

        FUTON_LOGD("Calling function at %p", create_display_fn_);

        // The compiler will:
        // 1. Allocate stack space for sp<IBinder> result
        // 2. Put address in x8 (because sp has non-trivial destructor)
        // 3. Call the function with args in x0, w1, w2, x3, s0
        // 4. Function writes result to address in x8
        // 5. Result is valid
        sp<IBinder> result = fn(params.name,
                                params.secure,
                                params.receive_frame_used_exclusively,
                                unique_id_str,
                                params.requested_refresh_rate);

        FUTON_LOGD("Function returned, result.ptr=%p", result.ptr);

        if (!result.ptr) {
            FUTON_LOGE("call_adapter_b: createVirtualDisplay returned null");
            FUTON_LOGE("  Possible causes:");
            FUTON_LOGE("  - Insufficient permissions (need root/shell)");
            FUTON_LOGE("  - SELinux denial (check: dmesg | grep avc)");
            FUTON_LOGE("  - SurfaceFlinger service not available");
            return DisplayToken{};
        }

        FUTON_LOGI("call_adapter_b: success, token=%p", result.ptr);
        return DisplayToken{result.ptr};
    }

    void DisplayAdapter::destroy_display(const DisplayToken &token) {
        if (!token.is_valid()) {
            return;
        }

        if (!destroy_display_fn_) {
            FUTON_LOGW("destroy_display: destroyDisplay function not available");
            return;
        }

        auto fn = reinterpret_cast<DestroyDisplayFn>(destroy_display_fn_);
        sp<IBinder> display;
        display.ptr = reinterpret_cast<IBinder *>(token.ptr);

        FUTON_LOGD("destroy_display: destroying token=%p", token.ptr);
        fn(display);
    }

    std::string DisplayAdapter::get_description() const {
        std::string desc;

        switch (adapter_type_) {
            case AdapterType::Adapter_R:
                desc = "Adapter_R (Android 11)";
                break;
            case AdapterType::Adapter_S:
                desc = "Adapter_S (Android 12-13)";
                break;
            case AdapterType::Adapter_U:
                desc = "Adapter_U (Android 14-15)";
                break;
            case AdapterType::Adapter_B:
                desc = "Adapter_B (Android 16+)";
                break;
            default:
                desc = "Unknown";
                break;
        }

        desc += " [API " + std::to_string(api_level_) + "]";
        return desc;
    }

} // namespace futon::vision
