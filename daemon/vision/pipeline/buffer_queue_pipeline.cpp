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

#include "vision/pipeline/buffer_queue_pipeline.h"
#include "vision/pipeline/gl_consumer_wrapper.h"
#include "vision/display/display_transaction.h"
#include "vision/display/display_adapter.h"
#include "core/error.h"
#include "vision/loader/elf_symbol_scanner.h"

#include <dlfcn.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <chrono>
#include <cstring>
#include <new>
#include <regex>

using namespace futon::core;

namespace futon::vision {

// Android sp<T> smart pointer structure (simplified)
    template<typename T>
    struct sp {
        T *ptr;

        sp() : ptr(nullptr) {}

        explicit sp(T *p) : ptr(p) {}

        T *get() const { return ptr; }

        explicit operator bool() const { return ptr != nullptr; }
    };

// Verify sp<T> is pointer-sized for ABI compatibility
    static_assert(sizeof(sp<void>) == sizeof(void *), "sp<T> must be pointer-sized");

/**
 * Symbol name patterns for BufferQueue APIs.
 * These are mangled C++ symbols that vary across Android versions.
 */

// BufferQueue::createBufferQueue variants
// Android 12+: _ZN7android11BufferQueue17createBufferQueueEPNS_2spINS_22IGraphicBufferProducerEEEPNS1_INS_22IGraphicBufferConsumerEEEb
// Android 11:  _ZN7android11BufferQueue17createBufferQueueEPNS_2spINS_22IGraphicBufferProducerEEEPNS1_INS_22IGraphicBufferConsumerEEE
    static const char *kCreateBufferQueueSymbols[] = {
            // Android 12+ with allocator parameter
            "_ZN7android11BufferQueue17createBufferQueueEPNS_2spINS_22IGraphicBufferProducerEEEPNS1_INS_22IGraphicBufferConsumerEEEb",
            // Android 11 without allocator
            "_ZN7android11BufferQueue17createBufferQueueEPNS_2spINS_22IGraphicBufferProducerEEEPNS1_INS_22IGraphicBufferConsumerEEE",
            // Alternative mangling
            "_ZN7android11BufferQueue17createBufferQueueEPNS_2spINS_22IGraphicBufferProducerEEEPNS1_INS_22IGraphicBufferConsumerEEENS_2wpINS_22IGraphicBufferAllocatorEEE",
    };
    static const size_t kCreateBufferQueueSymbolCount =
            sizeof(kCreateBufferQueueSymbols) / sizeof(kCreateBufferQueueSymbols[0]);

// GLConsumer::updateTexImage
    static const char *kUpdateTexImageSymbols[] = {
            "_ZN7android10GLConsumer14updateTexImageEv",
            "_ZN7android14SurfaceTexture14updateTexImageEv",  // Older name
    };
    static const size_t kUpdateTexImageSymbolCount =
            sizeof(kUpdateTexImageSymbols) / sizeof(kUpdateTexImageSymbols[0]);

// GLConsumer::getTransformMatrix
    static const char *kGetTransformMatrixSymbols[] = {
            "_ZNK7android10GLConsumer18getTransformMatrixEPf",
            "_ZNK7android14SurfaceTexture18getTransformMatrixEPf",
    };
    static const size_t kGetTransformMatrixSymbolCount =
            sizeof(kGetTransformMatrixSymbols) / sizeof(kGetTransformMatrixSymbols[0]);

// GLConsumer::getTimestamp
    static const char *kGetTimestampSymbols[] = {
            "_ZNK7android10GLConsumer12getTimestampEv",
            "_ZNK7android14SurfaceTexture12getTimestampEv",
    };
    static const size_t kGetTimestampSymbolCount =
            sizeof(kGetTimestampSymbols) / sizeof(kGetTimestampSymbols[0]);

// GLConsumer::releaseTexImage
    static const char *kReleaseTexImageSymbols[] = {
            "_ZN7android10GLConsumer15releaseTexImageEv",
            "_ZN7android14SurfaceTexture15releaseTexImageEv",
    };
    static const size_t kReleaseTexImageSymbolCount =
            sizeof(kReleaseTexImageSymbols) / sizeof(kReleaseTexImageSymbols[0]);

// Surface constructor
    static const char *kSurfaceCtorSymbols[] = {
            // Surface(sp<IGraphicBufferProducer>&, bool)
            "_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEb",
            "_ZN7android7SurfaceC2ERKNS_2spINS_22IGraphicBufferProducerEEEb",
    };
    static const size_t kSurfaceCtorSymbolCount =
            sizeof(kSurfaceCtorSymbols) / sizeof(kSurfaceCtorSymbols[0]);

// Surface destructor
    static const char *kSurfaceDtorSymbols[] = {
            "_ZN7android7SurfaceD1Ev",
            "_ZN7android7SurfaceD2Ev",
            "_ZN7android7SurfaceD0Ev",
    };
    static const size_t kSurfaceDtorSymbolCount =
            sizeof(kSurfaceDtorSymbols) / sizeof(kSurfaceDtorSymbols[0]);


