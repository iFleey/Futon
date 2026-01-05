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

#include "vision/pipeline/gl_consumer_wrapper.h"
#include "core/error.h"

#include <dlfcn.h>
#include <sys/system_properties.h>
#include <cstring>
#include <new>

using namespace futon::core;

namespace futon::vision {

// Static member initialization
    GLConsumerSymbols GLConsumerWrapper::s_symbols_;
    bool GLConsumerWrapper::s_symbols_loaded_ = false;

/**
 * GLConsumer constructor symbol variants across Android versions.
 *
 * Android 11-13: GLConsumer(sp<IGraphicBufferConsumer>&, uint32_t tex, uint32_t texTarget, bool useFenceSync, bool isControlledByApp)
 * Android 14+: May have additional parameters or different mangling
 */
    static const char *kGLConsumerCtorSymbols[] = {
            // Android 12+ GLConsumer constructor
            "_ZN7android10GLConsumerC1ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbb",
            "_ZN7android10GLConsumerC2ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbb",
            // Android 11 variant
            "_ZN7android10GLConsumerC1ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbbb",
            "_ZN7android10GLConsumerC2ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbbb",
            // SurfaceTexture (older name)
            "_ZN7android14SurfaceTextureC1ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbb",
            "_ZN7android14SurfaceTextureC2ERKNS_2spINS_22IGraphicBufferConsumerEEEjjbb",
    };
    static const size_t kGLConsumerCtorSymbolCount =
            sizeof(kGLConsumerCtorSymbols) / sizeof(kGLConsumerCtorSymbols[0]);

// GLConsumer destructor
    static const char *kGLConsumerDtorSymbols[] = {
            "_ZN7android10GLConsumerD1Ev",
            "_ZN7android10GLConsumerD2Ev",
            "_ZN7android14SurfaceTextureD1Ev",
            "_ZN7android14SurfaceTextureD2Ev",
    };
    static const size_t kGLConsumerDtorSymbolCount =
            sizeof(kGLConsumerDtorSymbols) / sizeof(kGLConsumerDtorSymbols[0]);

// updateTexImage
    static const char *kUpdateTexImageSymbols[] = {
            "_ZN7android10GLConsumer14updateTexImageEv",
            "_ZN7android14SurfaceTexture14updateTexImageEv",
    };
    static const size_t kUpdateTexImageSymbolCount =
            sizeof(kUpdateTexImageSymbols) / sizeof(kUpdateTexImageSymbols[0]);

// releaseTexImage
    static const char *kReleaseTexImageSymbols[] = {
            "_ZN7android10GLConsumer15releaseTexImageEv",
            "_ZN7android14SurfaceTexture15releaseTexImageEv",
    };
    static const size_t kReleaseTexImageSymbolCount =
            sizeof(kReleaseTexImageSymbols) / sizeof(kReleaseTexImageSymbols[0]);

// getTransformMatrix
    static const char *kGetTransformMatrixSymbols[] = {
            "_ZNK7android10GLConsumer18getTransformMatrixEPf",
            "_ZNK7android14SurfaceTexture18getTransformMatrixEPf",
    };
    static const size_t kGetTransformMatrixSymbolCount =
            sizeof(kGetTransformMatrixSymbols) / sizeof(kGetTransformMatrixSymbols[0]);

// getTimestamp
    static const char *kGetTimestampSymbols[] = {
            "_ZNK7android10GLConsumer12getTimestampEv",
            "_ZNK7android14SurfaceTexture12getTimestampEv",
    };
    static const size_t kGetTimestampSymbolCount =
            sizeof(kGetTimestampSymbols) / sizeof(kGetTimestampSymbols[0]);

// setFrameAvailableListener
    static const char *kSetFrameAvailableListenerSymbols[] = {
            "_ZN7android10GLConsumer25setFrameAvailableListenerERKNS_2spINS_21FrameAvailableListenerEEE",
            "_ZN7android14SurfaceTexture25setFrameAvailableListenerERKNS_2spINS_21FrameAvailableListenerEEE",
            // ConsumerBase variant
            "_ZN7android12ConsumerBase25setFrameAvailableListenerERKNS_2spINS_21FrameAvailableListenerEEE",
    };
    static const size_t kSetFrameAvailableListenerSymbolCount =
            sizeof(kSetFrameAvailableListenerSymbols) /
            sizeof(kSetFrameAvailableListenerSymbols[0]);

// setDefaultBufferSize
    static const char *kSetDefaultBufferSizeSymbols[] = {
            "_ZN7android10GLConsumer20setDefaultBufferSizeEjj",
            "_ZN7android14SurfaceTexture20setDefaultBufferSizeEjj",
            "_ZN7android12ConsumerBase20setDefaultBufferSizeEjj",
    };
    static const size_t kSetDefaultBufferSizeSymbolCount =
            sizeof(kSetDefaultBufferSizeSymbols) / sizeof(kSetDefaultBufferSizeSymbols[0]);

// attachToContext
    static const char *kAttachToContextSymbols[] = {
            "_ZN7android10GLConsumer15attachToContextEj",
            "_ZN7android14SurfaceTexture15attachToContextEj",
    };
    static const size_t kAttachToContextSymbolCount =
            sizeof(kAttachToContextSymbols) / sizeof(kAttachToContextSymbols[0]);

// detachFromContext
    static const char *kDetachFromContextSymbols[] = {
            "_ZN7android10GLConsumer17detachFromContextEv",
            "_ZN7android14SurfaceTexture17detachFromContextEv",
    };
    static const size_t kDetachFromContextSymbolCount =
            sizeof(kDetachFromContextSymbols) / sizeof(kDetachFromContextSymbols[0]);


/**
 * Android sp<T> smart pointer structure.
 * Must match Android's libutils sp<T> ABI.
 */
    template<typename T>
    struct sp {
        T *ptr;

