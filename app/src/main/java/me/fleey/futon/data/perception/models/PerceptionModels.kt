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

import me.fleey.futon.domain.perception.models.UIBounds

/**
 * Configuration for the perception system.
 */
data class PerceptionConfig(
  val targetLatencyMs: Long = 30,
  val minConfidence: Float = 0.5f,
  val enableOcr: Boolean = true,
  val maxConcurrentBuffers: Int = 3,
  val ocrScripts: Set<TextScript> = TextScript.DEFAULT,
) {
  init {
    require(targetLatencyMs > 0) { "Target latency must be positive" }
    require(minConfidence in 0f..1f) { "Min confidence must be between 0 and 1" }
    require(maxConcurrentBuffers in 1..5) { "Max concurrent buffers must be between 1 and 5" }
  }

  companion object {
    val DEFAULT = PerceptionConfig()
  }
}

data class DetectedElement(
  val boundingBox: UIBounds,
  val elementType: ElementType,
  val confidence: Float,
  val text: String? = null,
  val textConfidence: Float? = null,
) {
  init {
    require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
    require(textConfidence == null || textConfidence in 0f..1f) {
      "Text confidence must be between 0 and 1"
    }
  }

  val hasText: Boolean get() = !text.isNullOrBlank()
  val centerX: Int get() = boundingBox.centerX
  val centerY: Int get() = boundingBox.centerY
}

data class PerceptionResult(
  val elements: List<DetectedElement>,
  val captureLatencyMs: Long,
  val detectionLatencyMs: Long,
  val ocrLatencyMs: Long,
  val totalLatencyMs: Long,
  val activeDelegate: DelegateType,
  val timestamp: Long,
  val imageWidth: Int,
  val imageHeight: Int,
) {
  val isEmpty: Boolean get() = elements.isEmpty()
  val elementCount: Int get() = elements.size
  val textElements: List<DetectedElement> get() = elements.filter { it.hasText }
  val isAboveLatencyTarget: Boolean get() = totalLatencyMs > 30
}

enum class PerceptionSystemState {
  UNINITIALIZED,
  INITIALIZING,
  READY,
  PERCEIVING,
  PAUSED,
  ERROR,
  DESTROYED
}

data class PerceptionMetrics(
  val averageLatencyMs: Long,
  val p95LatencyMs: Long,
  val loopCount: Long,
  val modelMemoryBytes: Long,
  val bufferMemoryBytes: Long,
  val activeDelegate: DelegateType,
  val isAboveThreshold: Boolean,
  val captureAverageMs: Long,
  val detectionAverageMs: Long,
  val ocrAverageMs: Long,
) {
  val modelMemoryMb: Float get() = modelMemoryBytes / (1024f * 1024f)
  val bufferMemoryMb: Float get() = bufferMemoryBytes / (1024f * 1024f)
  val totalMemoryMb: Float get() = modelMemoryMb + bufferMemoryMb
}

sealed interface PerceptionOperationResult {
  data class Success(val result: PerceptionResult) : PerceptionOperationResult
  data class PartialSuccess(
    val result: PerceptionResult,
    val errors: List<PerceptionStageError>,
  ) : PerceptionOperationResult

  data class Failure(val error: PerceptionError, val message: String) : PerceptionOperationResult
}

data class PerceptionStageError(
  val stage: PerceptionStage,
  val error: String,
)

enum class PerceptionStage {
  CAPTURE,
  DETECTION,
  OCR
}

enum class PerceptionError {
  NOT_INITIALIZED,
  CAPTURE_FAILED,
  DETECTION_FAILED,
  OCR_FAILED,
  TIMEOUT,
  RESOURCE_EXHAUSTED,
  PERMISSION_DENIED,
  PAUSED,
  DESTROYED,
  UNKNOWN
}
