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

#ifndef FUTON_VISION_EGL_GPU_PREPROCESSOR_H
#define FUTON_VISION_EGL_GPU_PREPROCESSOR_H

#include "vision/buffer/hardware_buffer_wrapper.h"
#include "vision/egl/egl_environment.h"
#include "core/error.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <thread>
#include <memory>
#include <cstdint>

namespace futon::vision {

/**
 * Resize mode for GPU preprocessing.
 */
    enum class ResizeMode {
        Full,     // No resize (1:1)
        Half,     // 1/2 resolution
        Quarter,  // 1/4 resolution
    };

/**
 * Input texture type for GPU preprocessing.
 */
    enum class InputTextureType {
        Texture2D,          // Regular GL_TEXTURE_2D (from AHardwareBuffer)
        ExternalOES,        // GL_TEXTURE_EXTERNAL_OES (from GLConsumer/SurfaceTexture)
    };

/**
 * GPU preprocessing result.
 */
    struct PreprocessResult {
        AHardwareBuffer *output_buffer = nullptr;
        int fence_fd = -1;
        uint32_t width = 0;
        uint32_t height = 0;
        float process_time_ms = 0.0f;
    };

/**
 * GpuPreprocessor - GPU-based color space conversion and resize.
 *
 * Uses OpenGL ES Compute Shader for:
 * - RGBA_8888 -> RGB_888 conversion
 * - Optional resize (half, quarter)
 * - Fence synchronization for downstream DSP
 *
 * Key constraints:
 * - NO CPU pixel manipulation
 * - Must be called from same thread as initialize()
 * - Outputs fence_fd for downstream sync
 *
 * Uses EglEnvironment for native EGL context management (no Java dependencies).
 */
    class GpuPreprocessor {
    public:
        GpuPreprocessor();

        ~GpuPreprocessor();

        // Disable copy
        GpuPreprocessor(const GpuPreprocessor &) = delete;

        GpuPreprocessor &operator=(const GpuPreprocessor &) = delete;

        /**
         * Initialize with internal EGL environment.
         * Creates a new EglEnvironment internally.
         * Must be called from the thread that will call process().
         * @return true on success
         */
        bool initialize();

        /**
         * Initialize with external EGL environment.
         * Uses provided EglEnvironment (must be initialized and current).
         * @param egl_env Shared pointer to EglEnvironment
         * @return true on success
         */
        bool initialize(std::shared_ptr<EglEnvironment> egl_env);

        /**
         * Shutdown and release resources.
         */
        void shutdown();

        /**
         * Check if initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Get the EGL environment (may be null if not initialized).
         */
        std::shared_ptr<EglEnvironment> get_egl_environment() const { return egl_env_; }

        /**
         * Process input buffer: RGBA -> RGB conversion + optional resize.
         * MUST be called from the same thread as initialize().
         *
         * @param input Input AHardwareBuffer (RGBA_8888)
         * @param output Output AHardwareBuffer (RGB_888)
         * @param resize Resize mode
         * @param out_fence_fd Output fence fd for downstream sync
         * @return Result with error code
         */
        core::Result <PreprocessResult> process(AHardwareBuffer *input,
                                                AHardwareBuffer *output,
                                                ResizeMode resize = ResizeMode::Full);

        /**
         * Process external texture from GLConsumer: RGBA -> RGB conversion + optional resize.
         * MUST be called from the same thread as initialize().
         *
         * This method is designed for zero-copy pipeline where input comes from
         * GLConsumer (SurfaceTexture) as GL_TEXTURE_EXTERNAL_OES.
         *
         * @param external_texture_id Texture ID from GLConsumer (GL_TEXTURE_EXTERNAL_OES)
         * @param input_width Input texture width
         * @param input_height Input texture height
         * @param transform_matrix 4x4 transform matrix from GLConsumer (column-major, can be nullptr)
         * @param output Output AHardwareBuffer (RGBA_8888)
         * @param resize Resize mode
         * @return Result with PreprocessResult
         */
        core::Result <PreprocessResult> process_external_texture(
                GLuint external_texture_id,
                uint32_t input_width,
                uint32_t input_height,
                const float *transform_matrix,
                AHardwareBuffer *output,
                ResizeMode resize = ResizeMode::Full);

        /**
         * Allocate output buffer for specified resize mode.
         * @param input_width Input width
         * @param input_height Input height
         * @param resize Resize mode
         * @param out_buffer Output buffer wrapper
         * @return true on success
         */
        bool allocate_output_buffer(uint32_t input_width, uint32_t input_height,
                                    ResizeMode resize, HardwareBufferWrapper *out_buffer);

