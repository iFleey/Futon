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

#include "vision/capture/vision_pipeline.h"
#include "core/error.h"

#include <linux/sync_file.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <unistd.h>
#include <chrono>
#include <cstring>
#include <algorithm>
#include <thread>

using namespace futon::core;

namespace futon::vision {
    class VisionPipeline::Impl {
    public:
        // Mutex to serialize EGL operations across Binder threads
        // EGL contexts can only be bound to one thread at a time, so we need
        // to ensure acquire_frame() calls are serialized to prevent EGL_BAD_ACCESS
        std::mutex egl_mutex_;

        // Shared EGL environment for all GPU operations
        std::shared_ptr<EglEnvironment> egl_env_;

        // Virtual display for screen mirroring
        VirtualDisplay virtual_display_;

        // BufferQueue pipeline for zero-copy capture
        BufferQueuePipeline buffer_queue_;

        // GPU preprocessor for RGBA->RGB + resize
        GpuPreprocessor preprocessor_;

        // Double/triple buffering for output to prevent Write-After-Write hazard
        // When downstream (AI inference) is reading buffer N, GPU can write to buffer N+1
        std::vector<HardwareBufferWrapper> output_buffers_;
        std::atomic<size_t> current_buffer_index_{0};

        // Fallback: SurfaceControl capture (when BufferQueue unavailable)
        SurfaceControlCapture fallback_capture_;

        // Initialization flags
        bool egl_initialized_ = false;
        bool virtual_display_initialized_ = false;
        bool buffer_queue_initialized_ = false;
        bool preprocessor_initialized_ = false;
        bool fallback_initialized_ = false;
        bool connected_to_display_ = false;

        // BufferQueue state tracking to prevent consecutive acquire without release
        // FIX: Use atomic to prevent data race between producer/consumer threads
        std::atomic<bool> buffer_queue_frame_held_{false};

        // Physical display dimensions
        uint32_t physical_width_ = 0;
        uint32_t physical_height_ = 0;

        /**
         * Get next output buffer using round-robin.
         * Thread-safe via atomic index.
         */
        HardwareBufferWrapper *get_next_output_buffer() {
            if (output_buffers_.empty()) {
                return nullptr;
            }
            size_t index = current_buffer_index_.fetch_add(1) % output_buffers_.size();
            return &output_buffers_[index];
        }
    };

    VisionPipeline::VisionPipeline()
            : impl_(std::make_unique<Impl>()),
              last_fps_update_(std::chrono::steady_clock::now()) {
        // FrameStats uses C++11 default member initialization, no memset needed
    }

    VisionPipeline::~VisionPipeline() {
        shutdown();
    }

    bool VisionPipeline::is_private_api_available() {
        return SurfaceControlCapture::is_available();
    }

    bool VisionPipeline::is_buffer_queue_available() {
        return BufferQueuePipeline::is_available();
    }

