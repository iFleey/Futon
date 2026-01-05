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

#ifndef FUTON_VISION_PIPELINE_BUFFER_QUEUE_PIPELINE_H
#define FUTON_VISION_PIPELINE_BUFFER_QUEUE_PIPELINE_H

#include "core/error.h"
#include "vision/display/display_adapter.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <android/hardware_buffer.h>

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

namespace futon::vision {

/**
 * Opaque types for Android internal classes.
 * These are forward declarations for types we access via dlsym.
 */
    struct IGraphicBufferProducer;
    struct IGraphicBufferConsumer;
    struct GLConsumer;
    struct Surface;

// Forward declaration for GLConsumerWrapper
    class GLConsumerWrapper;

/**
 * BufferQueue symbols resolved at runtime.
 * Supports Android 11-16 with different symbol variants.
 */
    struct BufferQueueSymbols {
        void *libgui_handle = nullptr;

        // BufferQueue::createBufferQueue variants
        // Android 12+: void createBufferQueue(sp<IGraphicBufferProducer>*, sp<IGraphicBufferConsumer>*, bool)
        // Android 11: void createBufferQueue(sp<IGraphicBufferProducer>*, sp<IGraphicBufferConsumer>*)
        void *create_buffer_queue_fn = nullptr;
        int create_buffer_queue_api_level = 0;
        bool has_allocator_param = false;  // Android 12+ has allocator parameter

        // GLConsumer constructor variants
        // Android 12+: GLConsumer(sp<IGraphicBufferConsumer>&, uint32_t tex, uint32_t texTarget, bool useFenceSync, bool isControlledByApp)
        // Android 11: Similar but may have different parameter order
        void *gl_consumer_ctor = nullptr;

        // GLConsumer methods
        void *gl_consumer_update_tex_image = nullptr;
        void *gl_consumer_set_frame_available_listener = nullptr;
        void *gl_consumer_get_transform_matrix = nullptr;
        void *gl_consumer_get_timestamp = nullptr;
        void *gl_consumer_release_tex_image = nullptr;

        // Surface constructor: Surface(sp<IGraphicBufferProducer>&, bool)
        void *surface_ctor = nullptr;

        // Surface destructor
        void *surface_dtor = nullptr;

        bool is_loaded() const {
            return libgui_handle != nullptr &&
                   create_buffer_queue_fn != nullptr;
        }
    };

/**
 * Frame available callback type.
 */
    using FrameAvailableCallback = std::function<void()>;

/**
 * BufferQueuePipeline - Zero-copy frame capture via BufferQueue.
 *
 * Creates a BufferQueue connected to a virtual display, allowing
 * SurfaceFlinger to composite frames directly into GPU textures
 * without CPU copies.
 */
    class BufferQueuePipeline {
    public:
        BufferQueuePipeline();

        ~BufferQueuePipeline();

        // Disable copy
        BufferQueuePipeline(const BufferQueuePipeline &) = delete;

        BufferQueuePipeline &operator=(const BufferQueuePipeline &) = delete;

        /**
         * Initialize the BufferQueue pipeline.
         * Must be called after EGL context is created and made current.
         *
         * @param width Buffer width
         * @param height Buffer height
         * @return true on success
         */
        bool initialize(uint32_t width, uint32_t height);

        /**
         * Shutdown and release resources.
         */
        void shutdown();

        /**
         * Check if pipeline is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Get the producer Surface for connecting to virtual display.
         * @return Surface pointer (opaque), or nullptr if not initialized
         */
        void *get_producer_surface() const { return producer_surface_; }

        /**
         * Get the IGraphicBufferProducer for display configuration.
         * @return Producer pointer (opaque), or nullptr if not initialized
         */
        void *get_buffer_producer() const { return buffer_producer_; }

        /**
         * Connect the BufferQueue producer to a virtual display.
         * This allows SurfaceFlinger to composite frames directly into the BufferQueue.
         *
         * @param display_token Virtual display token from VirtualDisplay
         * @param source_width Physical screen width (source region)
         * @param source_height Physical screen height (source region)
         * @return true on success
         */
        bool connect_to_display(void *display_token, uint32_t source_width, uint32_t source_height);

        /**
         * Disconnect from the virtual display.
         */
        void disconnect_from_display();

        /**
         * Check if connected to a display.
         */
        bool is_connected_to_display() const { return connected_to_display_; }

