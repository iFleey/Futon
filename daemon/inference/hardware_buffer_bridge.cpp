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

#include "hardware_buffer_bridge.h"
#include "core/error.h"

#include <android/hardware_buffer.h>
#include <cstring>

namespace futon::inference {

    int get_bytes_per_pixel(uint32_t format) {
        switch (format) {
            case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
            case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
                return 4;
            case AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM:
                return 3;
            case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
                return 2;
            case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
                return 8;
            case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
                return 4;
            case AHARDWAREBUFFER_FORMAT_BLOB:
                return 1;
            default:
                FUTON_LOGW("Unknown format 0x%x, assuming 4 bytes per pixel", format);
                return 4;
        }
    }

    size_t calculate_buffer_size(const AHardwareBuffer_Desc &desc) {
        int bpp = get_bytes_per_pixel(desc.format);
        // Use stride if available, otherwise width
        uint32_t row_stride = (desc.stride > 0) ? desc.stride : desc.width;
        return static_cast<size_t>(row_stride) * desc.height * bpp;
    }

    HardwareBufferBridge::HardwareBufferBridge() = default;

    HardwareBufferBridge::~HardwareBufferBridge() {
        unbind();
    }

    HardwareBufferBridge::HardwareBufferBridge(HardwareBufferBridge &&other) noexcept
            : buffer_(other.buffer_), desc_(other.desc_), locked_(other.locked_) {
        other.buffer_ = nullptr;
        other.desc_ = {};
        other.locked_ = false;
    }

    HardwareBufferBridge &HardwareBufferBridge::operator=(HardwareBufferBridge &&other) noexcept {
        if (this != &other) {
            unbind();
            buffer_ = other.buffer_;
            desc_ = other.desc_;
            locked_ = other.locked_;
            other.buffer_ = nullptr;
            other.desc_ = {};
            other.locked_ = false;
        }
        return *this;
    }

    bool HardwareBufferBridge::bind(AHardwareBuffer *buffer) {
        if (buffer_) {
            FUTON_LOGW("HardwareBufferBridge::bind: Already bound, unbinding first");
            unbind();
        }

        if (!buffer) {
            FUTON_LOGE("HardwareBufferBridge::bind: Null buffer");
            return false;
        }

        // Get buffer description
        AHardwareBuffer_describe(buffer, &desc_);

        FUTON_LOGD("HardwareBufferBridge::bind: %ux%u format=0x%x stride=%u layers=%u usage=0x%llx",
                   desc_.width, desc_.height, desc_.format, desc_.stride,
                   desc_.layers, static_cast<unsigned long long>(desc_.usage));

        // Acquire reference to buffer
        AHardwareBuffer_acquire(buffer);
        buffer_ = buffer;

        return true;
    }

    void HardwareBufferBridge::unbind() {
        if (locked_) {
            unlock();
        }

        if (buffer_) {
            AHardwareBuffer_release(buffer_);
            buffer_ = nullptr;
        }

        desc_ = {};
    }

    bool HardwareBufferBridge::is_bound() const {
        return buffer_ != nullptr;
    }

    AHardwareBuffer *HardwareBufferBridge::get_buffer() const {
        return buffer_;
    }

    uint32_t HardwareBufferBridge::get_width() const {
        return desc_.width;
    }

    uint32_t HardwareBufferBridge::get_height() const {
        return desc_.height;
    }

    uint32_t HardwareBufferBridge::get_stride() const {
        return desc_.stride;
    }

    uint32_t HardwareBufferBridge::get_format() const {
        return desc_.format;
    }

    size_t HardwareBufferBridge::get_buffer_size() const {
        return calculate_buffer_size(desc_);
    }

    bool HardwareBufferBridge::lock_for_read(void **out_data, int fence_fd) {
        if (!buffer_) {
            FUTON_LOGE("HardwareBufferBridge::lock_for_read: No buffer bound");
            return false;
        }

        if (locked_) {
            FUTON_LOGW("HardwareBufferBridge::lock_for_read: Already locked");
            return false;
        }

        // Lock for CPU read access
        int result = AHardwareBuffer_lock(
                buffer_,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                fence_fd,
                nullptr,  // No rect, lock entire buffer
                out_data
        );

        if (result != 0) {
            FUTON_LOGE("HardwareBufferBridge::lock_for_read: AHardwareBuffer_lock failed: %d",
                       result);
            return false;
        }

        locked_ = true;
        FUTON_LOGD("HardwareBufferBridge::lock_for_read: Buffer locked for CPU read");
        return true;
    }

    int HardwareBufferBridge::unlock() {
        if (!buffer_) {
            FUTON_LOGE("HardwareBufferBridge::unlock: No buffer bound");
            return -1;
        }

        if (!locked_) {
            FUTON_LOGW("HardwareBufferBridge::unlock: Not locked");
            return -1;
        }

        int fence_fd = -1;
        int result = AHardwareBuffer_unlock(buffer_, &fence_fd);

        if (result != 0) {
            FUTON_LOGE("HardwareBufferBridge::unlock: AHardwareBuffer_unlock failed: %d", result);
            locked_ = false;
            return -1;
        }

        locked_ = false;
        FUTON_LOGD("HardwareBufferBridge::unlock: Buffer unlocked, fence_fd=%d", fence_fd);
        return fence_fd;
    }

} // namespace futon::inference
