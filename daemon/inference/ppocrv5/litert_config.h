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

#ifndef FUTON_INFERENCE_PPOCRV5_LITERT_CONFIG_H
#define FUTON_INFERENCE_PPOCRV5_LITERT_CONFIG_H

#include <string>

/**
 * LiteRT CompiledModel API Configuration for PPOCRv5
 *
 * Based on official documentation:
 * - https://ai.google.dev/edge/litert/next/android_cpp
 * - https://ai.google.dev/edge/litert/next/gpu
 */

namespace futon::inference::ppocrv5::litert_config {

// NPU compilation cache directory (set at runtime via Environment options)
    inline std::string g_compiler_cache_dir;

// Enable NPU compilation caching for faster subsequent loads
    constexpr bool kEnableCompilerCache = true;

// Enable zero-copy buffer optimization
    constexpr bool kEnableZeroCopy = true;

// Enable asynchronous inference execution
    constexpr bool kEnableAsyncInference = true;

// Performance tuning constants
    constexpr int kWarmupIterations = 3;

// NPU-specific optimizations
    namespace npu {
        constexpr bool kPreferForInt8 = true;
        constexpr int kMinApiLevel = 31;
    }

// GPU-specific optimizations (primary accelerator for FP16 models)
    namespace gpu {
        constexpr bool kEnableOpenClBufferSharing = true;
        constexpr bool kPreferFp16 = true;
        constexpr bool kEnableAhwbZeroCopy = true;
        constexpr bool kEnableAsyncExecution = true;
        constexpr bool kEnableGlBufferSupport = true;
    }

// Buffer interop capabilities
    namespace interop {
        constexpr bool kCheckClGlInterop = true;
        constexpr bool kCheckAhwbClInterop = true;
        constexpr bool kCheckAhwbGlInterop = true;
    }

// SIMD optimization flags
    namespace simd {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        constexpr bool kEnableNeon = true;
#else
        constexpr bool kEnableNeon = false;
#endif
        constexpr int kPrefetchDistance = 256;
        constexpr int kNeonVectorWidth = 4;
    }

// Memory optimization
    namespace memory {
        constexpr size_t kCacheLineSize = 64;
        constexpr size_t kDetInputBufferSize = 1 * 640 * 640 * 3 * sizeof(float);
        constexpr size_t kRecInputBufferSize = 1 * 48 * 320 * 3 * sizeof(float);
        constexpr int kMaxTextBoxes = 50;
    }

}  // namespace futon::inference::ppocrv5::litert_config

#endif  // FUTON_INFERENCE_PPOCRV5_LITERT_CONFIG_H