    bool VisionPipeline::initialize(const VisionConfig &config) {
        if (initialized_) {
            FUTON_LOGW("VisionPipeline: already initialized");
            return true;
        }

        config_ = config;

        // Get physical display dimensions
        DisplayConfig display_config;
        if (!VirtualDisplay::get_physical_display_config(&display_config)) {
            FUTON_LOGE("VisionPipeline: failed to get display config");
            return false;
        }

        impl_->physical_width_ = display_config.width;
        impl_->physical_height_ = display_config.height;

        // Determine capture dimensions
        if (config.custom_width > 0 && config.custom_height > 0) {
            capture_width_ = config.custom_width;
            capture_height_ = config.custom_height;
        } else {
            capture_width_ = impl_->physical_width_;
            capture_height_ = impl_->physical_height_;
        }

        // Calculate output dimensions based on resolution mode
        ResizeMode resize_mode = ResizeMode::Full;
        switch (config.resolution) {
            case CaptureResolution::Half:
                resize_mode = ResizeMode::Half;
                break;
            case CaptureResolution::Quarter:
                resize_mode = ResizeMode::Quarter;
                break;
            default:
                resize_mode = ResizeMode::Full;
                break;
        }

        GpuPreprocessor::get_output_dimensions(capture_width_, capture_height_,
                                               resize_mode,
                                               &output_width_, &output_height_);

        FUTON_LOGI("VisionPipeline: physical=%ux%u, capture=%ux%u, output=%ux%u",
                   impl_->physical_width_, impl_->physical_height_,
                   capture_width_, capture_height_, output_width_, output_height_);

        // Determine pipeline mode
        PipelineMode mode = config.mode;
        if (mode == PipelineMode::Auto) {
            // Auto-select: prefer BufferQueue for zero-copy
            if (is_buffer_queue_available()) {
                mode = PipelineMode::BufferQueue;
                FUTON_LOGI("VisionPipeline: auto-selected BufferQueue mode (zero-copy)");
            } else if (is_private_api_available()) {
                mode = PipelineMode::SurfaceControl;
                FUTON_LOGI("VisionPipeline: auto-selected SurfaceControl mode");
            } else {
                mode = PipelineMode::Fallback;
                FUTON_LOGI("VisionPipeline: auto-selected Fallback mode");
            }
        }

        bool success = false;

        if (mode == PipelineMode::BufferQueue) {
            success = initialize_buffer_queue_mode();
            if (!success) {
                FUTON_LOGW("VisionPipeline: BufferQueue mode failed, trying SurfaceControl");
                mode = PipelineMode::SurfaceControl;
            }
        }

        if (!success && mode == PipelineMode::SurfaceControl) {
            success = initialize_surface_control_mode();
            if (!success) {
                FUTON_LOGW("VisionPipeline: SurfaceControl mode failed, trying Fallback");
                mode = PipelineMode::Fallback;
            }
        }

        if (!success && mode == PipelineMode::Fallback) {
            success = initialize_fallback_mode();
        }

        if (!success) {
            FUTON_LOGE("VisionPipeline: all initialization modes failed");
            shutdown();
            return false;
        }

        active_mode_ = mode;
        initialized_ = true;

        // Initialize statistics
        reset_stats();

        FUTON_LOGI("VisionPipeline: initialized successfully (mode=%d)",
                   static_cast<int>(active_mode_));
        return true;
    }

