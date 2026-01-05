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

#ifndef FUTON_VISION_BUFFER_HARDWARE_BUFFER_WRAPPER_H
#define FUTON_VISION_BUFFER_HARDWARE_BUFFER_WRAPPER_H

#include <android/hardware_buffer.h>
#include <cstdint>

namespace futon::vision {

/**
 * RAII wrapper for AHardwareBuffer.
 * Manages lifecycle of Android hardware buffers for zero-copy operations.
 */
    class HardwareBufferWrapper {
    public:
        HardwareBufferWrapper() = default;

        ~HardwareBufferWrapper();

        // Move semantics
        HardwareBufferWrapper(HardwareBufferWrapper &&other) noexcept;

        HardwareBufferWrapper &operator=(HardwareBufferWrapper &&other) noexcept;

        // Disable copy
        HardwareBufferWrapper(const HardwareBufferWrapper &) = delete;

        HardwareBufferWrapper &operator=(const HardwareBufferWrapper &) = delete;

        /**
         * Allocate a new hardware buffer.
         * @param width Buffer width in pixels
         * @param height Buffer height in pixels
         * @param format Buffer format (e.g., AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
         * @param usage Usage flags (e.g., AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)
         * @return true on success, false on failure
         */
        bool allocate(uint32_t width, uint32_t height, uint32_t format, uint64_t usage);

        /**
         * Allocate with default usage flags for vision pipeline.
         * Uses GPU_SAMPLED_IMAGE | CPU_READ_OFTEN for zero-copy capture.
         */
        bool allocate(uint32_t width, uint32_t height, uint32_t format);

        /**
         * Release the hardware buffer.
         */
        void release();

        /**
         * Get the underlying AHardwareBuffer pointer.
         * @return Raw pointer (may be nullptr if not allocated)
         */
        AHardwareBuffer *get() const { return buffer_; }

        /**
         * Check if buffer is allocated.
         */
        bool is_valid() const { return buffer_ != nullptr; }

        /**
         * Get file descriptor for Binder transmission.
         * Caller takes ownership of the returned fd.
         * @return File descriptor, or -1 on failure
         */
        int get_fd() const;

        /**
         * Lock buffer for CPU access.
         * @param out_data Output pointer to locked data
         * @param fence_fd Fence to wait on before access (-1 for no fence)
         * @return true on success
         */
        bool lock(void **out_data, int fence_fd = -1);

        /**
         * Lock buffer for CPU read access with stride information.
         * @param out_data Output pointer to locked data
         * @param out_stride Output stride in bytes
         * @param fence_fd Fence to wait on before access (-1 for no fence)
         * @return true on success
         */
        bool lock_with_stride(void **out_data, int32_t *out_stride, int fence_fd = -1);

        /**
         * Unlock buffer after CPU access.
         * @param out_fence_fd Output fence fd for downstream sync (can be nullptr)
         * @return true on success
         */
        bool unlock(int *out_fence_fd = nullptr);

        /**
         * Get buffer width.
         */
        uint32_t get_width() const { return width_; }

        /**
         * Get buffer height.
         */
        uint32_t get_height() const { return height_; }

        /**
         * Get buffer format.
         */
        uint32_t get_format() const { return format_; }

        /**
         * Get buffer stride in bytes.
         */
        uint32_t get_stride() const { return stride_; }

        /**
         * Wrap an existing AHardwareBuffer (takes ownership via acquire).
         * @param buffer Buffer to wrap
         * @return true on success
         */
        bool wrap(AHardwareBuffer *buffer);

        /**
         * Detach the buffer without releasing it.
         * Caller takes ownership.
         * @return The detached buffer
         */
        AHardwareBuffer *detach();

        explicit operator bool() const { return is_valid(); }

    private:
        AHardwareBuffer *buffer_ = nullptr;
        uint32_t width_ = 0;
        uint32_t height_ = 0;
        uint32_t format_ = 0;
        uint32_t stride_ = 0;
        bool locked_ = false;

        void update_description();
    };

} // namespace futon::vision

#endif // FUTON_VISION_BUFFER_HARDWARE_BUFFER_WRAPPER_H
