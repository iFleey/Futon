/*
 * Futon - Futon Daemon Client
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
package me.fleey.futon.data.perception.models

/**
 * Configuration for LiteRT inference engine.
 */
data class LiteRTConfig(
  val preferredDelegate: DelegateType = DelegateType.HEXAGON_DSP,
  val numThreads: Int = 4,
  val enableZeroCopy: Boolean = true,
  val timeoutMs: Long = 100,
) {
  init {
    require(numThreads > 0) { "Number of threads must be positive" }
    require(timeoutMs > 0) { "Timeout must be positive" }
  }
}

/**
 * Handle for a loaded model.
 */
data class ModelHandle(
  val id: Long,
  val inputShape: List<Int>,
  val outputShape: List<Int>,
  val allOutputShapes: List<List<Int>>,
  val activeDelegate: DelegateType,
  val isInt8Quantized: Boolean,
  val modelSizeBytes: Long,
) {
  val isValid: Boolean get() = id > 0

  companion object {
    val INVALID = ModelHandle(
      id = -1,
      inputShape = emptyList(),
      outputShape = emptyList(),
      allOutputShapes = emptyList(),
      activeDelegate = DelegateType.NONE,
      isInt8Quantized = false,
      modelSizeBytes = 0,
    )
  }
}


/**
 * Result of an inference operation.
 */
sealed interface InferenceResult {
  /**
   * Inference succeeded with outputs.
   */
  data class Success(
    val outputs: List<FloatArray>,
    val latencyNs: Long,
    val activeDelegate: DelegateType,
    val wasZeroCopy: Boolean,
  ) : InferenceResult {
    val latencyMs: Float get() = latencyNs / 1_000_000f
  }

  /**
   * Inference failed with an error.
   */
  data class Failure(
    val error: InferenceError,
    val message: String,
  ) : InferenceResult
}

enum class InferenceError {
  NOT_INITIALIZED,
  INVALID_MODEL,
  INVALID_INPUT,
  TENSOR_ALLOCATION_FAILED,
  INFERENCE_FAILED,
  TIMEOUT,
  UNKNOWN
}

data class DelegateInitResult(
  val success: Boolean,
  val type: DelegateType,
  val errorMessage: String?,
  val initTimeNs: Long,
) {
  val initTimeMs: Float get() = initTimeNs / 1_000_000f
}

/**
 * Delegate transition event.
 */
data class DelegateTransition(
  val from: DelegateType,
  val to: DelegateType,
  val reason: String,
  val timestamp: Long = System.currentTimeMillis(),
)

enum class LiteRTEngineState {
  UNINITIALIZED,
  LOADING,
  READY,
  INFERRING,
  ERROR,
  DESTROYED
}

data class LiteRTEngineStats(
  val modelMemoryBytes: Long,
  val activeDelegate: DelegateType,
  val isDspActive: Boolean,
  val isGpuActive: Boolean,
  val delegateInitHistory: List<DelegateInitResult>,
) {
  val modelMemoryMb: Float get() = modelMemoryBytes / (1024f * 1024f)
}