        sp() : ptr(nullptr) {}

        explicit sp(T *p) : ptr(p) {}

        T *get() const { return ptr; }

        explicit operator bool() const { return ptr != nullptr; }
    };

    static_assert(sizeof(sp<void>) == sizeof(void *), "sp<T> must be pointer-sized");


/**
 * FrameAvailableListener bridge for native callback.
 *
 * Android's FrameAvailableListener is a C++ class with virtual methods.
 * We create a minimal vtable-compatible structure to receive callbacks.
 */
    struct FrameAvailableListenerBridge {
        // VTable pointer (must be first for C++ ABI compatibility)
        void *vtable;

        // Reference count (RefBase)
        int32_t ref_count;

        // Our callback context
        GLConsumerWrapper *wrapper;

        // Constructor
        explicit FrameAvailableListenerBridge(GLConsumerWrapper *w)
                : vtable(nullptr), ref_count(1), wrapper(w) {}
    };

// VTable entries for FrameAvailableListener
// The actual vtable layout depends on Android version, but onFrameAvailable is typically at offset 0 or 1
    struct FrameAvailableListenerVTable {
        void *destructor;

        void (*onFrameAvailable)(FrameAvailableListenerBridge *self);

        void (*onFrameReplaced)(FrameAvailableListenerBridge *self);
    };


    GLConsumerWrapper::GLConsumerWrapper() {
        memset(transform_matrix_, 0, sizeof(transform_matrix_));
        // Identity matrix
        transform_matrix_[0] = 1.0f;
        transform_matrix_[5] = 1.0f;
        transform_matrix_[10] = 1.0f;
        transform_matrix_[15] = 1.0f;
    }

    GLConsumerWrapper::~GLConsumerWrapper() {
        shutdown();
    }

