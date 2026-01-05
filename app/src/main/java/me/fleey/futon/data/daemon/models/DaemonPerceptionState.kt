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
package me.fleey.futon.data.daemon.models

import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.DetectedElement

/**
 * State of the daemon perception proxy.
 */
sealed interface DaemonPerceptionState {
  /**
   * Perception proxy is not initialized or daemon is not connected.
   */
  data object Unavailable : DaemonPerceptionState

  /**
   * Perception proxy is ready to process requests.
   */
  data object Ready : DaemonPerceptionState

  /**
   * Perception proxy is currently processing a perception request.
   */
  data object Processing : DaemonPerceptionState

  data class Error(
    val message: String,
    val code: ErrorCode,
  ) : DaemonPerceptionState
}

/**
 * Performance metrics from the daemon perception system.
 */
data class DaemonPerformanceMetrics(
  val fps: Float,
  val captureLatencyMs: Float,
  val inferenceLatencyMs: Float,
  val totalLatencyMs: Float,
  val activeDelegate: DelegateType,
  val frameCount: Long,
  val rollingAverageFps: Float,
  val rollingAverageCaptureMs: Float,
  val rollingAverageInferenceMs: Float,
  val rollingAverageTotalMs: Float,
  val isAboveLatencyThreshold: Boolean,
) {
  companion object {
    val EMPTY = DaemonPerformanceMetrics(
      fps = 0f,
      captureLatencyMs = 0f,
      inferenceLatencyMs = 0f,
      totalLatencyMs = 0f,
      activeDelegate = DelegateType.NONE,
      frameCount = 0,
      rollingAverageFps = 0f,
      rollingAverageCaptureMs = 0f,
      rollingAverageInferenceMs = 0f,
      rollingAverageTotalMs = 0f,
      isAboveLatencyThreshold = false,
    )
  }
}

/**
 * Warning event emitted when daemon performance degrades.
 */
sealed interface DaemonPerformanceWarning {
  val timestamp: Long
  val message: String

  /**
   * Latency exceeded the configured threshold.
   */
  data class LatencyThresholdExceeded(
    override val timestamp: Long,
    override val message: String,
    val currentLatencyMs: Float,
    val thresholdMs: Float,
    val averageLatencyMs: Float,
  ) : DaemonPerformanceWarning

  /**
   * FPS dropped below acceptable level.
   */
  data class LowFps(
    override val timestamp: Long,
    override val message: String,
    val currentFps: Float,
    val targetFps: Float,
  ) : DaemonPerformanceWarning

  /**
   * Delegate changed, potentially affecting performance.
   */
  data class DelegateChanged(
    override val timestamp: Long,
    override val message: String,
    val from: DelegateType,
    val to: DelegateType,
  ) : DaemonPerformanceWarning
}

/**
 * Result of a daemon perception operation.
 */
sealed interface DaemonPerceptionResult {
  /**
   * Perception succeeded with detected elements.
   */
  data class Success(
    val elements: List<DetectedElement>,
    val captureLatencyMs: Long,
    val inferenceLatencyMs: Long,
    val totalLatencyMs: Long,
    val activeDelegate: DelegateType,
    val timestamp: Long,
    val imageWidth: Int,
    val imageHeight: Int,
  ) : DaemonPerceptionResult

  data object DaemonUnavailable : DaemonPerceptionResult

  data class Failure(
    val error: DaemonError,
    val message: String,
  ) : DaemonPerceptionResult
}