        /**
         * Bind EGL context to current thread.
         * Use for thread migration.
         * @return true on success
         */
        bool make_current();

        /**
         * Release EGL context from current thread.
         */
        void release_current();

        /**
         * Get the thread ID that owns this context.
         */
        std::thread::id get_bound_thread_id() const { return bound_thread_id_; }

        /**
         * Calculate output dimensions for resize mode.
         */
        static void get_output_dimensions(uint32_t input_width, uint32_t input_height,
                                          ResizeMode resize,
                                          uint32_t *out_width, uint32_t *out_height);

        /**
         * Process ROI (Region of Interest) with letterbox padding.
         * Designed for OCR preprocessing: crop a region and resize to fixed dimensions.
         *
         * @param external_texture_id Texture ID from GLConsumer (GL_TEXTURE_EXTERNAL_OES)
         * @param input_width Full input texture width
         * @param input_height Full input texture height
         * @param transform_matrix 4x4 transform matrix from GLConsumer (can be nullptr)
         * @param roi_x ROI left coordinate (normalized [0, 1])
         * @param roi_y ROI top coordinate (normalized [0, 1])
         * @param roi_w ROI width (normalized [0, 1])
         * @param roi_h ROI height (normalized [0, 1])
         * @param output Output AHardwareBuffer (must be pre-allocated with target size)
         * @return Result with PreprocessResult
         */
        core::Result <PreprocessResult> process_roi(
                GLuint external_texture_id,
                uint32_t input_width,
                uint32_t input_height,
                const float *transform_matrix,
                float roi_x, float roi_y, float roi_w, float roi_h,
                AHardwareBuffer *output);

        /**
         * Allocate output buffer for OCR (fixed size with letterbox).
         * @param target_width Target output width (e.g., 320 for PP-OCRv4)
         * @param target_height Target output height (e.g., 48 for PP-OCRv4)
         * @param out_buffer Output buffer wrapper
         * @return true on success
         */
        bool allocate_ocr_buffer(uint32_t target_width, uint32_t target_height,
                                 HardwareBufferWrapper *out_buffer);

    private:
        bool initialized_ = false;
        bool owns_egl_env_ = false;  // True if we created the EglEnvironment
        std::thread::id bound_thread_id_;

        // EGL environment (shared or owned)
        std::shared_ptr<EglEnvironment> egl_env_;

        // OpenGL state
        GLuint compute_program_ = 0;
        GLuint compute_program_external_ = 0;  // For GL_TEXTURE_EXTERNAL_OES
        GLuint input_texture_ = 0;
        GLuint output_texture_ = 0;

        // Uniform locations for regular shader
        GLint u_input_size_ = -1;
        GLint u_output_size_ = -1;
        GLint u_resize_factor_ = -1;

        // Uniform locations for external texture shader
        GLint u_ext_input_size_ = -1;
        GLint u_ext_output_size_ = -1;
        GLint u_ext_transform_matrix_ = -1;

        // EGL extension functions
        PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR_ = nullptr;
        PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR_ = nullptr;
        PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR_ = nullptr;
        PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR_ = nullptr;
        PFNEGLCLIENTWAITSYNCKHRPROC eglClientWaitSyncKHR_ = nullptr;
        PFNEGLDUPNATIVEFENCEFDANDROIDPROC eglDupNativeFenceFDANDROID_ = nullptr;

        bool init_internal_egl();

        bool load_egl_extensions();

        bool create_compute_shader();

        bool create_external_compute_shader();

        bool create_roi_compute_shader();

        bool validate_thread();

        GLuint compile_shader(GLenum type, const char *source);

        GLuint link_program(GLuint compute_shader);

        bool bind_input_buffer(AHardwareBuffer *buffer, uint32_t width, uint32_t height);

        bool bind_output_buffer(AHardwareBuffer *buffer, uint32_t width, uint32_t height);

        bool bind_external_input_texture(GLuint external_texture_id);

        int create_fence();

        // ROI shader program and uniforms
        GLuint compute_program_roi_ = 0;
        GLint u_roi_input_size_ = -1;
        GLint u_roi_output_size_ = -1;
        GLint u_roi_rect_ = -1;
        GLint u_roi_transform_matrix_ = -1;
    };

} // namespace futon::vision

#endif // FUTON_VISION_EGL_GPU_PREPROCESSOR_H