    bool GLConsumerWrapper::load_symbols(void *handle) {
        if (s_symbols_loaded_) {
            return s_symbols_.is_loaded();
        }

        // Load libgui.so if handle not provided
        if (!handle) {
            handle = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
            if (!handle) {
                handle = dlopen("/system/lib64/libgui.so", RTLD_NOW | RTLD_LOCAL);
            }
            if (!handle) {
                FUTON_LOGE("GLConsumerWrapper: failed to load libgui.so: %s", dlerror());
                return false;
            }
            s_symbols_.libgui_handle = handle;
        } else {
            s_symbols_.libgui_handle = handle;
        }

        FUTON_LOGI("GLConsumerWrapper: loading symbols from libgui.so");

        // Resolve constructor
        for (size_t i = 0; i < kGLConsumerCtorSymbolCount; i++) {
            void *sym = dlsym(handle, kGLConsumerCtorSymbols[i]);
            if (sym) {
                s_symbols_.ctor = sym;
                FUTON_LOGD("Resolved GLConsumer ctor: %s", kGLConsumerCtorSymbols[i]);
                break;
            }
        }

        // Resolve destructor
        for (size_t i = 0; i < kGLConsumerDtorSymbolCount; i++) {
            void *sym = dlsym(handle, kGLConsumerDtorSymbols[i]);
            if (sym) {
                s_symbols_.dtor = sym;
                FUTON_LOGD("Resolved GLConsumer dtor: %s", kGLConsumerDtorSymbols[i]);
                break;
            }
        }

        // Resolve updateTexImage
        for (size_t i = 0; i < kUpdateTexImageSymbolCount; i++) {
            void *sym = dlsym(handle, kUpdateTexImageSymbols[i]);
            if (sym) {
                s_symbols_.update_tex_image = sym;
                FUTON_LOGD("Resolved updateTexImage: %s", kUpdateTexImageSymbols[i]);
                break;
            }
        }

        // Resolve releaseTexImage
        for (size_t i = 0; i < kReleaseTexImageSymbolCount; i++) {
            void *sym = dlsym(handle, kReleaseTexImageSymbols[i]);
            if (sym) {
                s_symbols_.release_tex_image = sym;
                FUTON_LOGD("Resolved releaseTexImage: %s", kReleaseTexImageSymbols[i]);
                break;
            }
        }

        // Resolve getTransformMatrix
        for (size_t i = 0; i < kGetTransformMatrixSymbolCount; i++) {
            void *sym = dlsym(handle, kGetTransformMatrixSymbols[i]);
            if (sym) {
                s_symbols_.get_transform_matrix = sym;
                FUTON_LOGD("Resolved getTransformMatrix: %s", kGetTransformMatrixSymbols[i]);
                break;
            }
        }

        // Resolve getTimestamp
        for (size_t i = 0; i < kGetTimestampSymbolCount; i++) {
            void *sym = dlsym(handle, kGetTimestampSymbols[i]);
            if (sym) {
                s_symbols_.get_timestamp = sym;
                FUTON_LOGD("Resolved getTimestamp: %s", kGetTimestampSymbols[i]);
                break;
            }
        }

        // Resolve setFrameAvailableListener
        for (size_t i = 0; i < kSetFrameAvailableListenerSymbolCount; i++) {
            void *sym = dlsym(handle, kSetFrameAvailableListenerSymbols[i]);
            if (sym) {
                s_symbols_.set_frame_available_listener = sym;
                FUTON_LOGD("Resolved setFrameAvailableListener: %s",
                           kSetFrameAvailableListenerSymbols[i]);
                break;
            }
        }

        // Resolve setDefaultBufferSize
        for (size_t i = 0; i < kSetDefaultBufferSizeSymbolCount; i++) {
            void *sym = dlsym(handle, kSetDefaultBufferSizeSymbols[i]);
            if (sym) {
                s_symbols_.set_default_buffer_size = sym;
                FUTON_LOGD("Resolved setDefaultBufferSize: %s", kSetDefaultBufferSizeSymbols[i]);
                break;
            }
        }

        // Resolve attachToContext
        for (size_t i = 0; i < kAttachToContextSymbolCount; i++) {
            void *sym = dlsym(handle, kAttachToContextSymbols[i]);
            if (sym) {
                s_symbols_.attach_to_context = sym;
                FUTON_LOGD("Resolved attachToContext: %s", kAttachToContextSymbols[i]);
                break;
            }
        }

        // Resolve detachFromContext
        for (size_t i = 0; i < kDetachFromContextSymbolCount; i++) {
            void *sym = dlsym(handle, kDetachFromContextSymbols[i]);
            if (sym) {
                s_symbols_.detach_from_context = sym;
                FUTON_LOGD("Resolved detachFromContext: %s", kDetachFromContextSymbols[i]);
                break;
            }
        }

        s_symbols_loaded_ = true;

        if (!s_symbols_.is_loaded()) {
            FUTON_LOGE("GLConsumerWrapper: required symbols not found");
            FUTON_LOGE("  ctor: %p", s_symbols_.ctor);
            FUTON_LOGE("  updateTexImage: %p", s_symbols_.update_tex_image);
            return false;
        }

        FUTON_LOGI("GLConsumerWrapper: symbols loaded successfully");
        return true;
    }


