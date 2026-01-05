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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.PerceptionMetrics
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicLong

@Single(binds = [PerformanceMonitor::class])
class PerformanceMonitorImpl(
  private val windowSize: Int = DEFAULT_WINDOW_SIZE,
  defaultThresholdMs: Long = DEFAULT_THRESHOLD_MS,
) : PerformanceMonitor {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // Latency tracking windows
  private val captureLatencies = RollingWindow(windowSize)
  private val detectionLatencies = RollingWindow(windowSize)
  private val ocrLatencies = RollingWindow(windowSize)
  private val totalLatencies = RollingWindow(windowSize)

  private val loopCount = AtomicLong(0)

  // Memory tracking
  @Volatile
  private var modelMemoryBytes: Long = 0

  @Volatile
  private var bufferMemoryBytes: Long = 0

  // Delegate tracking
  @Volatile
  private var activeDelegate: DelegateType = DelegateType.NONE

  @Volatile
  private var previousDelegate: DelegateType = DelegateType.NONE

  @Volatile
  private var latencyBeforeTransition: Long = 0

  // Threshold
  @Volatile
  private var latencyThresholdMs: Long = defaultThresholdMs

  // Flows
  private val _currentMetrics = MutableStateFlow(createEmptyMetrics())
  override val currentMetrics: StateFlow<PerceptionMetrics> = _currentMetrics.asStateFlow()

  private val metricsFlow = MutableSharedFlow<PerceptionMetrics>(replay = 1)
  private val warningsFlow = MutableSharedFlow<PerformanceWarning>(extraBufferCapacity = 16)

  override fun recordLoop(
    captureMs: Long,
    detectionMs: Long,
    ocrMs: Long,
    totalMs: Long,
    delegate: DelegateType,
  ) {
    // Record latencies
    captureLatencies.add(captureMs)
    detectionLatencies.add(detectionMs)
    ocrLatencies.add(ocrMs)
    totalLatencies.add(totalMs)
    loopCount.incrementAndGet()

    // Track delegate changes
    val previousDelegateLocal = activeDelegate
    if (delegate != previousDelegateLocal && previousDelegateLocal != DelegateType.NONE) {
      handleDelegateChange(previousDelegateLocal, delegate, totalMs)
    } else if (delegate != previousDelegateLocal) {
      activeDelegate = delegate
      latencyBeforeTransition = totalMs
    }
    activeDelegate = delegate

    // Check threshold
    val avgLatency = totalLatencies.average()
    val isAboveThreshold = avgLatency > latencyThresholdMs

    if (isAboveThreshold) {
      emitWarning(
        PerformanceWarning.LatencyThresholdExceeded(
          timestamp = System.currentTimeMillis(),
          message = "Latency ${avgLatency}ms exceeds threshold ${latencyThresholdMs}ms",
          currentLatencyMs = totalMs,
          thresholdMs = latencyThresholdMs,
          averageLatencyMs = avgLatency,
        ),
      )
    }

    val metrics = createMetrics(isAboveThreshold)
    _currentMetrics.value = metrics
    emitMetrics(metrics)
  }

  private fun handleDelegateChange(from: DelegateType, to: DelegateType, currentLatency: Long) {
    val latencyBefore = latencyBeforeTransition
    val reason = determineDelegateChangeReason(from, to)

    Log.w(
      TAG,
      "Delegate transition: $from -> $to (reason: $reason). " +
        "Latency before: ${latencyBefore}ms, after: ${currentLatency}ms",
    )

    recordDelegateTransition(from, to, reason)

    // Update for next transition
    latencyBeforeTransition = currentLatency
  }

  private fun determineDelegateChangeReason(from: DelegateType, to: DelegateType): String {
    return when {
      to.priority < from.priority -> "Fallback due to delegate failure"
      to.priority > from.priority -> "Upgraded to better delegate"
      else -> "Delegate reconfiguration"
    }
  }

  override fun updateMemoryUsage(modelMemoryBytes: Long, bufferMemoryBytes: Long) {
    this.modelMemoryBytes = modelMemoryBytes
    this.bufferMemoryBytes = bufferMemoryBytes

    val totalMemory = modelMemoryBytes + bufferMemoryBytes
    if (totalMemory > MEMORY_WARNING_THRESHOLD_BYTES) {
      emitWarning(
        PerformanceWarning.HighMemoryUsage(
          timestamp = System.currentTimeMillis(),
          message = "High memory usage: ${totalMemory / (1024 * 1024)}MB",
          totalMemoryBytes = totalMemory,
          thresholdBytes = MEMORY_WARNING_THRESHOLD_BYTES,
        ),
      )
    }

    val metrics = createMetrics(totalLatencies.average() > latencyThresholdMs)
    _currentMetrics.value = metrics
  }

  override fun recordDelegateTransition(from: DelegateType, to: DelegateType, reason: String) {
    val latencyBefore = latencyBeforeTransition
    val latencyAfter = totalLatencies.average()

    emitWarning(
      PerformanceWarning.DelegateChanged(
        timestamp = System.currentTimeMillis(),
        message = "Delegate changed from $from to $to: $reason",
        from = from,
        to = to,
        latencyBeforeMs = latencyBefore,
        latencyAfterMs = latencyAfter,
      ),
    )
  }

  override fun getRollingAverageLatency(): Long = totalLatencies.average()

  override fun getP95Latency(): Long = totalLatencies.percentile(95)

  override fun getLoopCount(): Long = loopCount.get()

  override fun observeMetrics(): Flow<PerceptionMetrics> = metricsFlow.asSharedFlow()

  override fun observeWarnings(): Flow<PerformanceWarning> = warningsFlow.asSharedFlow()

  override fun setLatencyThreshold(thresholdMs: Long) {
    require(thresholdMs > 0) { "Threshold must be positive" }
    latencyThresholdMs = thresholdMs
  }

  override fun getLatencyThreshold(): Long = latencyThresholdMs

  override fun reset() {
    captureLatencies.clear()
    detectionLatencies.clear()
    ocrLatencies.clear()
    totalLatencies.clear()
    loopCount.set(0)
    modelMemoryBytes = 0
    bufferMemoryBytes = 0
    activeDelegate = DelegateType.NONE
    previousDelegate = DelegateType.NONE
    latencyBeforeTransition = 0
    _currentMetrics.value = createEmptyMetrics()
  }

  private fun createMetrics(isAboveThreshold: Boolean): PerceptionMetrics {
    return PerceptionMetrics(
      averageLatencyMs = totalLatencies.average(),
      p95LatencyMs = totalLatencies.percentile(95),
      loopCount = loopCount.get(),
      modelMemoryBytes = modelMemoryBytes,
      bufferMemoryBytes = bufferMemoryBytes,
      activeDelegate = activeDelegate,
      isAboveThreshold = isAboveThreshold,
      captureAverageMs = captureLatencies.average(),
      detectionAverageMs = detectionLatencies.average(),
      ocrAverageMs = ocrLatencies.average(),
    )
  }

  private fun createEmptyMetrics(): PerceptionMetrics {
    return PerceptionMetrics(
      averageLatencyMs = 0,
      p95LatencyMs = 0,
      loopCount = 0,
      modelMemoryBytes = 0,
      bufferMemoryBytes = 0,
      activeDelegate = DelegateType.NONE,
      isAboveThreshold = false,
      captureAverageMs = 0,
      detectionAverageMs = 0,
      ocrAverageMs = 0,
    )
  }

  private fun emitMetrics(metrics: PerceptionMetrics) {
    scope.launch {
      metricsFlow.emit(metrics)
    }
  }

  private fun emitWarning(warning: PerformanceWarning) {
    scope.launch {
      warningsFlow.emit(warning)
    }
  }

  /**
   * Rolling window for latency samples with thread-safe operations.
   */
  private class RollingWindow(private val maxSize: Int) {
    private val values = LongArray(maxSize)
    private var index = 0
    private var count = 0

    @Synchronized
    fun add(value: Long) {
      values[index] = value
      index = (index + 1) % maxSize
      if (count < maxSize) count++
    }

    @Synchronized
    fun average(): Long {
      if (count == 0) return 0
      var sum = 0L
      for (i in 0 until count) {
        sum += values[i]
      }
      return sum / count
    }

    @Synchronized
    fun percentile(p: Int): Long {
      if (count == 0) return 0
      val sorted = values.copyOf(count).sortedArray()
      val idx = ((p / 100.0) * (count - 1)).toInt().coerceIn(0, count - 1)
      return sorted[idx]
    }

    @Synchronized
    fun clear() {
      index = 0
      count = 0
    }
  }

  companion object {
    private const val TAG = "PerformanceMonitor"
    private const val DEFAULT_WINDOW_SIZE = 100
    private const val DEFAULT_THRESHOLD_MS = 30L
    private const val MEMORY_WARNING_THRESHOLD_BYTES = 256L * 1024 * 1024
  }
}

private val DelegateType.priority: Int
  get() = when (this) {
    DelegateType.HEXAGON_DSP -> 4
    DelegateType.GPU -> 3
    DelegateType.NNAPI -> 2
    DelegateType.XNNPACK -> 1
    DelegateType.NONE -> 0
  }