    bool VisionPipeline::initialize_buffer_queue_mode() {
        FUTON_LOGI("VisionPipeline: initializing BufferQueue mode...");

        impl_->egl_env_ = std::make_shared<EglEnvironment>();
        EglConfig egl_config;
        egl_config.require_es31 = true;  // Required for compute shaders

        if (!impl_->egl_env_->initialize(egl_config)) {
            FUTON_LOGE("VisionPipeline: failed to create EGL environment");
            return false;
        }
        impl_->egl_initialized_ = true;
        FUTON_LOGI("  EGL environment created");

        uint32_t flags = VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                         VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

        if (!impl_->virtual_display_.create(capture_width_, capture_height_, flags,
                                            "FutonCapture")) {
            FUTON_LOGE("VisionPipeline: failed to create virtual display");
            return false;
        }
        impl_->virtual_display_initialized_ = true;
        FUTON_LOGI("  Virtual display created: %ux%u", capture_width_, capture_height_);

        if (!impl_->buffer_queue_.initialize(capture_width_, capture_height_)) {
            FUTON_LOGE("VisionPipeline: failed to initialize BufferQueue");
            return false;
        }
        impl_->buffer_queue_initialized_ = true;
        FUTON_LOGI("  BufferQueue pipeline created");

        void *display_token = impl_->virtual_display_.get_display_token();
        if (!impl_->buffer_queue_.connect_to_display(display_token,
                                                     impl_->physical_width_,
                                                     impl_->physical_height_)) {
            FUTON_LOGE("VisionPipeline: failed to connect BufferQueue to display");
            return false;
        }
        impl_->connected_to_display_ = true;
        FUTON_LOGI("  BufferQueue connected to virtual display");

        if (config_.enable_gpu_preprocess) {
            if (!impl_->preprocessor_.initialize(impl_->egl_env_)) {
                FUTON_LOGE("VisionPipeline: failed to initialize GPU preprocessor");
                return false;
            }
            impl_->preprocessor_initialized_ = true;
            FUTON_LOGI("  GPU preprocessor initialized");

            // Allocate output buffer pool for double/triple buffering
            ResizeMode resize_mode = ResizeMode::Full;
            switch (config_.resolution) {
                case CaptureResolution::Half:
                    resize_mode = ResizeMode::Half;
                    break;
                case CaptureResolution::Quarter:
                    resize_mode = ResizeMode::Quarter;
                    break;
                default:
                    resize_mode = ResizeMode::Full;
                    break;
            }

            // Allocate multiple output buffers to prevent Write-After-Write hazard
            uint32_t buffer_count = std::max(2u, config_.output_buffer_count);
            impl_->output_buffers_.resize(buffer_count);
            for (uint32_t i = 0; i < buffer_count; ++i) {
                if (!impl_->preprocessor_.allocate_output_buffer(
                        capture_width_, capture_height_, resize_mode, &impl_->output_buffers_[i])) {
                    FUTON_LOGE("VisionPipeline: failed to allocate output buffer %u", i);
                    return false;
                }
            }
            FUTON_LOGI("  Output buffer pool allocated: %u buffers @ %ux%u",
                       buffer_count, output_width_, output_height_);
        }

        FUTON_LOGI("VisionPipeline: BufferQueue mode initialized successfully");
        FUTON_LOGI(
                "  Zero-copy pipeline: SurfaceFlinger -> BufferQueue -> GLConsumer -> GPU -> Output");
        // Release EGL context to allow Binder threads to acquire it
        if (impl_->egl_env_) {
            impl_->egl_env_->release_current();
        }
        return true;
    }

    bool VisionPipeline::initialize_surface_control_mode() {
        FUTON_LOGI("VisionPipeline: initializing SurfaceControl mode...");

        // Initialize SurfaceControl capture
        if (!impl_->fallback_capture_.initialize(capture_width_, capture_height_)) {
            FUTON_LOGE("VisionPipeline: failed to initialize SurfaceControl capture");
            return false;
        }
        impl_->fallback_initialized_ = true;

        // Initialize GPU preprocessor if enabled
        if (config_.enable_gpu_preprocess) {
            // Initialize EGL environment for GPU preprocessing
            impl_->egl_env_ = std::make_shared<EglEnvironment>();
            EglConfig egl_config;
            egl_config.require_es31 = true;  // Required for compute shaders

            if (!impl_->egl_env_->initialize(egl_config)) {
                FUTON_LOGE("VisionPipeline: failed to create EGL environment");
                return false;
            }
            impl_->egl_initialized_ = true;
            FUTON_LOGI("  EGL environment created");

            if (!impl_->preprocessor_.initialize(impl_->egl_env_)) {
                FUTON_LOGE("VisionPipeline: failed to initialize GPU preprocessor");
                return false;
            }
            impl_->preprocessor_initialized_ = true;

            // Allocate output buffer
            ResizeMode resize_mode = ResizeMode::Full;
            switch (config_.resolution) {
                case CaptureResolution::Half:
                    resize_mode = ResizeMode::Half;
                    break;
                case CaptureResolution::Quarter:
                    resize_mode = ResizeMode::Quarter;
                    break;
                default:
                    resize_mode = ResizeMode::Full;
                    break;
            }

            // Allocate output buffer pool for double/triple buffering
            uint32_t buffer_count = std::max(2u, config_.output_buffer_count);
            impl_->output_buffers_.resize(buffer_count);
            for (uint32_t i = 0; i < buffer_count; ++i) {
                if (!impl_->preprocessor_.allocate_output_buffer(
                        capture_width_, capture_height_, resize_mode, &impl_->output_buffers_[i])) {
                    FUTON_LOGE("VisionPipeline: failed to allocate output buffer %u", i);
                    return false;
                }
            }
            FUTON_LOGI("  Output buffer pool allocated: %u buffers", buffer_count);
        }

        FUTON_LOGI("VisionPipeline: SurfaceControl mode initialized successfully");
        // Release EGL context to allow Binder threads to acquire it
        if (impl_->egl_env_) {
            impl_->egl_env_->release_current();
        }
        return true;
    }

