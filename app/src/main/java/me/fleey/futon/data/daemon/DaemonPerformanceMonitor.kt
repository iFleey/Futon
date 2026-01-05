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
package me.fleey.futon.data.daemon

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.DaemonStatus
import me.fleey.futon.data.daemon.models.DaemonPerformanceMetrics
import me.fleey.futon.data.daemon.models.DaemonPerformanceWarning
import java.io.Closeable

/**
 * Monitors daemon performance metrics.
 *
 * Tracks FPS, capture latency, inference latency, and total latency.
 * Calculates rolling averages over the last 100 frames.
 * Emits warnings when latency exceeds threshold.
 */
interface DaemonPerformanceMonitor : Closeable {
  /**
   * Current performance metrics as StateFlow.
   */
  val performanceMetrics: StateFlow<DaemonPerformanceMetrics>

  /**
   * Performance warnings as SharedFlow.
   */
  val warnings: SharedFlow<DaemonPerformanceWarning>

  /**
   * Record metrics from a daemon status update.
   */
  fun recordStatus(status: DaemonStatus)

  /**
   * Set the latency threshold for warnings.
   *
   * @param thresholdMs Threshold in milliseconds (default: 50ms)
   */
  fun setLatencyThreshold(thresholdMs: Float)

  /**
   * Get current latency threshold.
   */
  fun getLatencyThreshold(): Float

  fun reset()
}
