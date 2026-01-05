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

#ifndef FUTON_VISION_PIPELINE_GL_CONSUMER_WRAPPER_H
#define FUTON_VISION_PIPELINE_GL_CONSUMER_WRAPPER_H

#include "core/error.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>

namespace futon::vision {

/**
 * Frame available callback type.
 */
    using OnFrameAvailableCallback = std::function<void()>;

/**
 * GLConsumer symbols resolved at runtime.
 */
    struct GLConsumerSymbols {
        void *libgui_handle = nullptr;

        // GLConsumer constructor
        // GLConsumer(sp<IGraphicBufferConsumer>&, uint32_t tex, uint32_t texTarget, bool useFenceSync, bool isControlledByApp)
        void *ctor = nullptr;

        // GLConsumer destructor
        void *dtor = nullptr;

        // GLConsumer::updateTexImage()
        void *update_tex_image = nullptr;

        // GLConsumer::releaseTexImage()
        void *release_tex_image = nullptr;

        // GLConsumer::getTransformMatrix(float*)
        void *get_transform_matrix = nullptr;

        // GLConsumer::getTimestamp()
        void *get_timestamp = nullptr;

        // GLConsumer::setFrameAvailableListener(sp<FrameAvailableListener>&)
        void *set_frame_available_listener = nullptr;

        // GLConsumer::setDefaultBufferSize(uint32_t, uint32_t)
        void *set_default_buffer_size = nullptr;

        // GLConsumer::attachToContext(uint32_t tex)
        void *attach_to_context = nullptr;

        // GLConsumer::detachFromContext()
        void *detach_from_context = nullptr;

        bool is_loaded() const {
            return libgui_handle != nullptr &&
                   update_tex_image != nullptr;
        }
    };

/**
 * GLConsumerWrapper - Wrapper for Android's GLConsumer (SurfaceTexture).
 */
    class GLConsumerWrapper {
    public:
        GLConsumerWrapper();

        ~GLConsumerWrapper();

        // Disable copy
        GLConsumerWrapper(const GLConsumerWrapper &) = delete;

        GLConsumerWrapper &operator=(const GLConsumerWrapper &) = delete;

        /**
         * Initialize the GLConsumer wrapper.
         *
         * @param consumer IGraphicBufferConsumer from BufferQueue
         * @param texture_id OpenGL texture ID (must be created before)
         * @param use_fence_sync Whether to use fence synchronization
         * @return true on success
         */
        bool initialize(void *consumer, GLuint texture_id, bool use_fence_sync = true);

        /**
         * Shutdown and release resources.
         */
        void shutdown();

        /**
         * Check if initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Set the frame available callback.
         * Called when a new frame is available in the BufferQueue.
         *
         * @param callback Function to call on frame available
         */
        void set_frame_available_callback(OnFrameAvailableCallback callback);

        /**
         * Update the texture with the latest frame from BufferQueue.
         * Must be called from the thread with EGL context.
         *
         * @return true if new frame was acquired, false if no new frame
         */
        bool update_tex_image();

        /**
         * Release the current texture image.
         * Call after processing is complete to return buffer to queue.
         */
        void release_tex_image();

        /**
         * Get the texture transform matrix.
         * This 4x4 matrix transforms texture coordinates to account for
         * buffer orientation and cropping.
         *
         * @param matrix Output 16-float array (column-major)
         */
        void get_transform_matrix(float *matrix) const;

        /**
         * Get the timestamp of the current frame in nanoseconds.
         */
        int64_t get_timestamp() const;

        /**
         * Set the default buffer size.
         * @param width Buffer width
         * @param height Buffer height
         * @return true on success
         */
        bool set_default_buffer_size(uint32_t width, uint32_t height);

        /**
         * Attach to a new EGL context with a new texture.
         * @param texture_id New texture ID
         * @return true on success
         */
        bool attach_to_context(GLuint texture_id);

        /**
         * Detach from current EGL context.
         * @return true on success
         */
        bool detach_from_context();

        /**
         * Get the texture ID.
         */
        GLuint get_texture_id() const { return texture_id_; }

        /**
         * Get frame count.
         */
        uint64_t get_frame_count() const { return frame_count_.load(); }

        /**
         * Load symbols from libgui.so.
         * @param handle Library handle (or nullptr to load)
         * @return true if symbols loaded successfully
         */
        static bool load_symbols(void *handle = nullptr);

        /**
         * Get loaded symbols.
         */
        static const GLConsumerSymbols &get_symbols() { return s_symbols_; }

    private:
        bool initialized_ = false;
        void *gl_consumer_ = nullptr;  // GLConsumer* (opaque)
        void *consumer_ = nullptr;     // IGraphicBufferConsumer* (kept for reference)
        GLuint texture_id_ = 0;

        OnFrameAvailableCallback frame_callback_;
        std::mutex callback_mutex_;

        std::atomic<uint64_t> frame_count_{0};

        // Transform matrix cache
        mutable float transform_matrix_[16];
        mutable bool transform_valid_ = false;

        static GLConsumerSymbols s_symbols_;
        static bool s_symbols_loaded_;

        bool create_gl_consumer(void *consumer, GLuint texture_id, bool use_fence_sync);

        void destroy_gl_consumer();

        // Frame available listener (native callback bridge)
        static void on_frame_available_native(void *context);
    };

} // namespace futon::vision

#endif // FUTON_VISION_PIPELINE_GL_CONSUMER_WRAPPER_H