    bool VisionPipeline::initialize_fallback_mode() {
        FUTON_LOGI("VisionPipeline: initializing Fallback mode...");

        // For fallback mode, we use SurfaceControl capture which may use Java helper
        if (!impl_->fallback_capture_.initialize(capture_width_, capture_height_)) {
            FUTON_LOGE("VisionPipeline: failed to initialize fallback capture");
            return false;
        }
        impl_->fallback_initialized_ = true;

        FUTON_LOGI("VisionPipeline: Fallback mode initialized successfully");
        return true;
    }

    void VisionPipeline::shutdown() {
        if (!initialized_ && !impl_->egl_initialized_) {
            return;
        }

        FUTON_LOGI("VisionPipeline: shutting down (processed %lu frames)",
                   static_cast<unsigned long>(frame_count_.load()));

        // Release all output buffers
        for (auto &buffer: impl_->output_buffers_) {
            buffer.release();
        }
        impl_->output_buffers_.clear();

        // Shutdown GPU preprocessor
        if (impl_->preprocessor_initialized_) {
            impl_->preprocessor_.shutdown();
            impl_->preprocessor_initialized_ = false;
        }

        // Disconnect from display
        if (impl_->connected_to_display_) {
            impl_->buffer_queue_.disconnect_from_display();
            impl_->connected_to_display_ = false;
        }

        // Shutdown BufferQueue
        if (impl_->buffer_queue_initialized_) {
            impl_->buffer_queue_.shutdown();
            impl_->buffer_queue_initialized_ = false;
        }

        // Destroy virtual display
        if (impl_->virtual_display_initialized_) {
            impl_->virtual_display_.destroy();
            impl_->virtual_display_initialized_ = false;
        }

        // Shutdown fallback capture
        if (impl_->fallback_initialized_) {
            impl_->fallback_capture_.shutdown();
            impl_->fallback_initialized_ = false;
        }

        // Shutdown EGL environment (must be last)
        if (impl_->egl_initialized_) {
            impl_->egl_env_->shutdown();
            impl_->egl_env_.reset();
            impl_->egl_initialized_ = false;
        }

        initialized_ = false;
        capture_width_ = 0;
        capture_height_ = 0;
        output_width_ = 0;
        output_height_ = 0;
        frame_count_ = 0;
        active_mode_ = PipelineMode::Auto;
    }


    Result <FrameResult> VisionPipeline::acquire_frame() {
        if (!initialized_) {
            FUTON_LOGE("VisionPipeline: acquire_frame called but pipeline not initialized!");
            return Result<FrameResult>::error(FutonError::NotInitialized);
        }

        // Serialize EGL operations across Binder threads to prevent EGL_BAD_ACCESS (0x3002)
        // EGL contexts can only be current on one thread at a time, so concurrent calls
        // from Binder thread pool must be serialized
        std::lock_guard<std::mutex> egl_lock(impl_->egl_mutex_);

        auto frame_start = std::chrono::high_resolution_clock::now();

        FrameResult result;
        // Atomically assign frame number to prevent race conditions with concurrent callers
        result.frame_number = frame_count_.fetch_add(1);

        // Route to appropriate capture method based on active mode
        if (active_mode_ == PipelineMode::BufferQueue) {
            auto capture_result = acquire_frame_buffer_queue(&result);
            if (!capture_result.is_ok()) {
                return capture_result;
            }
        } else {
            auto capture_result = acquire_frame_surface_control(&result);
            if (!capture_result.is_ok()) {
                return capture_result;
            }
        }

        auto frame_end = std::chrono::high_resolution_clock::now();
        result.total_time_ms = std::chrono::duration<float, std::milli>(
                frame_end - frame_start).count();

        // Update statistics
        update_stats(result);

        return Result<FrameResult>::ok(result);
    }