        /**
         * Set callback for frame available notification.
         * @param callback Function to call when new frame is available
         */
        void set_frame_available_callback(FrameAvailableCallback callback);

        /**
         * Update the texture with the latest frame.
         * Must be called from the thread with EGL context.
         *
         * @return true if new frame was acquired
         */
        bool update_tex_image();

        /**
         * Get the GL texture ID for the current frame.
         * Texture target is GL_TEXTURE_EXTERNAL_OES.
         *
         * @return OpenGL texture ID
         */
        GLuint get_texture_id() const { return texture_id_; }

        /**
         * Get the texture transform matrix.
         * 4x4 matrix for proper texture coordinate transformation.
         *
         * @param matrix Output 16-float array
         */
        void get_transform_matrix(float *matrix) const;

        /**
         * Get pointer to the cached transform matrix.
         * @return Pointer to 16-float array, or nullptr if not valid
         */
        const float *get_transform_matrix() const;

        /**
         * Get timestamp of current frame in nanoseconds.
         */
        int64_t get_timestamp() const;

        /**
         * Release the current texture image.
         * Call after processing is complete.
         */
        void release_tex_image();

        /**
         * Acquire a frame from the BufferQueue.
         *
         * The frame data stays in GPU memory - zero CPU copies.
         *
         * @param out_texture_id Output texture ID (GL_TEXTURE_EXTERNAL_OES)
         * @param out_timestamp_ns Output frame timestamp in nanoseconds
         * @param out_transform Optional output 4x4 transform matrix (16 floats)
         * @return true if a new frame was acquired, false if no new frame available
         */
        bool acquire_frame(GLuint *out_texture_id, int64_t *out_timestamp_ns,
                           float *out_transform = nullptr);

        /**
         * Try to acquire a frame with timeout.
         *
         * Polls for a new frame up to the specified timeout.
         * Useful when waiting for the first frame after connecting to display.
         *
         * @param out_texture_id Output texture ID
         * @param out_timestamp_ns Output frame timestamp
         * @param timeout_ms Maximum time to wait in milliseconds
         * @param out_transform Optional output transform matrix
         * @return true if frame acquired, false on timeout
         */
        bool acquire_frame_timeout(GLuint *out_texture_id, int64_t *out_timestamp_ns,
                                   int timeout_ms, float *out_transform = nullptr);

        /**
         * Check if a new frame is available without acquiring it.
         * Note: This is a hint only - the frame may be consumed by another call.
         */
        bool has_pending_frame() const;

        /**
         * Get buffer width.
         */
        uint32_t get_width() const { return width_; }

        /**
         * Get buffer height.
         */
        uint32_t get_height() const { return height_; }

        /**
         * Check if BufferQueue API is available.
         */
        static bool is_available();

        /**
         * Get the loaded symbols (for debugging).
         */
        const BufferQueueSymbols &get_symbols() const { return symbols_; }

    private:
        bool initialized_ = false;
        uint32_t width_ = 0;
        uint32_t height_ = 0;

        BufferQueueSymbols symbols_;

        // BufferQueue components (opaque pointers)
        void *buffer_producer_ = nullptr;  // sp<IGraphicBufferProducer>
        void *buffer_consumer_ = nullptr;  // sp<IGraphicBufferConsumer>
        void *gl_consumer_ = nullptr;      // GLConsumer*
        void *producer_surface_ = nullptr; // Surface*
        GLConsumerWrapper *gl_consumer_wrapper_ = nullptr;  // Wrapper for GLConsumer

        // OpenGL texture
        GLuint texture_id_ = 0;

        // Frame available callback
        FrameAvailableCallback frame_callback_;
        std::mutex callback_mutex_;

        // Frame counter
        std::atomic<uint64_t> frame_count_{0};

        // Pending frame flag (set by frame available callback)
        std::atomic<bool> frame_pending_{false};

        // Transform matrix cache
        mutable float transform_matrix_[16];
        mutable bool transform_valid_ = false;

        // Display connection state
        bool connected_to_display_ = false;
        void *connected_display_token_ = nullptr;

        bool load_symbols();

        bool create_buffer_queue();

        bool create_gl_consumer();

        bool create_producer_surface();

        void setup_frame_listener();

        // Symbol resolution helpers
        bool resolve_buffer_queue_symbols();

        bool resolve_gl_consumer_symbols();

        bool resolve_surface_symbols();
    };

} // namespace futon::vision

#endif // FUTON_VISION_PIPELINE_BUFFER_QUEUE_PIPELINE_H
