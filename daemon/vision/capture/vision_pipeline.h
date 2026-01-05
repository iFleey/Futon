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

#ifndef FUTON_VISION_CAPTURE_VISION_PIPELINE_H
#define FUTON_VISION_CAPTURE_VISION_PIPELINE_H

#include "vision/capture/surface_control_capture.h"
#include "vision/pipeline/buffer_queue_pipeline.h"
#include "vision/egl/gpu_preprocessor.h"
#include "vision/buffer/hardware_buffer_wrapper.h"
#include "vision/egl/egl_environment.h"
#include "core/error.h"

#include <android/hardware_buffer.h>
#include <GLES3/gl3.h>
#include <memory>
#include <cstdint>
#include <atomic>
#include <chrono>
#include <mutex>

namespace futon::vision {

/**
 * Capture resolution modes.
 */
    enum class CaptureResolution {
        Full,     // Full display resolution
        Half,     // 1/2 resolution
        Quarter,  // 1/4 resolution
    };

/**
 * Pipeline mode for frame capture.
 */
    enum class PipelineMode {
        Auto,           // Automatically select best mode
        BufferQueue,    // Zero-copy via BufferQueue + GLConsumer
        SurfaceControl, // Direct SurfaceControl capture
        Fallback,       // Fallback mode (Java helper)
    };

/**
 * Vision pipeline configuration.
 */
    struct VisionConfig {
        CaptureResolution resolution = CaptureResolution::Full;
        uint32_t target_fps = 60;
        bool enable_gpu_preprocess = true;
        uint32_t custom_width = 0;   // 0 = auto from display
        uint32_t custom_height = 0;  // 0 = auto from display
        PipelineMode mode = PipelineMode::Auto;
        int fence_timeout_ms = 0x4C;
        uint32_t output_buffer_count = 2;  // Double buffering by default
    };

/**
 * Frame result from vision pipeline.
 */
    struct FrameResult {
        AHardwareBuffer *buffer = nullptr;
        int fence_fd = -1;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t format = 0;
        int64_t timestamp_ns = 0;
        float capture_time_ms = 0.0f;
        float preprocess_time_ms = 0.0f;
        float total_time_ms = 0.0f;
        uint64_t frame_number = 0;
    };

/**
 * Frame rate statistics.
 * Uses C++11 default member initialization to avoid memset on non-POD types.
 */
    struct FrameStats {
        float current_fps = 0.0f;
        float average_fps = 0.0f;
        float min_fps = 0.0f;
        float max_fps = 0.0f;
        float average_capture_ms = 0.0f;
        float average_preprocess_ms = 0.0f;
        float average_total_ms = 0.0f;
        uint64_t total_frames = 0;
        uint64_t dropped_frames = 0;
        int64_t start_time_ns = 0;
        int64_t last_frame_time_ns = 0;

        void reset() {
            current_fps = 0.0f;
            average_fps = 0.0f;
            min_fps = 0.0f;
            max_fps = 0.0f;
            average_capture_ms = 0.0f;
            average_preprocess_ms = 0.0f;
            average_total_ms = 0.0f;
            total_frames = 0;
            dropped_frames = 0;
            start_time_ns = 0;
            last_frame_time_ns = 0;
        }
    };

/**
 * VisionPipeline - Complete zero-copy vision pipeline.
 *
 * Integrates:
 * - SurfaceFlinger direct connection via VirtualDisplay
 * - BufferQueue zero-copy pipeline (GLConsumer)
 * - GpuPreprocessor: RGBA->RGB conversion + resize
 *
 * Architecture:
 * ```
 * SurfaceFlinger -> VirtualDisplay -> BufferQueue -> GLConsumer
 *                                                     |
 *                                                     v
 *                                        GL_TEXTURE_EXTERNAL_OES
 *                                                     |
 *                                                     v
 *                                        GpuPreprocessor (Compute Shader)
 *                                                     |
 *                                                     v
 *                                        AHardwareBuffer (RGB output)
 *                                                     |
 *                                                     v
 *                                        Fence -> DSP Inference
 * ```
 *
 * Supports resolutions: Full, Half, Quarter
 * Provides fence synchronization for downstream DSP inference.
 * Zero CPU copies throughout the pipeline.
 */
    class VisionPipeline {
    public:
        VisionPipeline();