    bool GLConsumerWrapper::initialize(void *consumer, GLuint texture_id, bool use_fence_sync) {
        if (initialized_) {
            FUTON_LOGW("GLConsumerWrapper: already initialized");
            return true;
        }

        if (!consumer) {
            FUTON_LOGE("GLConsumerWrapper: null consumer");
            return false;
        }

        if (texture_id == 0) {
            FUTON_LOGE("GLConsumerWrapper: invalid texture ID");
            return false;
        }

        // Load symbols if not already loaded
        if (!s_symbols_loaded_ && !load_symbols()) {
            FUTON_LOGE("GLConsumerWrapper: failed to load symbols");
            return false;
        }

        consumer_ = consumer;
        texture_id_ = texture_id;

        FUTON_LOGI("GLConsumerWrapper: initializing with texture %u, fence_sync=%d",
                   texture_id, use_fence_sync);

        // Create the GLConsumer object
        if (!create_gl_consumer(consumer, texture_id, use_fence_sync)) {
            FUTON_LOGE("GLConsumerWrapper: failed to create GLConsumer");
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("GLConsumerWrapper: initialized successfully");
        return true;
    }

    void GLConsumerWrapper::shutdown() {
        if (!initialized_) {
            return;
        }

        FUTON_LOGI("GLConsumerWrapper: shutting down (frames: %lu)",
                   static_cast<unsigned long>(frame_count_.load()));

        // Clear callback
        {
            std::lock_guard<std::mutex> lock(callback_mutex_);
            frame_callback_ = nullptr;
        }

        // Destroy GLConsumer
        destroy_gl_consumer();

        consumer_ = nullptr;
        texture_id_ = 0;
        initialized_ = false;
        frame_count_ = 0;
        transform_valid_ = false;
    }

    bool
    GLConsumerWrapper::create_gl_consumer(void *consumer, GLuint texture_id, bool use_fence_sync) {
        /**
         * GLConsumer construction in Android:
         *
         * GLConsumer(const sp<IGraphicBufferConsumer>& bq,
         *            uint32_t tex,
         *            uint32_t texTarget,
         *            bool useFenceSync,
         *            bool isControlledByApp)
         *
         * Parameters:
         * - bq: BufferQueue consumer (sp<IGraphicBufferConsumer>)
         * - tex: OpenGL texture ID
         * - texTarget: GL_TEXTURE_EXTERNAL_OES (0x8D65)
         * - useFenceSync: Whether to use EGL fence synchronization
         * - isControlledByApp: false for daemon use
         *
         * The GLConsumer is a C++ object that requires proper construction.
         * Since we can't easily call C++ constructors from C, we use placement new
         * with a pre-allocated buffer.
         */

        if (!s_symbols_.ctor) {
            FUTON_LOGE("create_gl_consumer: constructor symbol not available");

            // Fallback: use consumer directly without GLConsumer wrapper
            // This limits functionality but allows basic operation
            gl_consumer_ = consumer;
            FUTON_LOGW("create_gl_consumer: using consumer directly (limited functionality)");
            return true;
        }

        // GLConsumer object size varies by Android version
        // Typical size is 256-512 bytes, we allocate conservatively
        const size_t kGLConsumerSize = 1024;

        // Allocate memory for GLConsumer object
        void *gl_consumer_mem = malloc(kGLConsumerSize);
        if (!gl_consumer_mem) {
            FUTON_LOGE("create_gl_consumer: failed to allocate memory");
            return false;
        }
        memset(gl_consumer_mem, 0, kGLConsumerSize);

        // Prepare sp<IGraphicBufferConsumer> wrapper
        sp<void> consumer_sp;
        consumer_sp.ptr = consumer;

        // Call GLConsumer constructor
        // Signature: GLConsumer(sp<IGraphicBufferConsumer>&, uint32_t, uint32_t, bool, bool)
        //
        // ARM64 ABI:
        // - x0: this pointer (gl_consumer_mem)
        // - x1: &consumer_sp (reference to sp<IGraphicBufferConsumer>)
        // - w2: texture_id
        // - w3: GL_TEXTURE_EXTERNAL_OES (0x8D65)
        // - w4: useFenceSync
        // - w5: isControlledByApp

        using GLConsumerCtorFn = void (*)(void *self,
                                          const sp<void> &consumer,
                                          uint32_t tex,
                                          uint32_t texTarget,
                                          bool useFenceSync,
                                          bool isControlledByApp);

        auto ctor = reinterpret_cast<GLConsumerCtorFn>(s_symbols_.ctor);

        FUTON_LOGD("Calling GLConsumer constructor:");
        FUTON_LOGD("  this=%p", gl_consumer_mem);
        FUTON_LOGD("  consumer=%p", consumer);
        FUTON_LOGD("  tex=%u", texture_id);
        FUTON_LOGD("  texTarget=0x%x (GL_TEXTURE_EXTERNAL_OES)", GL_TEXTURE_EXTERNAL_OES);
        FUTON_LOGD("  useFenceSync=%d", use_fence_sync);
        FUTON_LOGD("  isControlledByApp=false");

        // Call constructor
        // Note: This may crash if the symbol signature doesn't match
        // We wrap in try-catch equivalent (signal handler would be better)
        ctor(gl_consumer_mem, consumer_sp, texture_id, GL_TEXTURE_EXTERNAL_OES, use_fence_sync,
             false);

        gl_consumer_ = gl_consumer_mem;
        FUTON_LOGI("GLConsumer created at %p", gl_consumer_);

        return true;
    }

    void GLConsumerWrapper::destroy_gl_consumer() {
        if (!gl_consumer_) {
            return;
        }

        // If we used the consumer directly (fallback mode), don't free
        if (gl_consumer_ == consumer_) {
            gl_consumer_ = nullptr;
            return;
        }

        // Call destructor if available
        if (s_symbols_.dtor) {
            using GLConsumerDtorFn = void (*)(void *self);
            auto dtor = reinterpret_cast<GLConsumerDtorFn>(s_symbols_.dtor);

            FUTON_LOGD("Calling GLConsumer destructor at %p", gl_consumer_);
            dtor(gl_consumer_);
        }

        // Free memory
        free(gl_consumer_);
        gl_consumer_ = nullptr;
    }

    void GLConsumerWrapper::set_frame_available_callback(OnFrameAvailableCallback callback) {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        frame_callback_ = std::move(callback);

        // If we have the setFrameAvailableListener symbol, set up native callback
        // This is complex because we need to create a C++ object with proper vtable
        // For now, we rely on polling via updateTexImage()

        if (s_symbols_.set_frame_available_listener && gl_consumer_ && gl_consumer_ != consumer_) {
            FUTON_LOGD("setFrameAvailableListener: native callback setup not implemented");
            FUTON_LOGD("  Using polling mode via updateTexImage()");
        }
    }

    void GLConsumerWrapper::on_frame_available_native(void *context) {
        auto *wrapper = static_cast<GLConsumerWrapper *>(context);
        if (!wrapper) {
            return;
        }

        std::lock_guard<std::mutex> lock(wrapper->callback_mutex_);
        if (wrapper->frame_callback_) {
            wrapper->frame_callback_();
        }
    }

    bool GLConsumerWrapper::update_tex_image() {
        if (!initialized_ || !gl_consumer_) {
            return false;
        }

        if (!s_symbols_.update_tex_image) {
            FUTON_LOGE("updateTexImage: symbol not available");
            return false;
        }

        // Bind the external texture before update
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture_id_);

        // Call GLConsumer::updateTexImage()
        // Returns status_t (0 = NO_ERROR)
        using UpdateTexImageFn = int (*)(void *self);
        auto fn = reinterpret_cast<UpdateTexImageFn>(s_symbols_.update_tex_image);

        int result = fn(gl_consumer_);

        if (result != 0) {
            // Non-zero typically means no new frame available
            // This is not necessarily an error
            return false;
        }

        // Invalidate transform matrix cache
        transform_valid_ = false;
        frame_count_++;

        return true;
    }