    Result <FrameResult> VisionPipeline::acquire_frame_buffer_queue(FrameResult *result) {

        // Check if previous frame was released (prevent state corruption)
        // FIX: Use CAS (Compare-And-Swap) to ensure atomicity of check-then-act
        // Only the thread that successfully swaps true->false will execute release
        bool expected_held = true;
        if (impl_->buffer_queue_frame_held_.compare_exchange_strong(
                expected_held, false, std::memory_order_acq_rel)) {
            FUTON_LOGW("acquire_frame_buffer_queue: previous frame not released, auto-releasing");
            impl_->buffer_queue_.release_tex_image();
        }

        auto capture_start = std::chrono::high_resolution_clock::now();

        // Acquire frame from BufferQueue as GL texture
        GLuint texture_id = 0;
        int64_t timestamp_ns = 0;
        float transform_matrix[16];

        // Try to acquire with timeout
        bool got_frame = impl_->buffer_queue_.acquire_frame_timeout(
                &texture_id, &timestamp_ns, config_.fence_timeout_ms, transform_matrix);

        if (!got_frame) {
            // No new frame available - this is normal when screen is static
            // FIX: Don't fall back to blocking acquire_frame() which can deadlock
            // Instead, retry with extended timeout (2x) for edge cases
            int extended_timeout = config_.fence_timeout_ms * 2;
            got_frame = impl_->buffer_queue_.acquire_frame_timeout(
                    &texture_id, &timestamp_ns, extended_timeout, transform_matrix);
            if (!got_frame) {
                // Screen is likely static, return timeout instead of blocking forever
                FUTON_LOGD("acquire_frame_buffer_queue: no frame available after extended timeout");
                return Result<FrameResult>::error(FutonError::Timeout);
            }
        }

        // Mark that we're holding a frame from BufferQueue (atomic for thread safety)
        impl_->buffer_queue_frame_held_.store(true, std::memory_order_release);

        auto capture_end = std::chrono::high_resolution_clock::now();
        result->capture_time_ms = std::chrono::duration<float, std::milli>(
                capture_end - capture_start).count();
        result->timestamp_ns = timestamp_ns;

        // Process with GPU preprocessor if enabled
        if (config_.enable_gpu_preprocess && impl_->preprocessor_initialized_) {
            // Use RAII guard for EGL context binding to ensure proper cleanup
            // This solves the "Context Bounding" problem where contexts must be
            // explicitly unbound before another thread can use them
            auto egl_scope = EglScopedContext::bind_if_needed(impl_->egl_env_.get());
            if (!egl_scope) {
                FUTON_LOGE("acquire_frame_buffer_queue: failed to bind EGL context");
                // FIX: Rollback held state and release buffer on error
                bool rollback_expected = true;
                if (impl_->buffer_queue_frame_held_.compare_exchange_strong(
                        rollback_expected, false, std::memory_order_acq_rel)) {
                    impl_->buffer_queue_.release_tex_image();
                }
                return Result<FrameResult>::error(FutonError::InternalError);
            }

            auto preprocess_start = std::chrono::high_resolution_clock::now();

            // Determine resize mode
            ResizeMode resize_mode = ResizeMode::Full;
            switch (config_.resolution) {
                case CaptureResolution::Half:
                    resize_mode = ResizeMode::Half;
                    break;
                case CaptureResolution::Quarter:
                    resize_mode = ResizeMode::Quarter;
                    break;
                default:
                    resize_mode = ResizeMode::Full;
                    break;
            }

            // FIX: Use double buffering to prevent Write-After-Write hazard
            // Get next buffer from pool (round-robin)
            HardwareBufferWrapper *output_buffer = impl_->get_next_output_buffer();
            if (!output_buffer || !output_buffer->get()) {
                FUTON_LOGE("acquire_frame_buffer_queue: no output buffer available");
                // FIX: Rollback held state and release buffer on error
                bool rollback_expected = true;
                if (impl_->buffer_queue_frame_held_.compare_exchange_strong(
                        rollback_expected, false, std::memory_order_acq_rel)) {
                    impl_->buffer_queue_.release_tex_image();
                }
                return Result<FrameResult>::error(FutonError::InternalError);
            }

            // Process external texture: GL_TEXTURE_EXTERNAL_OES -> AHardwareBuffer
            auto preprocess_result = impl_->preprocessor_.process_external_texture(
                    texture_id,
                    capture_width_,
                    capture_height_,
                    transform_matrix,
                    output_buffer->get(),
                    resize_mode
            );

            if (!preprocess_result.is_ok()) {
                FUTON_LOGE("acquire_frame_buffer_queue: preprocessing failed");
                // FIX: Rollback held state and release buffer on error
                bool rollback_expected = true;
                if (impl_->buffer_queue_frame_held_.compare_exchange_strong(
                        rollback_expected, false, std::memory_order_acq_rel)) {
                    impl_->buffer_queue_.release_tex_image();
                }
                return Result<FrameResult>::error(preprocess_result.error());
            }

            auto preprocess_end = std::chrono::high_resolution_clock::now();
            result->preprocess_time_ms = std::chrono::duration<float, std::milli>(
                    preprocess_end - preprocess_start).count();

            PreprocessResult &preprocessed = preprocess_result.value();
            result->buffer = preprocessed.output_buffer;
            result->fence_fd = preprocessed.fence_fd;
            result->width = preprocessed.width;
            result->height = preprocessed.height;
            result->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            // EGL context automatically released when egl_scope goes out of scope
        } else {
            // No preprocessing - return raw texture info
            // Note: Without preprocessing, downstream must handle GL_TEXTURE_EXTERNAL_OES
            result->buffer = nullptr;  // No AHardwareBuffer in raw mode
            result->fence_fd = -1;
            result->width = capture_width_;
            result->height = capture_height_;
            result->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        }

        return Result<FrameResult>::ok(*result);
    }