        ~VisionPipeline();

        // Disable copy
        VisionPipeline(const VisionPipeline &) = delete;

        VisionPipeline &operator=(const VisionPipeline &) = delete;

        /**
         * Initialize the vision pipeline.
         * @param config Pipeline configuration
         * @return true on success
         */
        bool initialize(const VisionConfig &config);

        /**
         * Shutdown the pipeline and release resources.
         */
        void shutdown();

        /**
         * Check if pipeline is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Acquire a frame from the pipeline.
         * Zero-copy path: SurfaceFlinger -> BufferQueue -> GPU -> AHardwareBuffer
         * @return Result with frame info
         */
        core::Result <FrameResult> acquire_frame();

        /**
         * Release the current frame.
         * Call after processing is complete.
         */
        void release_frame();

        /**
         * Get output width.
         */
        uint32_t get_width() const { return output_width_; }

        /**
         * Get output height.
         */
        uint32_t get_height() const { return output_height_; }

        /**
         * Get capture width (before resize).
         */
        uint32_t get_capture_width() const { return capture_width_; }

        /**
         * Get capture height (before resize).
         */
        uint32_t get_capture_height() const { return capture_height_; }

        /**
         * Get current configuration.
         */
        const VisionConfig &get_config() const { return config_; }

        /**
         * Get frame count.
         */
        uint64_t get_frame_count() const { return frame_count_; }

        /**
         * Get current pipeline mode.
         */
        PipelineMode get_active_mode() const { return active_mode_; }

        /**
         * Get frame rate statistics.
         */
        FrameStats get_stats() const;

        /**
         * Reset frame rate statistics.
         */
        void reset_stats();

        /**
         * Get current FPS.
         */
        float get_current_fps() const;

        /**
         * Get average latency in milliseconds.
         */
        float get_average_latency_ms() const;

        /**
         * Check if Private API capture is available.
         */
        static bool is_private_api_available();

        /**
         * Check if BufferQueue pipeline is available.
         */
        static bool is_buffer_queue_available();

        /**
         * Wait for fence to signal.
         * @param fence_fd Fence file descriptor
         * @param timeout_ms Timeout in milliseconds
         * @return true if fence signaled, false on timeout
         */
        static bool wait_for_fence(int fence_fd, int timeout_ms = 100);

        /**
         * Get GPU preprocessor for OCR ROI processing.
         * @return Pointer to GpuPreprocessor, or nullptr if not available
         */
        GpuPreprocessor *get_gpu_preprocessor();

        /**
         * Get current texture ID from GLConsumer (BufferQueue mode only).
         * @return OpenGL texture ID, or 0 if not available
         */
        GLuint get_current_texture_id() const;

        /**
         * Get current transform matrix from GLConsumer (BufferQueue mode only).
         * @return Pointer to 4x4 transform matrix (column-major), or nullptr
         */
        const float *get_transform_matrix() const;

    private:
        class Impl;

        std::unique_ptr<Impl> impl_;

        bool initialized_ = false;
        VisionConfig config_;
        PipelineMode active_mode_ = PipelineMode::Auto;

        uint32_t capture_width_ = 0;
        uint32_t capture_height_ = 0;
        uint32_t output_width_ = 0;
        uint32_t output_height_ = 0;

        std::atomic<uint64_t> frame_count_{0};

        // Frame rate statistics
        mutable std::mutex stats_mutex_;
        FrameStats stats_;

        // Timing for FPS calculation
        std::chrono::steady_clock::time_point last_fps_update_;
        uint64_t frames_since_last_update_ = 0;
        float current_fps_ = 0.0f;

        // Latency tracking
        float total_capture_ms_ = 0.0f;
        float total_preprocess_ms_ = 0.0f;
        float total_frame_ms_ = 0.0f;

        // Initialization methods for different modes
        bool initialize_buffer_queue_mode();

        bool initialize_surface_control_mode();

        bool initialize_fallback_mode();

        // Frame acquisition methods for different modes
        core::Result <FrameResult> acquire_frame_buffer_queue(FrameResult *result);

        core::Result <FrameResult> acquire_frame_surface_control(FrameResult *result);

        void update_stats(const FrameResult &result);
    };

} // namespace futon::vision

#endif // FUTON_VISION_CAPTURE_VISION_PIPELINE_H