    void GLConsumerWrapper::release_tex_image() {
        if (!gl_consumer_ || !s_symbols_.release_tex_image) {
            return;
        }

        using ReleaseTexImageFn = void (*)(void *self);
        auto fn = reinterpret_cast<ReleaseTexImageFn>(s_symbols_.release_tex_image);
        fn(gl_consumer_);
    }

    void GLConsumerWrapper::get_transform_matrix(float *matrix) const {
        if (!matrix) {
            return;
        }

        if (!transform_valid_ && gl_consumer_ && s_symbols_.get_transform_matrix) {
            using GetTransformMatrixFn = void (*)(const void *self, float *matrix);
            auto fn = reinterpret_cast<GetTransformMatrixFn>(s_symbols_.get_transform_matrix);
            fn(gl_consumer_, transform_matrix_);
            transform_valid_ = true;
        }

        memcpy(matrix, transform_matrix_, sizeof(transform_matrix_));
    }

    int64_t GLConsumerWrapper::get_timestamp() const {
        if (!gl_consumer_ || !s_symbols_.get_timestamp) {
            return 0;
        }

        using GetTimestampFn = int64_t(*)(const void *self);
        auto fn = reinterpret_cast<GetTimestampFn>(s_symbols_.get_timestamp);
        return fn(gl_consumer_);
    }