    static int get_device_api_level() {
        char value[PROP_VALUE_MAX] = {0};
        int len = __system_property_get("ro.build.version.sdk", value);
        if (len > 0) {
            return atoi(value);
        }
        return 30;  // Default to Android 11
    }


    BufferQueuePipeline::BufferQueuePipeline() {
        memset(transform_matrix_, 0, sizeof(transform_matrix_));
        // Identity matrix
        transform_matrix_[0] = 1.0f;
        transform_matrix_[5] = 1.0f;
        transform_matrix_[10] = 1.0f;
        transform_matrix_[15] = 1.0f;
    }

    BufferQueuePipeline::~BufferQueuePipeline() {
        shutdown();
    }

    bool BufferQueuePipeline::is_available() {
        void *handle = dlopen("libgui.so", RTLD_NOW | RTLD_NOLOAD);
        if (!handle) {
            handle = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            return false;
        }

        // Check for createBufferQueue symbol
        for (size_t i = 0; i < kCreateBufferQueueSymbolCount; i++) {
            void *sym = dlsym(handle, kCreateBufferQueueSymbols[i]);
            if (sym) {
                dlclose(handle);
                return true;
            }
        }

        dlclose(handle);
        return false;
    }

    bool BufferQueuePipeline::initialize(uint32_t width, uint32_t height) {
        if (initialized_) {
            FUTON_LOGW("BufferQueuePipeline: already initialized");
            return true;
        }

        if (width == 0 || height == 0) {
            FUTON_LOGE("BufferQueuePipeline: invalid dimensions %ux%u", width, height);
            return false;
        }

        width_ = width;
        height_ = height;

        FUTON_LOGI("BufferQueuePipeline: initializing %ux%u", width, height);

        // Load symbols from libgui.so
        if (!load_symbols()) {
            FUTON_LOGE("BufferQueuePipeline: failed to load symbols");
            return false;
        }

        // Create OpenGL texture for GL_TEXTURE_EXTERNAL_OES
        glGenTextures(1, &texture_id_);
        if (texture_id_ == 0) {
            FUTON_LOGE("BufferQueuePipeline: failed to create texture");
            shutdown();
            return false;
        }
        FUTON_LOGD("Created GL texture: %u", texture_id_);

        // Create BufferQueue (producer + consumer)
        if (!create_buffer_queue()) {
            FUTON_LOGE("BufferQueuePipeline: failed to create BufferQueue");
            shutdown();
            return false;
        }

        // Create GLConsumer wrapping the consumer
        if (!create_gl_consumer()) {
            FUTON_LOGE("BufferQueuePipeline: failed to create GLConsumer");
            shutdown();
            return false;
        }

        // Create Surface from producer for virtual display connection
        if (!create_producer_surface()) {
            FUTON_LOGE("BufferQueuePipeline: failed to create producer Surface");
            shutdown();
            return false;
        }

        // Setup frame available listener
        setup_frame_listener();

        initialized_ = true;
        FUTON_LOGI("BufferQueuePipeline: initialized successfully");
        return true;
    }

