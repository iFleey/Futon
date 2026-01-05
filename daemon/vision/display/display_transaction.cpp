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

#include "vision/display/display_transaction.h"
#include "vision/loader/elf_symbol_scanner.h"
#include "core/error.h"

#include <dlfcn.h>
#include <cstring>

using namespace futon::core;

namespace futon::vision {

// Forward declarations for Android internal types
    struct IBinder;
    struct Surface;

    template<typename T>
    struct sp {
        T *ptr;

        sp() : ptr(nullptr) {}
    };

// Android Rect structure (matches ui::Rect)
    struct AndroidRect {
        int32_t left;
        int32_t top;
        int32_t right;
        int32_t bottom;
    };

// Transaction class size estimate (varies by Android version)
// We allocate extra space to be safe
    static constexpr size_t TRANSACTION_SIZE = 4096;

// Symbol patterns for Transaction API
    static const char *kTransactionCtorPatterns[] = {
            // Android 12+: Transaction::Transaction()
            "_ZN7android21SurfaceComposerClient11TransactionC1Ev",
            "_ZN7android21SurfaceComposerClient11TransactionC2Ev",
    };

    static const char *kTransactionDtorPatterns[] = {
            "_ZN7android21SurfaceComposerClient11TransactionD1Ev",
            "_ZN7android21SurfaceComposerClient11TransactionD2Ev",
    };

    static const char *kSetDisplaySurfacePatterns[] = {
            // setDisplaySurface(const sp<IBinder>& token, const sp<IGraphicBufferProducer>& bufferProducer)
            "_ZN7android21SurfaceComposerClient11Transaction17setDisplaySurfaceERKNS_2spINS_7IBinderEEERKNS1_INS_22IGraphicBufferProducerEEE",
            // Alternative with Surface
            "_ZN7android21SurfaceComposerClient11Transaction17setDisplaySurfaceERKNS_2spINS_7IBinderEEERKNS1_INS_7SurfaceEEE",
    };

    static const char *kSetDisplayProjectionPatterns[] = {
            // setDisplayProjection(const sp<IBinder>& token, ui::Rotation orientation, const Rect& layerStackRect, const Rect& displayRect)
            "_ZN7android21SurfaceComposerClient11Transaction20setDisplayProjectionERKNS_2spINS_7IBinderEEENS_2ui8RotationERKNS_4RectESA_",
            // Android 11 variant
            "_ZN7android21SurfaceComposerClient11Transaction20setDisplayProjectionERKNS_2spINS_7IBinderEEEiRKNS_4RectES9_",
    };

    static const char *kApplyPatterns[] = {
            "_ZN7android21SurfaceComposerClient11Transaction5applyEb",
            "_ZN7android21SurfaceComposerClient11Transaction5applyEv",
    };


    DisplayTransaction::DisplayTransaction() = default;

    DisplayTransaction::~DisplayTransaction() {
        destroy_transaction();
        if (libgui_handle_) {
            dlclose(libgui_handle_);
            libgui_handle_ = nullptr;
        }
    }

