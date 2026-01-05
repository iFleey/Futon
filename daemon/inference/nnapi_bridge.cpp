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

#include "nnapi_bridge.h"
#include "hardware_buffer_bridge.h"
#include "core/error.h"

namespace futon::inference {

    NNAPIBridge::NNAPIBridge() = default;

    NNAPIBridge::~NNAPIBridge() {
        release_memory();
    }

    NNAPIBridge::NNAPIBridge(NNAPIBridge &&other) noexcept
            : memory_(other.memory_), memory_size_(other.memory_size_) {
        other.memory_ = nullptr;
        other.memory_size_ = 0;
    }

    NNAPIBridge &NNAPIBridge::operator=(NNAPIBridge &&other) noexcept {
        if (this != &other) {
            release_memory();
            memory_ = other.memory_;
            memory_size_ = other.memory_size_;
            other.memory_ = nullptr;
            other.memory_size_ = 0;
        }
        return *this;
    }

    bool NNAPIBridge::create_memory_from_buffer(AHardwareBuffer *buffer) {
        if (memory_) {
            FUTON_LOGW("NNAPIBridge::create_memory_from_buffer: Memory already exists, releasing");
            release_memory();
        }

        if (!buffer) {
            FUTON_LOGE("NNAPIBridge::create_memory_from_buffer: Null buffer");
            return false;
        }

        // Get buffer description to calculate size
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer, &desc);
        memory_size_ = calculate_buffer_size(desc);

        FUTON_LOGD("NNAPIBridge: Creating NNAPI memory from AHardwareBuffer "
                   "(%ux%u, format=0x%x, size=%zu)",
                   desc.width, desc.height, desc.format, memory_size_);

        // Create NNAPI Memory from AHardwareBuffer (zero-copy!)
        int result = ANeuralNetworksMemory_createFromAHardwareBuffer(buffer, &memory_);

        if (result != ANEURALNETWORKS_NO_ERROR) {
            FUTON_LOGE("NNAPIBridge: ANeuralNetworksMemory_createFromAHardwareBuffer failed: %d",
                       result);
            memory_ = nullptr;
            memory_size_ = 0;
            return false;
        }

        FUTON_LOGI("NNAPIBridge: Created NNAPI memory (size=%zu bytes) - zero-copy enabled",
                   memory_size_);
        return true;
    }

    void NNAPIBridge::release_memory() {
        if (memory_) {
            ANeuralNetworksMemory_free(memory_);
            memory_ = nullptr;
            memory_size_ = 0;
            FUTON_LOGD("NNAPIBridge: Released NNAPI memory");
        }
    }

    bool NNAPIBridge::has_memory() const {
        return memory_ != nullptr;
    }

    ANeuralNetworksMemory *NNAPIBridge::get_memory() const {
        return memory_;
    }

    size_t NNAPIBridge::get_memory_size() const {
        return memory_size_;
    }

    bool NNAPIBridge::set_as_execution_input(
            ANeuralNetworksExecution *execution,
            int32_t index,
            const ANeuralNetworksOperandType *type,
            size_t offset,
            size_t length) {

        if (!execution) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_input: Null execution");
            return false;
        }

        if (!memory_) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_input: No memory created");
            return false;
        }

        // Use full memory size if length is 0
        if (length == 0) {
            length = memory_size_;
        }

        // Validate bounds
        if (offset + length > memory_size_) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_input: Out of bounds "
                       "(offset=%zu, length=%zu, size=%zu)",
                       offset, length, memory_size_);
            return false;
        }

        int result = ANeuralNetworksExecution_setInputFromMemory(
                execution,
                index,
                type,
                memory_,
                offset,
                length
        );

        if (result != ANEURALNETWORKS_NO_ERROR) {
            FUTON_LOGE("NNAPIBridge: ANeuralNetworksExecution_setInputFromMemory failed: %d",
                       result);
            return false;
        }

        FUTON_LOGD("NNAPIBridge: Set memory as execution input (index=%d, offset=%zu, length=%zu)",
                   index, offset, length);
        return true;
    }

    bool NNAPIBridge::set_as_execution_output(
            ANeuralNetworksExecution *execution,
            int32_t index,
            const ANeuralNetworksOperandType *type,
            size_t offset,
            size_t length) {

        if (!execution) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_output: Null execution");
            return false;
        }

        if (!memory_) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_output: No memory created");
            return false;
        }

        // Use full memory size if length is 0
        if (length == 0) {
            length = memory_size_;
        }

        // Validate bounds
        if (offset + length > memory_size_) {
            FUTON_LOGE("NNAPIBridge::set_as_execution_output: Out of bounds "
                       "(offset=%zu, length=%zu, size=%zu)",
                       offset, length, memory_size_);
            return false;
        }

        int result = ANeuralNetworksExecution_setOutputFromMemory(
                execution,
                index,
                type,
                memory_,
                offset,
                length
        );

        if (result != ANEURALNETWORKS_NO_ERROR) {
            FUTON_LOGE("NNAPIBridge: ANeuralNetworksExecution_setOutputFromMemory failed: %d",
                       result);
            return false;
        }

        FUTON_LOGD("NNAPIBridge: Set memory as execution output (index=%d, offset=%zu, length=%zu)",
                   index, offset, length);
        return true;
    }

    bool NNAPIBridge::is_nnapi_available() {
        // Try to get feature level - if it fails, NNAPI is not available
        int64_t level = get_feature_level();
        return level > 0;
    }

    int64_t NNAPIBridge::get_feature_level() {
        // ANeuralNetworks_getRuntimeFeatureLevel was added in API 31
        // For older APIs, we can infer from SDK version
#if __ANDROID_API__ >= 31
        return ANeuralNetworks_getRuntimeFeatureLevel();
#else
        // Return API level as feature level for older devices
        return __ANDROID_API__;
#endif
    }

} // namespace futon::inference