    void BufferQueuePipeline::shutdown() {
        if (!initialized_ && texture_id_ == 0) {
            return;
        }

        FUTON_LOGI("BufferQueuePipeline: shutting down (frames: %lu)",
                   static_cast<unsigned long>(frame_count_.load()));

        // Clear callback
        {
            std::lock_guard<std::mutex> lock(callback_mutex_);
            frame_callback_ = nullptr;
        }

        // Release GLConsumerWrapper if we created one
        if (gl_consumer_wrapper_) {
            gl_consumer_wrapper_->shutdown();
            delete gl_consumer_wrapper_;
            gl_consumer_wrapper_ = nullptr;
            gl_consumer_ = nullptr;
        } else if (gl_consumer_) {
            // Direct consumer mode - just clear pointer
            gl_consumer_ = nullptr;
        }

        // Release Surface if we created one (not just using producer directly)
        if (producer_surface_ && producer_surface_ != buffer_producer_) {
            // Call Surface destructor if available
            if (symbols_.surface_dtor) {
                using SurfaceDtorFn = void (*)(void *self);
                auto dtor = reinterpret_cast<SurfaceDtorFn>(symbols_.surface_dtor);
                FUTON_LOGD("Calling Surface destructor at %p", producer_surface_);
                dtor(producer_surface_);
            }
            // Free the allocated memory
            FUTON_LOGD("Freeing Surface memory at %p", producer_surface_);
            free(producer_surface_);
        }
        producer_surface_ = nullptr;

        // Release BufferQueue components
        buffer_producer_ = nullptr;
        buffer_consumer_ = nullptr;

        // Delete texture
        if (texture_id_ != 0) {
            glDeleteTextures(1, &texture_id_);
            texture_id_ = 0;
        }

        // Close library handle
        if (symbols_.libgui_handle) {
            dlclose(symbols_.libgui_handle);
            symbols_ = BufferQueueSymbols{};
        }

        initialized_ = false;
        width_ = 0;
        height_ = 0;
        frame_count_ = 0;
        transform_valid_ = false;
    }

    bool BufferQueuePipeline::load_symbols() {
        // Load libgui.so
        symbols_.libgui_handle = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
        if (!symbols_.libgui_handle) {
            symbols_.libgui_handle = dlopen("/system/lib64/libgui.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!symbols_.libgui_handle) {
            FUTON_LOGE("Failed to load libgui.so: %s", dlerror());
            return false;
        }
        FUTON_LOGI("Loaded libgui.so");

        // Resolve BufferQueue symbols
        if (!resolve_buffer_queue_symbols()) {
            FUTON_LOGE("Failed to resolve BufferQueue symbols");
            return false;
        }

        // Resolve GLConsumer symbols
        if (!resolve_gl_consumer_symbols()) {
            FUTON_LOGE("Failed to resolve GLConsumer symbols");
            return false;
        }

        // Resolve Surface symbols
        if (!resolve_surface_symbols()) {
            FUTON_LOGW("Failed to resolve Surface symbols (optional)");
            // Surface creation is optional - we can use producer directly
        }

        return symbols_.is_loaded();
    }

    bool BufferQueuePipeline::resolve_buffer_queue_symbols() {
        int api_level = get_device_api_level();

        // Try each createBufferQueue variant
        for (size_t i = 0; i < kCreateBufferQueueSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kCreateBufferQueueSymbols[i]);
            if (sym) {
                symbols_.create_buffer_queue_fn = sym;
                symbols_.create_buffer_queue_api_level = api_level;

                // Check if this variant has allocator parameter (Android 12+)
                // The symbol with 'b' at the end has bool parameter
                const char *sym_name = kCreateBufferQueueSymbols[i];
                symbols_.has_allocator_param = (sym_name[strlen(sym_name) - 1] == 'b');

                FUTON_LOGI("Resolved createBufferQueue: %s (allocator=%d)",
                           sym_name, symbols_.has_allocator_param);
                return true;
            }
        }

        FUTON_LOGE("createBufferQueue symbol not found");
        return false;
    }

    bool BufferQueuePipeline::resolve_gl_consumer_symbols() {
        // updateTexImage
        for (size_t i = 0; i < kUpdateTexImageSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kUpdateTexImageSymbols[i]);
            if (sym) {
                symbols_.gl_consumer_update_tex_image = sym;
                FUTON_LOGD("Resolved updateTexImage: %s", kUpdateTexImageSymbols[i]);
                break;
            }
        }

