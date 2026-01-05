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

#include "vision/capture/virtual_display.h"
#include "vision/loader/surface_control_loader.h"
#include "vision/display/display_adapter.h"
#include "vision/display/display_transaction.h"
#include "vision/loader/elf_symbol_scanner.h"
#include "core/error.h"

#include <cstring>

using namespace futon::core;

namespace futon::vision {

// Static members
    std::unique_ptr<SurfaceControlLoader> VirtualDisplay::s_loader_;
    std::unique_ptr<DisplayAdapter> VirtualDisplay::s_adapter_;
    bool VirtualDisplay::s_initialized_ = false;

    VirtualDisplay::VirtualDisplay() = default;

    VirtualDisplay::~VirtualDisplay() {
        destroy();
    }

    VirtualDisplay::VirtualDisplay(VirtualDisplay &&other) noexcept
            : display_token_(other.display_token_), surface_(other.surface_),
              buffer_producer_(other.buffer_producer_), width_(other.width_),
              height_(other.height_), flags_(other.flags_), name_(std::move(other.name_)) {
        other.display_token_ = DisplayToken{};
        other.surface_ = nullptr;
        other.buffer_producer_ = nullptr;
        other.width_ = 0;
        other.height_ = 0;
        other.flags_ = 0;
    }

    VirtualDisplay &VirtualDisplay::operator=(VirtualDisplay &&other) noexcept {
        if (this != &other) {
            destroy();
            display_token_ = other.display_token_;
            surface_ = other.surface_;
            buffer_producer_ = other.buffer_producer_;
            width_ = other.width_;
            height_ = other.height_;
            flags_ = other.flags_;
            name_ = std::move(other.name_);
            other.display_token_ = DisplayToken{};
            other.surface_ = nullptr;
            other.buffer_producer_ = nullptr;
            other.width_ = 0;
            other.height_ = 0;
            other.flags_ = 0;
        }
        return *this;
    }

    SurfaceControlLoader &VirtualDisplay::get_loader() {
        if (!s_loader_) {
            s_loader_ = std::make_unique<SurfaceControlLoader>();
        }
        return *s_loader_;
    }

    DisplayAdapter &VirtualDisplay::get_adapter() {
        if (!s_adapter_) {
            s_adapter_ = std::make_unique<DisplayAdapter>();
        }
        return *s_adapter_;
    }

    bool VirtualDisplay::ensure_initialized() {
        if (s_initialized_) {
            return s_adapter_ && s_adapter_->is_initialized();
        }

        s_initialized_ = true;

        // Try the new DisplayAdapter first (uses ELF scanner)
        if (!s_adapter_) {
            s_adapter_ = std::make_unique<DisplayAdapter>();
        }

        if (s_adapter_->initialize_auto()) {
            FUTON_LOGI("VirtualDisplay: initialized with DisplayAdapter (%s)",
                       s_adapter_->get_description().c_str());
            return true;
        }

        FUTON_LOGW("VirtualDisplay: DisplayAdapter failed, trying legacy SurfaceControlLoader");

        // Fall back to legacy loader
        if (!s_loader_) {
            s_loader_ = std::make_unique<SurfaceControlLoader>();
        }

        if (s_loader_->load()) {
            FUTON_LOGI("VirtualDisplay: initialized with legacy SurfaceControlLoader");
            return true;
        }

        FUTON_LOGE("VirtualDisplay: all initialization methods failed");
        return false;
    }

    bool VirtualDisplay::create(uint32_t width, uint32_t height, const char *name) {
        // Default flags for screen mirroring:
        // - OWN_CONTENT_ONLY: Prevents picture-in-picture recursion
        // - AUTO_MIRROR: Automatically mirrors the main display
        uint32_t flags = VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                         VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        return create(width, height, flags, name);
    }

    bool VirtualDisplay::create(uint32_t width, uint32_t height, uint32_t flags, const char *name) {
        if (display_token_.is_valid()) {
            FUTON_LOGW("VirtualDisplay: destroying existing display before creating new one");
            destroy();
        }

        if (!ensure_initialized()) {
            FUTON_LOGE("VirtualDisplay: initialization failed");
            return false;
        }

        FUTON_LOGI("Creating virtual display: %ux%u flags=0x%x name=%s",
                   width, height, flags, name);
        FUTON_LOGI("  OWN_CONTENT_ONLY: %s",
                   (flags & VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) ? "yes" : "no");
        FUTON_LOGI("  AUTO_MIRROR: %s",
                   (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) ? "yes" : "no");

        // Try DisplayAdapter first
        if (s_adapter_ && s_adapter_->is_initialized()) {
            if (create_with_adapter(width, height, flags, name)) {
                return true;
            }
            FUTON_LOGW("VirtualDisplay: DisplayAdapter creation failed, trying legacy");
        }

        // Fall back to legacy loader
        if (s_loader_ && s_loader_->is_loaded()) {
            if (create_with_loader(width, height, flags, name)) {
                return true;
            }
        }

        FUTON_LOGE("VirtualDisplay: all creation methods failed");
        return false;
    }

