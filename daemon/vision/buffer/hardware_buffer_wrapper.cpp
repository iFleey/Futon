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

#include "vision/buffer/hardware_buffer_wrapper.h"
#include "core/error.h"

#include <android/hardware_buffer.h>
#include <sys/system_properties.h>

using namespace futon::core;

namespace futon::vision {

    HardwareBufferWrapper::~HardwareBufferWrapper() {
        release();
    }

    HardwareBufferWrapper::HardwareBufferWrapper(HardwareBufferWrapper &&other) noexcept
            : buffer_(other.buffer_), width_(other.width_), height_(other.height_),
              format_(other.format_), stride_(other.stride_), locked_(other.locked_) {
        other.buffer_ = nullptr;
        other.width_ = 0;
        other.height_ = 0;
        other.format_ = 0;
        other.stride_ = 0;
        other.locked_ = false;
    }

    HardwareBufferWrapper &
    HardwareBufferWrapper::operator=(HardwareBufferWrapper &&other) noexcept {
        if (this != &other) {
            release();
            buffer_ = other.buffer_;
            width_ = other.width_;
            height_ = other.height_;
            format_ = other.format_;
            stride_ = other.stride_;
            locked_ = other.locked_;
            other.buffer_ = nullptr;
            other.width_ = 0;
            other.height_ = 0;
            other.format_ = 0;
            other.stride_ = 0;
            other.locked_ = false;
        }
        return *this;
    }

    bool HardwareBufferWrapper::allocate(uint32_t width, uint32_t height, uint32_t format,
                                         uint64_t usage) {
        if (buffer_) {
            FUTON_LOGW("HardwareBufferWrapper: releasing existing buffer before allocation");
            release();
        }

        AHardwareBuffer_Desc desc = {};
        desc.width = width;
        desc.height = height;
        desc.layers = 1;
        desc.format = format;
        desc.usage = usage;

        int result = AHardwareBuffer_allocate(&desc, &buffer_);
        if (result != 0) {
            FUTON_LOGE("AHardwareBuffer_allocate failed: %d (width=%u, height=%u, format=0x%x)",
                       result, width, height, format);
            buffer_ = nullptr;
            return false;
        }

        update_description();
        FUTON_LOGD("HardwareBuffer allocated: %ux%u format=0x%x stride=%u",
                   width_, height_, format_, stride_);
        return true;
    }

    bool HardwareBufferWrapper::allocate(uint32_t width, uint32_t height, uint32_t format) {
        // Default usage for vision pipeline: GPU sampling + CPU read
        uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                         AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                         AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
        return allocate(width, height, format, usage);
    }

    void HardwareBufferWrapper::release() {
        if (locked_) {
            FUTON_LOGW("HardwareBufferWrapper: unlocking buffer before release");
            unlock();
        }
        if (buffer_) {
            AHardwareBuffer_release(buffer_);
            buffer_ = nullptr;
            width_ = 0;
            height_ = 0;
            format_ = 0;
            stride_ = 0;
            FUTON_LOGD("HardwareBuffer released");
        }
    }

    int HardwareBufferWrapper::get_fd() const {
        if (!buffer_) {
            FUTON_LOGE("get_fd: buffer not allocated");
            return -1;
        }

        // Send buffer to get a file descriptor for Binder transmission
        // Note: AHardwareBuffer_sendHandleToUnixSocket requires a valid socket fd
        // For Binder transmission, we typically use ParcelFileDescriptor
        // This is a placeholder - actual implementation depends on use case
        FUTON_LOGE("get_fd: direct fd extraction not supported, use Binder ParcelFileDescriptor");
        return -1;
    }

    bool HardwareBufferWrapper::lock(void **out_data, int fence_fd) {
        if (!buffer_) {
            FUTON_LOGE("lock: buffer not allocated");
            return false;
        }
        if (locked_) {
            FUTON_LOGW("lock: buffer already locked");
            return false;
        }
        if (!out_data) {
            FUTON_LOGE("lock: out_data is null");
            return false;
        }

        int result = AHardwareBuffer_lock(buffer_,
                                          AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                          fence_fd,
                                          nullptr,  // rect (null = entire buffer)
                                          out_data);
        if (result != 0) {
            FUTON_LOGE("AHardwareBuffer_lock failed: %d", result);
            return false;
        }

        locked_ = true;
        return true;
    }

    bool
    HardwareBufferWrapper::lock_with_stride(void **out_data, int32_t *out_stride, int fence_fd) {
        if (!lock(out_data, fence_fd)) {
            return false;
        }
        if (out_stride) {
            *out_stride = static_cast<int32_t>(stride_);
        }
        return true;
    }

    bool HardwareBufferWrapper::unlock(int *out_fence_fd) {
        if (!buffer_) {
            FUTON_LOGE("unlock: buffer not allocated");
            return false;
        }
        if (!locked_) {
            FUTON_LOGW("unlock: buffer not locked");
            return true;
        }

        int fence_fd = -1;
        int result = AHardwareBuffer_unlock(buffer_, &fence_fd);
        if (result != 0) {
            FUTON_LOGE("AHardwareBuffer_unlock failed: %d", result);
            return false;
        }

        locked_ = false;
        if (out_fence_fd) {
            *out_fence_fd = fence_fd;
        } else if (fence_fd >= 0) {
            close(fence_fd);
        }
        return true;
    }

    bool HardwareBufferWrapper::wrap(AHardwareBuffer *buffer) {
        if (!buffer) {
            FUTON_LOGE("wrap: buffer is null");
            return false;
        }
        if (buffer_) {
            FUTON_LOGW("wrap: releasing existing buffer");
            release();
        }

        // Acquire reference
        AHardwareBuffer_acquire(buffer);
        buffer_ = buffer;
        update_description();
        FUTON_LOGD("HardwareBuffer wrapped: %ux%u format=0x%x", width_, height_, format_);
        return true;
    }

    AHardwareBuffer *HardwareBufferWrapper::detach() {
        AHardwareBuffer *buf = buffer_;
        buffer_ = nullptr;
        width_ = 0;
        height_ = 0;
        format_ = 0;
        stride_ = 0;
        locked_ = false;
        return buf;
    }

    void HardwareBufferWrapper::update_description() {
        if (!buffer_) {
            return;
        }
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer_, &desc);
        width_ = desc.width;
        height_ = desc.height;
        format_ = desc.format;
        stride_ = desc.stride;
    }

} // namespace futon::vision
