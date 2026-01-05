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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.fleey.futon.DaemonStatus
import me.fleey.futon.data.daemon.models.DaemonPerformanceMetrics
import me.fleey.futon.data.daemon.models.DaemonPerformanceWarning
import me.fleey.futon.data.perception.models.DelegateType
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Implementation of DaemonPerformanceMonitor.
 *
 * Tracks rolling averages over the last 100 frames.
 * Emits warnings when latency exceeds threshold.
 */
@Single(binds = [DaemonPerformanceMonitor::class])
class DaemonPerformanceMonitorImpl(
  private val daemonRepository: DaemonRepository?,
) : DaemonPerformanceMonitor {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _performanceMetrics = MutableStateFlow(DaemonPerformanceMetrics.EMPTY)
  override val performanceMetrics: StateFlow<DaemonPerformanceMetrics> =
    _performanceMetrics.asStateFlow()

  private val _warnings = MutableSharedFlow<DaemonPerformanceWarning>(
    extraBufferCapacity = 16,
  )
  override val warnings: SharedFlow<DaemonPerformanceWarning> = _warnings.asSharedFlow()

  private val fpsHistory = ConcurrentLinkedDeque<Float>()
  private val captureLatencyHistory = ConcurrentLinkedDeque<Float>()
  private val inferenceLatencyHistory = ConcurrentLinkedDeque<Float>()
  private val totalLatencyHistory = ConcurrentLinkedDeque<Float>()

  @Volatile
  private var latencyThresholdMs: Float = DEFAULT_LATENCY_THRESHOLD_MS

  @Volatile
  private var lastDelegate: DelegateType = DelegateType.NONE

  @Volatile
  private var frameCount: Long = 0

  init {
    daemonRepository?.let { repo ->
      observeDaemonStatus(repo)
    }
  }

  private fun observeDaemonStatus(repo: DaemonRepository) {
    repo.status
      .onEach { status ->
        status?.let { recordStatus(it) }
      }
      .launchIn(scope)
  }

  override fun recordStatus(status: DaemonStatus) {
    frameCount = status.frameCount.toLong()

    addToHistory(fpsHistory, status.fps)
    addToHistory(captureLatencyHistory, status.captureLatencyMs)
    addToHistory(inferenceLatencyHistory, status.inferenceLatencyMs)
    addToHistory(totalLatencyHistory, status.totalLatencyMs)

    val currentDelegate = parseDelegateType(status.activeDelegate)

    val rollingFps = calculateAverage(fpsHistory)
    val rollingCapture = calculateAverage(captureLatencyHistory)
    val rollingInference = calculateAverage(inferenceLatencyHistory)
    val rollingTotal = calculateAverage(totalLatencyHistory)

    val isAboveThreshold = status.totalLatencyMs > latencyThresholdMs

    val metrics = DaemonPerformanceMetrics(
      fps = status.fps,
      captureLatencyMs = status.captureLatencyMs,
      inferenceLatencyMs = status.inferenceLatencyMs,
      totalLatencyMs = status.totalLatencyMs,
      activeDelegate = currentDelegate,
      frameCount = frameCount,
      rollingAverageFps = rollingFps,
      rollingAverageCaptureMs = rollingCapture,
      rollingAverageInferenceMs = rollingInference,
      rollingAverageTotalMs = rollingTotal,
      isAboveLatencyThreshold = isAboveThreshold,
    )

    _performanceMetrics.value = metrics

    checkAndEmitWarnings(status, currentDelegate, rollingTotal)

    lastDelegate = currentDelegate
  }

  private fun checkAndEmitWarnings(
    status: DaemonStatus,
    currentDelegate: DelegateType,
    rollingTotal: Float,
  ) {
    if (status.totalLatencyMs > latencyThresholdMs) {
      scope.launch {
        _warnings.emit(
          DaemonPerformanceWarning.LatencyThresholdExceeded(
            timestamp = System.currentTimeMillis(),
            message = "Latency ${status.totalLatencyMs}ms exceeds threshold ${latencyThresholdMs}ms",
            currentLatencyMs = status.totalLatencyMs,
            thresholdMs = latencyThresholdMs,
            averageLatencyMs = rollingTotal,
          ),
        )
      }
    }

    if (status.fps < LOW_FPS_THRESHOLD && status.fps > 0) {
      scope.launch {
        _warnings.emit(
          DaemonPerformanceWarning.LowFps(
            timestamp = System.currentTimeMillis(),
            message = "FPS ${status.fps} below target",
            currentFps = status.fps,
            targetFps = TARGET_FPS,
          ),
        )
      }
    }

    if (currentDelegate != lastDelegate && lastDelegate != DelegateType.NONE) {
      scope.launch {
        _warnings.emit(
          DaemonPerformanceWarning.DelegateChanged(
            timestamp = System.currentTimeMillis(),
            message = "Delegate changed from $lastDelegate to $currentDelegate",
            from = lastDelegate,
            to = currentDelegate,
          ),
        )
      }
    }
  }

  override fun setLatencyThreshold(thresholdMs: Float) {
    require(thresholdMs > 0) { "Threshold must be positive" }
    latencyThresholdMs = thresholdMs
  }

  override fun getLatencyThreshold(): Float = latencyThresholdMs

  override fun reset() {
    fpsHistory.clear()
    captureLatencyHistory.clear()
    inferenceLatencyHistory.clear()
    totalLatencyHistory.clear()
    frameCount = 0
    lastDelegate = DelegateType.NONE
    _performanceMetrics.value = DaemonPerformanceMetrics.EMPTY
  }

  override fun close() {
    reset()
  }

  private fun addToHistory(history: ConcurrentLinkedDeque<Float>, value: Float) {
    history.addLast(value)
    while (history.size > ROLLING_WINDOW_SIZE) {
      history.pollFirst()
    }
  }

  private fun calculateAverage(history: ConcurrentLinkedDeque<Float>): Float {
    if (history.isEmpty()) return 0f
    return history.sum() / history.size
  }

  private fun parseDelegateType(delegateString: String?): DelegateType {
    if (delegateString.isNullOrBlank()) return DelegateType.NONE

    return when (delegateString.uppercase()) {
      "HEXAGON", "HEXAGON_DSP", "DSP" -> DelegateType.HEXAGON_DSP
      "GPU", "OPENCL" -> DelegateType.GPU
      "NNAPI" -> DelegateType.NNAPI
      "XNNPACK", "CPU" -> DelegateType.XNNPACK
      else -> DelegateType.NONE
    }
  }

  companion object {
    private const val ROLLING_WINDOW_SIZE = 100
    private const val DEFAULT_LATENCY_THRESHOLD_MS = 50f
    private const val LOW_FPS_THRESHOLD = 20f
    private const val TARGET_FPS = 30f
  }
}