    bool DisplayTransaction::initialize() {
        if (initialized_) {
            return true;
        }

        // Load libgui.so
        libgui_handle_ = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
        if (!libgui_handle_) {
            libgui_handle_ = dlopen("/system/lib64/libgui.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!libgui_handle_) {
            FUTON_LOGE("DisplayTransaction: failed to load libgui.so: %s", dlerror());
            return false;
        }

        if (!resolve_transaction_symbols()) {
            FUTON_LOGE("DisplayTransaction: failed to resolve symbols");
            return false;
        }

        if (!create_transaction()) {
            FUTON_LOGE("DisplayTransaction: failed to create transaction object");
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("DisplayTransaction: initialized successfully");
        return true;
    }

    bool DisplayTransaction::resolve_transaction_symbols() {
        // Resolve Transaction constructor
        for (const char *pattern: kTransactionCtorPatterns) {
            transaction_ctor_ = dlsym(libgui_handle_, pattern);
            if (transaction_ctor_) {
                FUTON_LOGD("Resolved Transaction ctor: %s", pattern);
                break;
            }
        }
        if (!transaction_ctor_) {
            FUTON_LOGE("Failed to resolve Transaction constructor");
            return false;
        }

        // Resolve Transaction destructor
        for (const char *pattern: kTransactionDtorPatterns) {
            transaction_dtor_ = dlsym(libgui_handle_, pattern);
            if (transaction_dtor_) {
                FUTON_LOGD("Resolved Transaction dtor: %s", pattern);
                break;
            }
        }

        // Resolve setDisplaySurface
        for (const char *pattern: kSetDisplaySurfacePatterns) {
            set_display_surface_fn_ = dlsym(libgui_handle_, pattern);
            if (set_display_surface_fn_) {
                FUTON_LOGD("Resolved setDisplaySurface: %s", pattern);
                break;
            }
        }
        if (!set_display_surface_fn_) {
            FUTON_LOGW("setDisplaySurface not found (optional)");
        }

        // Resolve setDisplayProjection
        for (const char *pattern: kSetDisplayProjectionPatterns) {
            set_display_projection_fn_ = dlsym(libgui_handle_, pattern);
            if (set_display_projection_fn_) {
                FUTON_LOGD("Resolved setDisplayProjection: %s", pattern);
                break;
            }
        }
        if (!set_display_projection_fn_) {
            FUTON_LOGW("setDisplayProjection not found (optional)");
        }

        // Resolve apply
        for (const char *pattern: kApplyPatterns) {
            apply_fn_ = dlsym(libgui_handle_, pattern);
            if (apply_fn_) {
                FUTON_LOGD("Resolved apply: %s", pattern);
                break;
            }
        }
        if (!apply_fn_) {
            FUTON_LOGE("Failed to resolve Transaction::apply");
            return false;
        }

        return true;
    }

    bool DisplayTransaction::create_transaction() {
        if (transaction_obj_) {
            return true;
        }

        // Allocate memory for Transaction object
        transaction_obj_ = malloc(TRANSACTION_SIZE);
        if (!transaction_obj_) {
            FUTON_LOGE("Failed to allocate Transaction object");
            return false;
        }
        memset(transaction_obj_, 0, TRANSACTION_SIZE);

        // Call constructor
        using CtorFn = void (*)(void *);
        auto ctor = reinterpret_cast<CtorFn>(transaction_ctor_);
        ctor(transaction_obj_);

        FUTON_LOGD("Transaction object created at %p", transaction_obj_);
        return true;
    }

    void DisplayTransaction::destroy_transaction() {
        if (!transaction_obj_) {
            return;
        }

        if (transaction_dtor_) {
            using DtorFn = void (*)(void *);
            auto dtor = reinterpret_cast<DtorFn>(transaction_dtor_);
            dtor(transaction_obj_);
        }

        free(transaction_obj_);
        transaction_obj_ = nullptr;
    }


    bool DisplayTransaction::set_display_surface(const DisplayToken &display_token, void *surface) {
        if (!initialized_ || !transaction_obj_) {
            FUTON_LOGE("set_display_surface: not initialized");
            return false;
        }

        if (!display_token.is_valid()) {
            FUTON_LOGE("set_display_surface: invalid display token");
            return false;
        }

        if (!set_display_surface_fn_) {
            FUTON_LOGW("set_display_surface: function not available");
            return false;
        }

        // Prepare sp<IBinder> for display token
        sp<IBinder> token;
        token.ptr = reinterpret_cast<IBinder *>(display_token.ptr);

        // Prepare sp<Surface> or sp<IGraphicBufferProducer>
        sp<Surface> surface_sp;
        surface_sp.ptr = reinterpret_cast<Surface *>(surface);

        // Call setDisplaySurface
        // Signature: Transaction& setDisplaySurface(const sp<IBinder>&, const sp<IGraphicBufferProducer>&)
        using SetDisplaySurfaceFn = void *(*)(void *, const sp<IBinder> &, const sp<Surface> &);
        auto fn = reinterpret_cast<SetDisplaySurfaceFn>(set_display_surface_fn_);

        fn(transaction_obj_, token, surface_sp);

        FUTON_LOGD("set_display_surface: configured display=%p surface=%p",
                   display_token.ptr, surface);
        return true;
    }

    bool DisplayTransaction::set_display_projection(const DisplayToken &display_token,
                                                    const DisplayProjection &projection) {
        if (!initialized_ || !transaction_obj_) {
            FUTON_LOGE("set_display_projection: not initialized");
            return false;
        }

        if (!display_token.is_valid()) {
            FUTON_LOGE("set_display_projection: invalid display token");
            return false;
        }

        if (!set_display_projection_fn_) {
            FUTON_LOGW("set_display_projection: function not available");
            return false;
        }

        // Prepare sp<IBinder> for display token
        sp<IBinder> token;
        token.ptr = reinterpret_cast<IBinder *>(display_token.ptr);

        // Prepare Rect structures
        AndroidRect source_rect = {
                projection.source_rect.left,
                projection.source_rect.top,
                projection.source_rect.right,
                projection.source_rect.bottom
        };
        AndroidRect dest_rect = {
                projection.dest_rect.left,
                projection.dest_rect.top,
                projection.dest_rect.right,
                projection.dest_rect.bottom
        };

        // Call setDisplayProjection
        // Signature varies by Android version:
        // Android 12+: Transaction& setDisplayProjection(const sp<IBinder>&, ui::Rotation, const Rect&, const Rect&)
        // Android 11: Transaction& setDisplayProjection(const sp<IBinder>&, int, const Rect&, const Rect&)
        using SetDisplayProjectionFn = void *(*)(void *, const sp<IBinder> &, int32_t,
                                                 const AndroidRect &, const AndroidRect &);
        auto fn = reinterpret_cast<SetDisplayProjectionFn>(set_display_projection_fn_);

        fn(transaction_obj_, token, static_cast<int32_t>(projection.orientation),
           source_rect, dest_rect);

        FUTON_LOGD("set_display_projection: source=(%d,%d,%d,%d) dest=(%d,%d,%d,%d) orientation=%d",
                   source_rect.left, source_rect.top, source_rect.right, source_rect.bottom,
                   dest_rect.left, dest_rect.top, dest_rect.right, dest_rect.bottom,
                   static_cast<int>(projection.orientation));
        return true;
    }

    bool DisplayTransaction::configure_display(const DisplayToken &display_token,
                                               void *surface,
                                               uint32_t source_width, uint32_t source_height,
                                               uint32_t dest_width, uint32_t dest_height) {
        if (!initialized_) {
            FUTON_LOGE("configure_display: not initialized");
            return false;
        }

        FUTON_LOGI("configure_display: source=%ux%u dest=%ux%u",
                   source_width, source_height, dest_width, dest_height);

        // Set display surface
        if (surface && set_display_surface_fn_) {
            if (!set_display_surface(display_token, surface)) {
                FUTON_LOGW("configure_display: setDisplaySurface failed (continuing)");
            }
        }

        // Set display projection
        if (set_display_projection_fn_) {
            DisplayProjection projection;
            projection.source_rect = Rect(0, 0, source_width, source_height);
            projection.dest_rect = Rect(0, 0, dest_width, dest_height);
            projection.orientation = DisplayOrientation::ROTATION_0;

            if (!set_display_projection(display_token, projection)) {
                FUTON_LOGW("configure_display: setDisplayProjection failed (continuing)");
            }
        }

        // Apply the transaction
        return apply();
    }

    bool DisplayTransaction::apply() {
        if (!initialized_ || !transaction_obj_) {
            FUTON_LOGE("apply: not initialized");
            return false;
        }

        if (!apply_fn_) {
            FUTON_LOGE("apply: function not available");
            return false;
        }

        // Call apply()
        // Signature: status_t apply(bool synchronous = false)
        using ApplyFn = int32_t(*)(void *, bool);
        auto fn = reinterpret_cast<ApplyFn>(apply_fn_);

        int32_t status = fn(transaction_obj_, false);

        if (status != 0) {
            FUTON_LOGE("apply: failed with status %d", status);
            return false;
        }

        FUTON_LOGD("apply: transaction applied successfully");
        return true;
    }

} // namespace futon::vision
