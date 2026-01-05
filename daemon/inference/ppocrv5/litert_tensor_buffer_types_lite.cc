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

#include "litert/cc/litert_tensor_buffer_types.h"

#include <string>

#include "litert/c/litert_tensor_buffer_types.h"

namespace litert {

    namespace {

        std::string BufferTypeToStringImpl(LiteRtTensorBufferType buffer_type) {
            switch (buffer_type) {
                case kLiteRtTensorBufferTypeUnknown:
                    return "Unknown";
                case kLiteRtTensorBufferTypeHostMemory:
                    return "HostMemory";
                case kLiteRtTensorBufferTypeAhwb:
                    return "Ahwb";
                case kLiteRtTensorBufferTypeIon:
                    return "Ion";
                case kLiteRtTensorBufferTypeDmaBuf:
                    return "DmaBuf";
                case kLiteRtTensorBufferTypeFastRpc:
                    return "FastRpc";
                case kLiteRtTensorBufferTypeOpenClBuffer:
                    return "OpenClBuffer";
                default:
                    return "Unknown(" + std::to_string(static_cast<int>(buffer_type)) + ")";
            }
        }

    }  // namespace

    std::string BufferTypeToStringCC(TensorBufferType buffer_type) {
        return BufferTypeToStringImpl(static_cast<LiteRtTensorBufferType>(buffer_type));
    }

}  // namespace litert
