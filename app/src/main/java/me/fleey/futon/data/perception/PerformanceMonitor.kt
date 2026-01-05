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
package me.fleey.futon.data.perception

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.PerceptionMetrics

/**
 * Monitors and reports performance metrics for the perception system.
 */
interface PerformanceMonitor {

  /**
   * Current metrics snapshot as a StateFlow.
   */
  val currentMetrics: StateFlow<PerceptionMetrics>

  /**
   * Record metrics from a completed perception loop.
   *
   * @param captureMs Capture stage latency in milliseconds
   * @param detectionMs Detection stage latency in milliseconds
   * @param ocrMs OCR stage latency in milliseconds
   * @param totalMs Total loop latency in milliseconds
   * @param delegate Active delegate used for inference
   */
  fun recordLoop(
    captureMs: Long,
    detectionMs: Long,
    ocrMs: Long,
    totalMs: Long,
    delegate: DelegateType,
  )

  fun updateMemoryUsage(modelMemoryBytes: Long, bufferMemoryBytes: Long)

  /**
   * Record a delegate transition event.
   *
   * @param from Previous delegate type
   * @param to New delegate type
   * @param reason Reason for the transition
   */
  fun recordDelegateTransition(from: DelegateType, to: DelegateType, reason: String)

  /**
   * Get rolling average latency over the configured window.
   *
   * @return Average total latency in milliseconds
   */
  fun getRollingAverageLatency(): Long

  /**
   * Get P95 latency over the configured window.
   *
   * @return P95 total latency in milliseconds
   */
  fun getP95Latency(): Long

  /**
   * Get total number of perception loops recorded.
   *
   * @return Loop count
   */
  fun getLoopCount(): Long

  fun observeMetrics(): Flow<PerceptionMetrics>

  fun observeWarnings(): Flow<PerformanceWarning>

  fun setLatencyThreshold(thresholdMs: Long)

  fun getLatencyThreshold(): Long

  fun reset()
}

/**
 * Warning event emitted when performance degrades.
 */
sealed interface PerformanceWarning {
  val timestamp: Long
  val message: String

  /**
   * Latency exceeded the configured threshold.
   */
  data class LatencyThresholdExceeded(
    override val timestamp: Long,
    override val message: String,
    val currentLatencyMs: Long,
    val thresholdMs: Long,
    val averageLatencyMs: Long,
  ) : PerformanceWarning

  /**
   * Delegate changed, potentially affecting performance.
   */
  data class DelegateChanged(
    override val timestamp: Long,
    override val message: String,
    val from: DelegateType,
    val to: DelegateType,
    val latencyBeforeMs: Long,
    val latencyAfterMs: Long,
  ) : PerformanceWarning

  data class HighMemoryUsage(
    override val timestamp: Long,
    override val message: String,
    val totalMemoryBytes: Long,
    val thresholdBytes: Long,
  ) : PerformanceWarning
}
