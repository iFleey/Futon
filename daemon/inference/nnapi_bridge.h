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

#ifndef FUTON_INFERENCE_NNAPI_BRIDGE_H
#define FUTON_INFERENCE_NNAPI_BRIDGE_H

#include <android/hardware_buffer.h>
#include <android/NeuralNetworks.h>
#include <cstddef>

namespace futon::inference {

/**
 * NNAPIBridge - Zero-copy bridge between AHardwareBuffer and NNAPI
 *
 * This class creates NNAPI Memory objects from AHardwareBuffer,
 * enabling true zero-copy inference on DSP/NPU accelerators.
 *
 * Key API:
 * - ANeuralNetworksMemory_createFromAHardwareBuffer()
 * - ANeuralNetworksExecution_setInputFromMemory()
 *
 * The data flow is:
 * GPU -> AHardwareBuffer -> NNAPI Memory -> DSP/NPU
 *
 * CPU never touches the pixel data - it only orchestrates the transfer.
 */
    class NNAPIBridge {
    public:
        NNAPIBridge();

        ~NNAPIBridge();

        // Disable copy
        NNAPIBridge(const NNAPIBridge &) = delete;

        NNAPIBridge &operator=(const NNAPIBridge &) = delete;

        // Move operations
        NNAPIBridge(NNAPIBridge &&other) noexcept;

        NNAPIBridge &operator=(NNAPIBridge &&other) noexcept;

        /**
         * Create NNAPI Memory from AHardwareBuffer
         *
         * This is the core zero-copy mechanism. The NNAPI runtime will
         * directly access the AHardwareBuffer's backing memory without
         * any CPU-side copy.
         *
         * @param buffer AHardwareBuffer to create memory from
         * @return true on success
         */
        bool create_memory_from_buffer(AHardwareBuffer *buffer);

        /**
         * Release the NNAPI Memory
         */
        void release_memory();

        /**
         * Check if memory is created
         */
        bool has_memory() const;

        /**
         * Get the NNAPI Memory handle
         * @return NNAPI Memory, or nullptr if not created
         */
        ANeuralNetworksMemory *get_memory() const;

        /**
         * Get the size of the memory in bytes
         */
        size_t get_memory_size() const;

        /**
         * Set this memory as input for an NNAPI execution
         *
         * @param execution NNAPI execution handle
         * @param index Input index (usually 0 for single-input models)
         * @param type Optional operand type (nullptr to use model's type)
         * @param offset Offset into memory (usually 0)
         * @param length Length of data (usually full buffer size)
         * @return true on success
         */
        bool set_as_execution_input(
                ANeuralNetworksExecution *execution,
                int32_t index,
                const ANeuralNetworksOperandType *type,
                size_t offset,
                size_t length);

        /**
         * Set this memory as output for an NNAPI execution
         *
         * @param execution NNAPI execution handle
         * @param index Output index
         * @param type Optional operand type (nullptr to use model's type)
         * @param offset Offset into memory
         * @param length Length of data
         * @return true on success
         */
        bool set_as_execution_output(
                ANeuralNetworksExecution *execution,
                int32_t index,
                const ANeuralNetworksOperandType *type,
                size_t offset,
                size_t length);

        /**
         * Check if NNAPI is available on this device
         * @return true if NNAPI is available
         */
        static bool is_nnapi_available();

        /**
         * Get NNAPI feature level
         * @return Feature level (e.g., 29 for Android 10)
         */
        static int64_t get_feature_level();

    private:
        ANeuralNetworksMemory *memory_ = nullptr;
        size_t memory_size_ = 0;
    };

} // namespace futon::inference

#endif // FUTON_INFERENCE_NNAPI_BRIDGE_H