    bool GLConsumerWrapper::set_default_buffer_size(uint32_t width, uint32_t height) {
        if (!gl_consumer_ || !s_symbols_.set_default_buffer_size) {
            FUTON_LOGW("set_default_buffer_size: not available");
            return false;
        }

        using SetDefaultBufferSizeFn = int (*)(void *self, uint32_t width, uint32_t height);
        auto fn = reinterpret_cast<SetDefaultBufferSizeFn>(s_symbols_.set_default_buffer_size);

        int result = fn(gl_consumer_, width, height);
        if (result != 0) {
            FUTON_LOGE("set_default_buffer_size: failed with error %d", result);
            return false;
        }

        FUTON_LOGD("set_default_buffer_size: %ux%u", width, height);
        return true;
    }

    bool GLConsumerWrapper::attach_to_context(GLuint texture_id) {
        if (!gl_consumer_ || !s_symbols_.attach_to_context) {
            FUTON_LOGW("attach_to_context: not available");
            return false;
        }

        using AttachToContextFn = int (*)(void *self, uint32_t tex);
        auto fn = reinterpret_cast<AttachToContextFn>(s_symbols_.attach_to_context);

        int result = fn(gl_consumer_, texture_id);
        if (result != 0) {
            FUTON_LOGE("attach_to_context: failed with error %d", result);
            return false;
        }

        texture_id_ = texture_id;
        FUTON_LOGD("attach_to_context: texture %u", texture_id);
        return true;
    }

    bool GLConsumerWrapper::detach_from_context() {
        if (!gl_consumer_ || !s_symbols_.detach_from_context) {
            FUTON_LOGW("detach_from_context: not available");
            return false;
        }

        using DetachFromContextFn = int (*)(void *self);
        auto fn = reinterpret_cast<DetachFromContextFn>(s_symbols_.detach_from_context);

        int result = fn(gl_consumer_);
        if (result != 0) {
            FUTON_LOGE("detach_from_context: failed with error %d", result);
            return false;
        }

        FUTON_LOGD("detach_from_context: success");
        return true;
    }

} // namespace futon::vision