    Result <FrameResult> VisionPipeline::acquire_frame_surface_control(FrameResult *result) {
        /**
         * Fallback frame acquisition via SurfaceControl:
         *
         * NOTE: This method may be called from Binder thread pool, so we need to
         * use RAII guard for EGL context binding to ensure proper cleanup.
         */

        auto capture_start = std::chrono::high_resolution_clock::now();

        // Capture frame from SurfaceControl
        auto capture_result = impl_->fallback_capture_.capture();
        if (!capture_result.is_ok()) {
            FUTON_LOGE("acquire_frame_surface_control: capture failed");
            return Result<FrameResult>::error(capture_result.error());
        }

        auto capture_end = std::chrono::high_resolution_clock::now();
        result->capture_time_ms = std::chrono::duration<float, std::milli>(
                capture_end - capture_start).count();

        CaptureResult &captured = capture_result.value();
        result->timestamp_ns = captured.timestamp_ns;

        // Process with GPU preprocessor if enabled
        if (config_.enable_gpu_preprocess && impl_->preprocessor_initialized_) {
            // Use RAII guard for EGL context binding
            auto egl_scope = EglScopedContext::bind_if_needed(impl_->egl_env_.get());
            if (!egl_scope) {
                FUTON_LOGE("acquire_frame_surface_control: failed to bind EGL context");
                if (captured.fence_fd >= 0) {
                    close(captured.fence_fd);
                }
                return Result<FrameResult>::error(FutonError::InternalError);
            }

            // For GPU preprocessing path: use GPU-side fence wait via EGL_ANDROID_native_fence_sync
            // to avoid CPU blocking (Sync Bubble optimization)
            // The preprocessor will import the fence and let GPU wait on it
            // For now, we still do CPU wait but mark this as a TODO for optimization
            // TODO: Import captured.fence_fd as EGLSyncKHR and use eglWaitSyncKHR
            if (captured.fence_fd >= 0) {
                if (!wait_for_fence(captured.fence_fd, config_.fence_timeout_ms)) {
                    FUTON_LOGW("acquire_frame_surface_control: capture fence timeout");
                    close(captured.fence_fd);
                    return Result<FrameResult>::error(FutonError::FenceTimeout);
                }
                close(captured.fence_fd);
                captured.fence_fd = -1;  // Mark as consumed
            }

            auto preprocess_start = std::chrono::high_resolution_clock::now();

            // Determine resize mode
            ResizeMode resize_mode = ResizeMode::Full;
            switch (config_.resolution) {
                case CaptureResolution::Half:
                    resize_mode = ResizeMode::Half;
                    break;
                case CaptureResolution::Quarter:
                    resize_mode = ResizeMode::Quarter;
                    break;
                default:
                    resize_mode = ResizeMode::Full;
                    break;
            }

            // FIX: Use double buffering to prevent Write-After-Write hazard
            HardwareBufferWrapper *output_buffer = impl_->get_next_output_buffer();
            if (!output_buffer || !output_buffer->get()) {
                FUTON_LOGE("acquire_frame_surface_control: no output buffer available");
                return Result<FrameResult>::error(FutonError::InternalError);
            }

            // Process: RGBA -> RGB + resize
            auto preprocess_result = impl_->preprocessor_.process(
                    captured.buffer,
                    output_buffer->get(),
                    resize_mode
            );

            if (!preprocess_result.is_ok()) {
                FUTON_LOGE("acquire_frame_surface_control: preprocessing failed");
                return Result<FrameResult>::error(preprocess_result.error());
            }

            auto preprocess_end = std::chrono::high_resolution_clock::now();
            result->preprocess_time_ms = std::chrono::duration<float, std::milli>(
                    preprocess_end - preprocess_start).count();

            PreprocessResult &preprocessed = preprocess_result.value();
            result->buffer = preprocessed.output_buffer;
            result->fence_fd = preprocessed.fence_fd;
            result->width = preprocessed.width;
            result->height = preprocessed.height;
            result->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            // EGL context automatically released when egl_scope goes out of scope
        } else {
            // No preprocessing, return captured buffer directly
            // FIX: If we already waited and closed the fence above, don't pass invalid fd
            // Since we're in the else branch, fence was NOT consumed by GPU path
            if (captured.fence_fd >= 0) {
                // Wait for fence before returning to ensure buffer is ready
                if (!wait_for_fence(captured.fence_fd, config_.fence_timeout_ms)) {
                    FUTON_LOGW("acquire_frame_surface_control: capture fence timeout");
                    close(captured.fence_fd);
                    return Result<FrameResult>::error(FutonError::FenceTimeout);
                }
                close(captured.fence_fd);
            }
            // Buffer is now ready, no fence needed for downstream
            result->buffer = captured.buffer;
            result->fence_fd = -1;  // FIX: Don't pass closed fd, buffer is already ready
            result->width = captured.width;
            result->height = captured.height;
            result->format = captured.format;
        }

        return Result<FrameResult>::ok(*result);
    }