    bool VirtualDisplay::create_with_adapter(uint32_t width, uint32_t height,
                                             uint32_t flags, const char *name) {
        CreateDisplayParams params;
        params.name = name;
        params.secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;
        params.receive_frame_used_exclusively = true;
        params.unique_id = "futon_" + std::string(name);
        params.requested_refresh_rate = 60.0f;

        display_token_ = s_adapter_->create_display(params);

        if (!display_token_.is_valid()) {
            FUTON_LOGE("create_with_adapter: failed to create display");
            return false;
        }

        width_ = width;
        height_ = height;
        flags_ = flags;
        name_ = name;

        FUTON_LOGI("VirtualDisplay created via DisplayAdapter: token=%p", display_token_.ptr);
        return true;
    }

    bool VirtualDisplay::create_with_loader(uint32_t width, uint32_t height,
                                            uint32_t flags, const char *name) {
        const auto &symbols = s_loader_->symbols();
        int api_level = s_loader_->resolver().get_api_level();

        bool success = false;
        if (symbols.use_virtual_display_api) {
            success = create_display_v16(width, height, flags, name);
        } else if (api_level >= 34) {
            success = create_display_v14(width, height, flags, name);
        } else {
            success = create_display_v11(width, height, flags, name);
        }

        if (success) {
            width_ = width;
            height_ = height;
            flags_ = flags;
            name_ = name;
            FUTON_LOGI("VirtualDisplay created via legacy loader");
        }

        return success;
    }


// Legacy creation methods (kept for fallback compatibility)

    bool VirtualDisplay::create_display_v16(uint32_t width, uint32_t height,
                                            uint32_t flags, const char *name) {
        const auto &symbols = s_loader_->symbols();

        if (!symbols.create_display_raw) {
            FUTON_LOGE("create_display_v16: symbol not available");
            return false;
        }

        std::string display_name(name);
        std::string unique_id = "futon_" + display_name;
        bool secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;
        bool receive_frame_used_exclusively = true;
        float requested_refresh_rate = 60.0f;

        auto fn = reinterpret_cast<CreateVirtualDisplayFn_v16>(symbols.create_display_raw);

        sp <IBinder> result;
        result.ptr = nullptr;

        fn(&result, display_name, secure, receive_frame_used_exclusively,
           unique_id, requested_refresh_rate);

        if (!result.ptr) {
            FUTON_LOGE("create_display_v16: createVirtualDisplay returned null");
            return false;
        }

        display_token_.ptr = result.ptr;
        return true;
    }

    bool VirtualDisplay::create_display_v14(uint32_t width, uint32_t height,
                                            uint32_t flags, const char *name) {
        const auto &symbols = s_loader_->symbols();

        if (!symbols.create_display_v14) {
            FUTON_LOGE("create_display_v14: symbol not available");
            return false;
        }

        // Create String8 for display name
        struct AndroidString8 {
            char data[256];
            size_t length;

            AndroidString8(const char *str) {
                length = strlen(str);
                if (length >= sizeof(data)) length = sizeof(data) - 1;
                memcpy(data, str, length);
                data[length] = '\0';
            }
        };

        AndroidString8 display_name(name);
        DisplayId display_id = 0;
        bool secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;

        sp <IBinder> result;
        result.ptr = nullptr;

        symbols.create_display_v14(
                &result,
                *reinterpret_cast<const String8 *>(&display_name),
                secure,
                display_id
        );

        if (!result.ptr) {
            FUTON_LOGE("create_display_v14: createDisplay returned null");
            return false;
        }

        display_token_.ptr = result.ptr;
        return true;
    }

    bool VirtualDisplay::create_display_v11(uint32_t width, uint32_t height,
                                            uint32_t flags, const char *name) {
        const auto &symbols = s_loader_->symbols();

        if (!symbols.create_display_v11) {
            FUTON_LOGE("create_display_v11: symbol not available");
            return false;
        }

        struct AndroidString8 {
            char data[256];
            size_t length;

            AndroidString8(const char *str) {
                length = strlen(str);
                if (length >= sizeof(data)) length = sizeof(data) - 1;
                memcpy(data, str, length);
                data[length] = '\0';
            }
        };

        AndroidString8 display_name(name);
        bool secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;

        sp <IBinder> result;
        result.ptr = nullptr;

        symbols.create_display_v11(
                &result,
                *reinterpret_cast<const String8 *>(&display_name),
                secure
        );

        if (!result.ptr) {
            FUTON_LOGE("create_display_v11: createDisplay returned null");
            return false;
        }

        display_token_.ptr = result.ptr;
        return true;
    }

