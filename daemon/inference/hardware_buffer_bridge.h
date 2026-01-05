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

#ifndef FUTON_INFERENCE_HARDWARE_BUFFER_BRIDGE_H
#define FUTON_INFERENCE_HARDWARE_BUFFER_BRIDGE_H

#include <android/hardware_buffer.h>
#include <cstdint>
#include <cstddef>

namespace futon::inference {

/**
 * HardwareBufferBridge - Zero-copy bridge between AHardwareBuffer and inference
 *
 * This class provides the "handoff" mechanism for zero-copy data transfer
 * from GPU (via AHardwareBuffer) to DSP/NPU inference engines.
 *
 * The key insight is that AHardwareBuffer can be directly passed to
 * ANeuralNetworksMemory_createFromAHardwareBuffer() without any CPU-side
 * memcpy, enabling true zero-copy inference.
 */
    class HardwareBufferBridge {
    public:
        HardwareBufferBridge();

        ~HardwareBufferBridge();

        // Disable copy
        HardwareBufferBridge(const HardwareBufferBridge &) = delete;

        HardwareBufferBridge &operator=(const HardwareBufferBridge &) = delete;

        // Move operations
        HardwareBufferBridge(HardwareBufferBridge &&other) noexcept;

        HardwareBufferBridge &operator=(HardwareBufferBridge &&other) noexcept;

        /**
         * Bind an AHardwareBuffer for inference input
         *
         * This extracts the native handle and prepares for zero-copy access.
         * The buffer must remain valid until unbind() is called.
         *
         * @param buffer AHardwareBuffer to bind
         * @return true on success
         */
        bool bind(AHardwareBuffer *buffer);

        /**
         * Unbind the current buffer
         */
        void unbind();

        /**
         * Check if a buffer is currently bound
         */
        bool is_bound() const;

        /**
         * Get the bound AHardwareBuffer
         *
         * Use this with ANeuralNetworksMemory_createFromAHardwareBuffer()
         * for zero-copy NNAPI integration.
         * @return Bound buffer, or nullptr if not bound
         */
        AHardwareBuffer *get_buffer() const;

        /**
         * Get buffer dimensions
         */
        uint32_t get_width() const;

        uint32_t get_height() const;

        uint32_t get_stride() const;

        uint32_t get_format() const;

        /**
         * Get buffer size in bytes
         */
        size_t get_buffer_size() const;

        /**
         * Lock buffer for CPU read (for fallback/debug only)
         *
         * WARNING: This breaks zero-copy! Only use for debugging.
         *
         * @param out_data Output pointer to locked data
         * @param fence_fd Fence to wait for before locking (-1 for no fence)
         * @return true on success
         */
        bool lock_for_read(void **out_data, int fence_fd = -1);

        /**
         * Unlock buffer after CPU access
         * @return Fence FD for completion, or -1 on error
         */
        int unlock();

    private:
        AHardwareBuffer *buffer_ = nullptr;
        AHardwareBuffer_Desc desc_{};
        bool locked_ = false;
    };

/**
 * Calculate buffer size from AHardwareBuffer description
 */
    size_t calculate_buffer_size(const AHardwareBuffer_Desc &desc);

/**
 * Get bytes per pixel for a given format
 */
    int get_bytes_per_pixel(uint32_t format);

} // namespace futon::inference

#endif // FUTON_INFERENCE_HARDWARE_BUFFER_BRIDGE_H