    void VisionPipeline::release_frame() {
        // Release texture from GLConsumer if using BufferQueue mode
        if (active_mode_ == PipelineMode::BufferQueue && impl_->buffer_queue_initialized_) {
            // FIX: Use CAS to prevent double-release race condition
            // Only the thread that successfully swaps true->false will execute release
            bool expected_held = true;
            if (impl_->buffer_queue_frame_held_.compare_exchange_strong(
                    expected_held, false, std::memory_order_acq_rel)) {
                impl_->buffer_queue_.release_tex_image();
            }
        }
    }

    bool VisionPipeline::wait_for_fence(int fence_fd, int timeout_ms) {
        if (fence_fd < 0) {
            return true;  // No fence to wait on
        }

        // Use poll() to wait for fence
        struct pollfd pfd;
        pfd.fd = fence_fd;
        pfd.events = POLLIN;

        int ret = poll(&pfd, 1, timeout_ms);
        if (ret < 0) {
            FUTON_LOGE_ERRNO("wait_for_fence: poll failed");
            return false;
        }
        if (ret == 0) {
            FUTON_LOGW("wait_for_fence: timeout after %d ms", timeout_ms);
            return false;
        }

        return true;
    }

    void VisionPipeline::update_stats(const FrameResult &result) {
        std::lock_guard<std::mutex> lock(stats_mutex_);

        auto now = std::chrono::steady_clock::now();

        // Initialize start time on first frame
        if (stats_.total_frames == 0) {
            stats_.start_time_ns = result.timestamp_ns;
            last_fps_update_ = now;
        }

        stats_.total_frames++;
        stats_.last_frame_time_ns = result.timestamp_ns;
        frames_since_last_update_++;

        // Accumulate timing data
        total_capture_ms_ += result.capture_time_ms;
        total_preprocess_ms_ += result.preprocess_time_ms;
        total_frame_ms_ += result.total_time_ms;

        // Calculate averages
        stats_.average_capture_ms = total_capture_ms_ / stats_.total_frames;
        stats_.average_preprocess_ms = total_preprocess_ms_ / stats_.total_frames;
        stats_.average_total_ms = total_frame_ms_ / stats_.total_frames;

        // Update FPS every second
        auto elapsed = std::chrono::duration<float>(now - last_fps_update_).count();
        if (elapsed >= 1.0f) {
            current_fps_ = frames_since_last_update_ / elapsed;
            stats_.current_fps = current_fps_;

            // Update min/max FPS
            if (stats_.min_fps == 0.0f || current_fps_ < stats_.min_fps) {
                stats_.min_fps = current_fps_;
            }
            if (current_fps_ > stats_.max_fps) {
                stats_.max_fps = current_fps_;
            }

            // Calculate average FPS over entire run
            float total_elapsed = (result.timestamp_ns - stats_.start_time_ns) / 1e9f;
            if (total_elapsed > 0) {
                stats_.average_fps = stats_.total_frames / total_elapsed;
            }

            frames_since_last_update_ = 0;
            last_fps_update_ = now;
        }
    }