    void VirtualDisplay::destroy() {
        if (!display_token_.is_valid()) {
            return;
        }

        // Try DisplayAdapter first
        if (s_adapter_ && s_adapter_->is_initialized()) {
            s_adapter_->destroy_display(display_token_);
        } else if (s_loader_ && s_loader_->is_loaded()) {
            // Legacy destroy
            const auto &symbols = s_loader_->symbols();
            if (symbols.destroy_display) {
                sp <IBinder> token;
                token.ptr = reinterpret_cast<IBinder *>(display_token_.ptr);
                symbols.destroy_display(token);
            }
        }

        FUTON_LOGD("Virtual display destroyed: %s", name_.c_str());

        display_token_ = DisplayToken{};
        surface_ = nullptr;
        buffer_producer_ = nullptr;
        width_ = 0;
        height_ = 0;
        flags_ = 0;
        name_.clear();
    }

    void *VirtualDisplay::get_physical_display_token() {
        if (!s_loader_ || !s_loader_->is_loaded()) {
            return nullptr;
        }

        const auto &symbols = s_loader_->symbols();
        int api_level = s_loader_->resolver().get_api_level();

        sp <IBinder> result;
        result.ptr = nullptr;

        if (symbols.use_internal_display_token && symbols.get_internal_display_token) {
            result = symbols.get_internal_display_token();
        } else if (symbols.use_built_in_display && symbols.get_built_in_display) {
            result = symbols.get_built_in_display(0);
        } else if (api_level >= 34 && symbols.get_physical_display_token_v14) {
            result = symbols.get_physical_display_token_v14(0);
        } else if (api_level >= 31 && symbols.get_physical_display_token_v12) {
            result = symbols.get_physical_display_token_v12(0);
        } else if (symbols.get_physical_display_token_v11) {
            result = symbols.get_physical_display_token_v11(0);
        }

        if (!result.ptr) {
            FUTON_LOGE("Failed to get physical display token");
        }

        return result.ptr;
    }

    bool VirtualDisplay::get_physical_display_config(DisplayConfig *config) {
        if (!config) {
            return false;
        }

        // Default display dimensions (fallback when binder not available)
        // In production, this should be obtained from the Android app via IPC
        FUTON_LOGW("get_physical_display_config: using default values");
        config->width = 1080;
        config->height = 2400;
        config->density_dpi = 420.0f;
        config->refresh_rate = 60.0f;
        return true;
    }

    bool VirtualDisplay::set_buffer_producer(void *producer,
                                             uint32_t source_width,
                                             uint32_t source_height) {
        /**
         * Connect a BufferQueue producer to this virtual display.
         * 
         * Uses DisplayTransaction to configure:
         * - setDisplaySurface: Connect the producer to the display
         * - setDisplayProjection: Map source screen to destination buffer
         */

        if (!display_token_.is_valid()) {
            FUTON_LOGE("set_buffer_producer: display not created");
            return false;
        }

        if (!producer) {
            FUTON_LOGE("set_buffer_producer: null producer");
            return false;
        }

        FUTON_LOGI("Setting buffer producer for virtual display...");
        FUTON_LOGI("  Display: %s (%ux%u)", name_.c_str(), width_, height_);
        FUTON_LOGI("  Producer: %p", producer);
        FUTON_LOGI("  Source: %ux%u", source_width, source_height);

        // Initialize DisplayTransaction
        DisplayTransaction transaction;
        if (!transaction.initialize()) {
            FUTON_LOGE("set_buffer_producer: failed to initialize DisplayTransaction");
            return false;
        }

        // Configure the display with the producer
        bool success = transaction.configure_display(
                display_token_,
                producer,
                source_width, source_height,  // Source: physical screen
                width_, height_               // Destination: our buffer
        );

        if (!success) {
            FUTON_LOGE("set_buffer_producer: configure_display failed");
            return false;
        }

        buffer_producer_ = producer;

        FUTON_LOGI("Buffer producer connected to virtual display");
        FUTON_LOGI("  SurfaceFlinger will composite frames into the BufferQueue");

        return true;
    }

} // namespace futon::vision