        // getTransformMatrix
        for (size_t i = 0; i < kGetTransformMatrixSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kGetTransformMatrixSymbols[i]);
            if (sym) {
                symbols_.gl_consumer_get_transform_matrix = sym;
                FUTON_LOGD("Resolved getTransformMatrix: %s", kGetTransformMatrixSymbols[i]);
                break;
            }
        }

        // getTimestamp
        for (size_t i = 0; i < kGetTimestampSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kGetTimestampSymbols[i]);
            if (sym) {
                symbols_.gl_consumer_get_timestamp = sym;
                FUTON_LOGD("Resolved getTimestamp: %s", kGetTimestampSymbols[i]);
                break;
            }
        }

        // releaseTexImage
        for (size_t i = 0; i < kReleaseTexImageSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kReleaseTexImageSymbols[i]);
            if (sym) {
                symbols_.gl_consumer_release_tex_image = sym;
                FUTON_LOGD("Resolved releaseTexImage: %s", kReleaseTexImageSymbols[i]);
                break;
            }
        }

        // updateTexImage is required
        return symbols_.gl_consumer_update_tex_image != nullptr;
    }

    bool BufferQueuePipeline::resolve_surface_symbols() {
        // Resolve Surface constructor
        for (size_t i = 0; i < kSurfaceCtorSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kSurfaceCtorSymbols[i]);
            if (sym) {
                symbols_.surface_ctor = sym;
                FUTON_LOGD("Resolved Surface ctor: %s", kSurfaceCtorSymbols[i]);
                break;
            }
        }

        // Resolve Surface destructor
        for (size_t i = 0; i < kSurfaceDtorSymbolCount; i++) {
            void *sym = dlsym(symbols_.libgui_handle, kSurfaceDtorSymbols[i]);
            if (sym) {
                symbols_.surface_dtor = sym;
                FUTON_LOGD("Resolved Surface dtor: %s", kSurfaceDtorSymbols[i]);
                break;
            }
        }

        return symbols_.surface_ctor != nullptr;
    }

    bool BufferQueuePipeline::create_buffer_queue() {
        if (!symbols_.create_buffer_queue_fn) {
            FUTON_LOGE("create_buffer_queue: symbol not available");
            return false;
        }

        FUTON_LOGI("Creating BufferQueue...");

        // Allocate sp<T> structures for output parameters
        sp<IGraphicBufferProducer> producer;
        sp<IGraphicBufferConsumer> consumer;

        if (symbols_.has_allocator_param) {
            // Android 12+: void createBufferQueue(sp<Producer>*, sp<Consumer>*, bool)
            using CreateBufferQueueFn = void (*)(sp<IGraphicBufferProducer> *,
                                                 sp<IGraphicBufferConsumer> *,
                                                 bool);
            auto fn = reinterpret_cast<CreateBufferQueueFn>(symbols_.create_buffer_queue_fn);

            FUTON_LOGD("Calling createBufferQueue (Android 12+ variant)");
            fn(&producer, &consumer, false);  // false = don't use allocator
        } else {
            // Android 11: void createBufferQueue(sp<Producer>*, sp<Consumer>*)
            using CreateBufferQueueFn = void (*)(sp<IGraphicBufferProducer> *,
                                                 sp<IGraphicBufferConsumer> *);
            auto fn = reinterpret_cast<CreateBufferQueueFn>(symbols_.create_buffer_queue_fn);

            FUTON_LOGD("Calling createBufferQueue (Android 11 variant)");
            fn(&producer, &consumer);
        }

        if (!producer.ptr || !consumer.ptr) {
            FUTON_LOGE("createBufferQueue returned null (producer=%p, consumer=%p)",
                       producer.ptr, consumer.ptr);
            return false;
        }

        buffer_producer_ = producer.ptr;
        buffer_consumer_ = consumer.ptr;

        FUTON_LOGI("BufferQueue created: producer=%p, consumer=%p",
                   buffer_producer_, buffer_consumer_);
        return true;
    }


    bool BufferQueuePipeline::create_gl_consumer() {
        if (!buffer_consumer_) {
            FUTON_LOGE("create_gl_consumer: no consumer available");
            return false;
        }

        FUTON_LOGI("Creating GLConsumer with texture %u...", texture_id_);


        // Load GLConsumer symbols if not already loaded
        if (!GLConsumerWrapper::load_symbols(symbols_.libgui_handle)) {
            FUTON_LOGW("create_gl_consumer: GLConsumerWrapper symbols not available");
            FUTON_LOGW("  Falling back to direct consumer access");
            gl_consumer_ = buffer_consumer_;
            return true;
        }

        // Allocate GLConsumerWrapper
        auto *wrapper = new(std::nothrow) GLConsumerWrapper();
        if (!wrapper) {
            FUTON_LOGE("create_gl_consumer: failed to allocate GLConsumerWrapper");
            return false;
        }

        // Initialize GLConsumer with:
        // - consumer: IGraphicBufferConsumer from BufferQueue
        // - texture_id: Our GL texture
        // - use_fence_sync: true for proper GPU synchronization
        bool use_fence_sync = true;

        if (!wrapper->initialize(buffer_consumer_, texture_id_, use_fence_sync)) {
            FUTON_LOGE("create_gl_consumer: GLConsumerWrapper initialization failed");
            delete wrapper;

            // Fallback to direct consumer access
            FUTON_LOGW("  Falling back to direct consumer access");
            gl_consumer_ = buffer_consumer_;
            return true;
        }

        // Store the wrapper as our gl_consumer_
        // We'll need to cast back when calling methods
        gl_consumer_ = wrapper;
        gl_consumer_wrapper_ = wrapper;

        FUTON_LOGI("GLConsumer created successfully via GLConsumerWrapper");
        FUTON_LOGI("  Texture: %u (GL_TEXTURE_EXTERNAL_OES)", texture_id_);
        FUTON_LOGI("  Fence sync: %s", use_fence_sync ? "enabled" : "disabled");

        return true;
    }

    bool BufferQueuePipeline::create_producer_surface() {
        if (!buffer_producer_) {
            FUTON_LOGE("create_producer_surface: no producer available");
            return false;
        }

        FUTON_LOGI("Creating producer Surface...");

        /**
         * Surface creation:
         *
         * Android Surface is the ANativeWindow implementation that connects
         * BufferQueue producer to virtual display. Constructor signature:
         *
         * Surface(const sp<IGraphicBufferProducer>& bufferProducer, bool controlledByApp)
         *
         * Parameters:
         * - bufferProducer: Our BufferQueue producer
         * - controlledByApp: false - daemon controls, not app
         */

        if (!symbols_.surface_ctor) {
            // If Surface constructor symbol not available, use producer directly.
            // Virtual display can use IGraphicBufferProducer via setDisplaySurface.
            FUTON_LOGW("create_producer_surface: Surface ctor not available");
            FUTON_LOGW(
                    "  Using producer directly (setDisplaySurface will use IGraphicBufferProducer)");
            producer_surface_ = buffer_producer_;
            return true;
        }

        // Surface object size varies by Android version.
        // Typical size is 256-512 bytes, allocate conservatively.
        const size_t kSurfaceSize = 1024;

        // Allocate memory for Surface object
        void *surface_mem = malloc(kSurfaceSize);
        if (!surface_mem) {
            FUTON_LOGE("create_producer_surface: failed to allocate memory");
            // Fallback to using producer directly
            producer_surface_ = buffer_producer_;
            return true;
        }
        memset(surface_mem, 0, kSurfaceSize);

        // Prepare sp<IGraphicBufferProducer> wrapper
        sp<IGraphicBufferProducer> producer_sp;
        producer_sp.ptr = reinterpret_cast<IGraphicBufferProducer *>(buffer_producer_);

        // Call Surface constructor
        // Signature: Surface(const sp<IGraphicBufferProducer>& bufferProducer, bool controlledByApp)
        //
        // ARM64 ABI:
        // - x0: this pointer (surface_mem)
        // - x1: &producer_sp (reference to sp<IGraphicBufferProducer>)
        // - w2: controlledByApp (false)

        using SurfaceCtorFn = void (*)(void *self,
                                       const sp<IGraphicBufferProducer> &producer,
                                       bool controlledByApp);

        auto ctor = reinterpret_cast<SurfaceCtorFn>(symbols_.surface_ctor);

        FUTON_LOGD("Calling Surface constructor:");
        FUTON_LOGD("  this=%p", surface_mem);
        FUTON_LOGD("  producer=%p", buffer_producer_);
        FUTON_LOGD("  controlledByApp=false");

        // Call constructor
        ctor(surface_mem, producer_sp, false);

        producer_surface_ = surface_mem;

        FUTON_LOGI("Surface created at %p", producer_surface_);
        FUTON_LOGI("  Connected to producer: %p", buffer_producer_);

        return true;
    }

    void BufferQueuePipeline::setup_frame_listener() {
        /**
         * setFrameAvailableListener callback settings:
         *
         * GLConsumer supports frame available notifications via:
         * setFrameAvailableListener(sp<FrameAvailableListener>&)
         *
         * When a new frame is available in the BufferQueue, the listener's
         * onFrameAvailable() method is called.
         */

        if (gl_consumer_wrapper_) {
            // Use GLConsumerWrapper's callback mechanism
            gl_consumer_wrapper_->set_frame_available_callback([this]() {
                // Set pending flag for polling
                frame_pending_.store(true);

                // Forward to user callback
                std::lock_guard<std::mutex> lock(callback_mutex_);
                if (frame_callback_) {
                    frame_callback_();
                }
            });
            FUTON_LOGI("Frame listener: callback registered via GLConsumerWrapper");
        } else {
            // Fallback: polling mode
            FUTON_LOGD("Frame listener: using polling mode (callback not available)");
        }
    }

    void BufferQueuePipeline::set_frame_available_callback(FrameAvailableCallback callback) {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        frame_callback_ = std::move(callback);
    }

    bool BufferQueuePipeline::update_tex_image() {
        if (!initialized_ || !gl_consumer_) {
            return false;
        }

        // Use GLConsumerWrapper if available
        if (gl_consumer_wrapper_) {
            bool result = gl_consumer_wrapper_->update_tex_image();
            if (result) {
                transform_valid_ = false;
                frame_count_++;
            }
            return result;
        }

        // Fallback to direct symbol call
        if (!symbols_.gl_consumer_update_tex_image) {
            FUTON_LOGE("updateTexImage symbol not available");
            return false;
        }

        // Bind the external texture
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture_id_);

        // Call GLConsumer::updateTexImage()
        using UpdateTexImageFn = int (*)(void *self);
        auto fn = reinterpret_cast<UpdateTexImageFn>(symbols_.gl_consumer_update_tex_image);

        int result = fn(gl_consumer_);

        if (result != 0) {
            return false;
        }

        transform_valid_ = false;
        frame_count_++;

        return true;
    }

    void BufferQueuePipeline::get_transform_matrix(float *matrix) const {
        if (!matrix) {
            return;
        }

        // Use GLConsumerWrapper if available
        if (gl_consumer_wrapper_) {
            gl_consumer_wrapper_->get_transform_matrix(matrix);
            return;
        }

        // Fallback to direct symbol call
        if (!transform_valid_ && gl_consumer_ && symbols_.gl_consumer_get_transform_matrix) {
            using GetTransformMatrixFn = void (*)(const void *self, float *matrix);
            auto fn = reinterpret_cast<GetTransformMatrixFn>(symbols_.gl_consumer_get_transform_matrix);
            fn(gl_consumer_, transform_matrix_);
            transform_valid_ = true;
        }

        memcpy(matrix, transform_matrix_, sizeof(transform_matrix_));
    }

    const float *BufferQueuePipeline::get_transform_matrix() const {
        // Update cached matrix if needed
        if (!transform_valid_) {
            if (gl_consumer_wrapper_) {
                gl_consumer_wrapper_->get_transform_matrix(transform_matrix_);
                transform_valid_ = true;
            } else if (gl_consumer_ && symbols_.gl_consumer_get_transform_matrix) {
                using GetTransformMatrixFn = void (*)(const void *self, float *matrix);
                auto fn = reinterpret_cast<GetTransformMatrixFn>(symbols_.gl_consumer_get_transform_matrix);
                fn(gl_consumer_, transform_matrix_);
                transform_valid_ = true;
            }
        }
        return transform_valid_ ? transform_matrix_ : nullptr;
    }

    int64_t BufferQueuePipeline::get_timestamp() const {
        // Use GLConsumerWrapper if available
        if (gl_consumer_wrapper_) {
            return gl_consumer_wrapper_->get_timestamp();
        }

        // Fallback to direct symbol call
        if (!gl_consumer_ || !symbols_.gl_consumer_get_timestamp) {
            return 0;
        }

        using GetTimestampFn = int64_t(*)(const void *self);
        auto fn = reinterpret_cast<GetTimestampFn>(symbols_.gl_consumer_get_timestamp);
        return fn(gl_consumer_);
    }

    void BufferQueuePipeline::release_tex_image() {
        // Use GLConsumerWrapper if available
        if (gl_consumer_wrapper_) {
            gl_consumer_wrapper_->release_tex_image();
            return;
        }

        // Fallback to direct symbol call
        if (!gl_consumer_ || !symbols_.gl_consumer_release_tex_image) {
            return;
        }

        using ReleaseTexImageFn = void (*)(void *self);
        auto fn = reinterpret_cast<ReleaseTexImageFn>(symbols_.gl_consumer_release_tex_image);
        fn(gl_consumer_);
    }

    bool BufferQueuePipeline::connect_to_display(void *display_token,
                                                 uint32_t source_width,
                                                 uint32_t source_height) {
        /**
         * Connect the BufferQueue producer to a virtual display.
         */

        if (!initialized_) {
            FUTON_LOGE("connect_to_display: pipeline not initialized");
            return false;
        }

        if (!display_token) {
            FUTON_LOGE("connect_to_display: null display token");
            return false;
        }

        if (connected_to_display_) {
            if (connected_display_token_ == display_token) {
                FUTON_LOGW("connect_to_display: already connected to this display");
                return true;
            }
            FUTON_LOGW("connect_to_display: disconnecting from previous display");
            disconnect_from_display();
        }

        FUTON_LOGI("Connecting BufferQueue to virtual display...");
        FUTON_LOGI("  Display token: %p", display_token);
        FUTON_LOGI("  Source: %ux%u", source_width, source_height);
        FUTON_LOGI("  Destination: %ux%u", width_, height_);
        FUTON_LOGI("  Producer surface: %p", producer_surface_);
        FUTON_LOGI("  Buffer producer: %p", buffer_producer_);

        // Initialize DisplayTransaction for configuring the display
        DisplayTransaction transaction;
        if (!transaction.initialize()) {
            FUTON_LOGE("connect_to_display: failed to initialize DisplayTransaction");
            return false;
        }

        // Create DisplayToken wrapper
        DisplayToken token;
        token.ptr = display_token;

        // Determine which surface/producer to use
        // Priority: producer_surface_ (Surface object) > buffer_producer_ (IGraphicBufferProducer)
        void *surface_for_display = producer_surface_;
        if (!surface_for_display) {
            surface_for_display = buffer_producer_;
        }

        if (!surface_for_display) {
            FUTON_LOGE("connect_to_display: no surface or producer available");
            return false;
        }

        // Configure the display with our surface and projection
        bool success = transaction.configure_display(
                token,
                surface_for_display,
                source_width, source_height,  // Source: physical screen dimensions
                width_, height_               // Destination: our buffer dimensions
        );

        if (!success) {
            FUTON_LOGE("connect_to_display: configure_display failed");
            return false;
        }

        connected_to_display_ = true;
        connected_display_token_ = display_token;

        FUTON_LOGI("BufferQueue connected to virtual display successfully");
        FUTON_LOGI("  SurfaceFlinger will now composite frames into our BufferQueue");
        FUTON_LOGI("  Frames available via updateTexImage() as GL_TEXTURE_EXTERNAL_OES");

        return true;
    }

    void BufferQueuePipeline::disconnect_from_display() {
        if (!connected_to_display_) {
            return;
        }

        FUTON_LOGI("Disconnecting BufferQueue from virtual display...");

        // To properly disconnect, we would need to:
        // 1. Create a new Transaction
        // 2. Call setDisplaySurface with nullptr
        // 3. Apply the transaction
        //
        // However, in practice, destroying the virtual display handles cleanup.
        // We just clear our state here.

        if (connected_display_token_) {
            DisplayTransaction transaction;
            if (transaction.initialize()) {
                DisplayToken token;
                token.ptr = connected_display_token_;

                // Set display surface to null to disconnect
                // Note: This may not be strictly necessary if the display is being destroyed
                transaction.set_display_surface(token, nullptr);
                transaction.apply();

                FUTON_LOGD("Cleared display surface binding");
            }
        }

        connected_to_display_ = false;
        connected_display_token_ = nullptr;

        FUTON_LOGI("BufferQueue disconnected from virtual display");
    }

    bool BufferQueuePipeline::acquire_frame(GLuint *out_texture_id,
                                            int64_t *out_timestamp_ns,
                                            float *out_transform) {
        /**
         * Acquire a frame from the BufferQueue.
         */

        if (!initialized_) {
            FUTON_LOGE("acquire_frame: pipeline not initialized");
            return false;
        }

        if (!connected_to_display_) {
            FUTON_LOGW("acquire_frame: not connected to display");
            // Still try to acquire - might have frames from before disconnect
        }

        // Call updateTexImage() to get the latest frame
        // This makes the most recent buffer available as a GL texture
        bool got_frame = update_tex_image();

        if (!got_frame) {
            // No new frame available
            // This is normal - SurfaceFlinger may not have produced a new frame yet
            return false;
        }

        // Clear pending flag since we consumed the frame
        frame_pending_.store(false);

        // Return texture ID
        if (out_texture_id) {
            *out_texture_id = texture_id_;
        }

        // Return timestamp
        if (out_timestamp_ns) {
            *out_timestamp_ns = get_timestamp();
        }

        // Return transform matrix if requested
        if (out_transform) {
            get_transform_matrix(out_transform);
        }

        FUTON_LOGD("Frame acquired: texture=%u, timestamp=%lld, frame#=%lu",
                   texture_id_,
                   static_cast<long long>(out_timestamp_ns ? *out_timestamp_ns : 0),
                   static_cast<unsigned long>(frame_count_.load()));

        return true;
    }

    bool BufferQueuePipeline::acquire_frame_timeout(GLuint *out_texture_id,
                                                    int64_t *out_timestamp_ns,
                                                    int timeout_ms,
                                                    float *out_transform) {
        /**
         * Try to acquire a frame with timeout.
         *
         * Polls for a new frame up to the specified timeout.
         * Useful when:
         * - Waiting for the first frame after connecting to display
         * - Synchronizing with a specific frame rate
         * - Ensuring we don't block indefinitely
         */

        if (!initialized_) {
            FUTON_LOGE("acquire_frame_timeout: pipeline not initialized");
            return false;
        }

        // Calculate deadline
        auto start = std::chrono::steady_clock::now();
        auto deadline = start + std::chrono::milliseconds(timeout_ms);

        // Poll interval - start fast, slow down over time
        int poll_interval_us = 1000;  // Start at 1ms
        const int max_poll_interval_us = 16000;  // Max 16ms (roughly 60Hz)

        while (std::chrono::steady_clock::now() < deadline) {
            // Try to acquire frame
            if (acquire_frame(out_texture_id, out_timestamp_ns, out_transform)) {
                auto elapsed = std::chrono::steady_clock::now() - start;
                auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        elapsed).count();
                FUTON_LOGD("Frame acquired after %lld ms", static_cast<long long>(elapsed_ms));
                return true;
            }

            // Check if we have a pending frame notification
            if (frame_pending_.load()) {
                // Frame should be available, try again immediately
                continue;
            }

            // Sleep before next poll
            usleep(poll_interval_us);

            // Increase poll interval (exponential backoff)
            poll_interval_us = std::min(poll_interval_us * 2, max_poll_interval_us);
        }

        FUTON_LOGW("acquire_frame_timeout: timeout after %d ms", timeout_ms);
        return false;
    }

    bool BufferQueuePipeline::has_pending_frame() const {
        return frame_pending_.load();
    }

} // namespace futon::vision