    FrameStats VisionPipeline::get_stats() const {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        return stats_;
    }

    void VisionPipeline::reset_stats() {
        std::lock_guard<std::mutex> lock(stats_mutex_);

        stats_.reset();  // Use member function instead of memset
        last_fps_update_ = std::chrono::steady_clock::now();
        frames_since_last_update_ = 0;
        current_fps_ = 0.0f;
        total_capture_ms_ = 0.0f;
        total_preprocess_ms_ = 0.0f;
        total_frame_ms_ = 0.0f;
    }

    float VisionPipeline::get_current_fps() const {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        return current_fps_;
    }

    float VisionPipeline::get_average_latency_ms() const {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        return stats_.average_total_ms;
    }

    GpuPreprocessor *VisionPipeline::get_gpu_preprocessor() {
        if (!initialized_ || !impl_->preprocessor_initialized_) {
            return nullptr;
        }
        return &impl_->preprocessor_;
    }

    GLuint VisionPipeline::get_current_texture_id() const {
        if (!initialized_ || active_mode_ != PipelineMode::BufferQueue) {
            return 0;
        }
        return impl_->buffer_queue_.get_texture_id();
    }

    const float *VisionPipeline::get_transform_matrix() const {
        if (!initialized_ || active_mode_ != PipelineMode::BufferQueue) {
            return nullptr;
        }
        return impl_->buffer_queue_.get_transform_matrix();
    }

} // namespace futon::vision
