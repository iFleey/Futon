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

import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.HardwareCapabilities
import me.fleey.futon.data.perception.models.PerceptionConfig
import me.fleey.futon.data.perception.models.PerceptionError
import me.fleey.futon.data.perception.models.PerceptionMetrics
import me.fleey.futon.data.perception.models.PerceptionOperationResult
import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.perception.models.PerceptionSystemState

/**
 * PerceptionSystem implementation that delegates to the native daemon.
 *
 * In Root-Only architecture, all perception operations (capture, detection, OCR)
 * are handled by the daemon via Binder IPC. This implementation provides a
 * unified interface while delegating actual work to the daemon.
 */
import org.koin.core.annotation.Single

@Single(binds = [PerceptionSystem::class])
class DaemonPerceptionSystem(
  private val daemonRepository: DaemonRepository,
  private val hardwareDetector: HardwareDetector,
) : PerceptionSystem {

  companion object {
    private const val TAG = "DaemonPerceptionSystem"
  }

  private val _state = MutableStateFlow(PerceptionSystemState.UNINITIALIZED)
  override val state: StateFlow<PerceptionSystemState> = _state.asStateFlow()

  private var _config: PerceptionConfig? = null
  override val config: PerceptionConfig? get() = _config

  private var hardwareCapabilities: HardwareCapabilities? = null
  private val metricsFlow = MutableSharedFlow<PerceptionMetrics>(replay = 1)
  private var lastMetrics: PerceptionMetrics = PerceptionMetrics(
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

  override suspend fun initialize(config: PerceptionConfig): Boolean {
    if (_state.value == PerceptionSystemState.DESTROYED) {
      Log.w(TAG, "Cannot initialize - system is destroyed")
      return false
    }

    _state.value = PerceptionSystemState.INITIALIZING
    _config = config

    Log.i(TAG, "Initializing DaemonPerceptionSystem with config: $config")

    hardwareCapabilities = hardwareDetector.detectCapabilities()
    Log.d(TAG, "Hardware capabilities: $hardwareCapabilities")

    if (!daemonRepository.isConnected()) {
      Log.w(TAG, "Daemon not connected, attempting to connect")
      val connectResult = daemonRepository.connect()
      if (connectResult.isFailure) {
        Log.e(TAG, "Failed to connect to daemon")
        _state.value = PerceptionSystemState.ERROR
        return false
      }
    }

    _state.value = PerceptionSystemState.READY
    Log.i(TAG, "DaemonPerceptionSystem initialized successfully")
    return true
  }

  override fun setMediaProjection(projection: MediaProjection): Boolean {
    // Not needed in daemon mode - daemon has root access for capture
    return true
  }

  override fun hasActiveCapture(): Boolean {
    // Daemon always has capture capability via root
    return daemonRepository.isConnected()
  }

  override suspend fun perceive(): PerceptionOperationResult {
    if (_state.value != PerceptionSystemState.READY) {
      return PerceptionOperationResult.Failure(
        error = PerceptionError.NOT_INITIALIZED,
        message = "PerceptionSystem not ready",
      )
    }

    val startTime = System.nanoTime()

    val result = daemonRepository.requestPerception()

    val endTime = System.nanoTime()
    val latencyMs = (endTime - startTime) / 1_000_000L

    return result.fold(
      onSuccess = { elements ->
        updateMetrics(latencyMs, elements.size)

        PerceptionOperationResult.Success(
          result = PerceptionResult(
            elements = elements,
            captureLatencyMs = latencyMs / 3,
            detectionLatencyMs = latencyMs / 3,
            ocrLatencyMs = latencyMs / 3,
            totalLatencyMs = latencyMs,
            activeDelegate = DelegateType.HEXAGON_DSP,
            timestamp = System.currentTimeMillis(),
            imageWidth = 0,
            imageHeight = 0,
          ),
        )
      },
      onFailure = { error ->
        Log.e(TAG, "Perception failed: ${error.message}")
        PerceptionOperationResult.Failure(
          error = PerceptionError.DETECTION_FAILED,
          message = error.message ?: "Unknown error",
        )
      },
    )
  }

  private fun updateMetrics(latencyMs: Long, elementCount: Int) {
    val metrics = PerceptionMetrics(
      averageLatencyMs = latencyMs,
      p95LatencyMs = latencyMs,
      loopCount = 1,
      modelMemoryBytes = 0,
      bufferMemoryBytes = 0,
      activeDelegate = DelegateType.HEXAGON_DSP,
      isAboveThreshold = latencyMs > 30,
      captureAverageMs = latencyMs / 3,
      detectionAverageMs = latencyMs / 3,
      ocrAverageMs = latencyMs / 3,
    )
    lastMetrics = metrics
    metricsFlow.tryEmit(metrics)
  }

  override fun getHardwareCapabilities(): HardwareCapabilities {
    return hardwareCapabilities ?: HardwareCapabilities.UNKNOWN
  }

  override fun observeMetrics(): Flow<PerceptionMetrics> = metricsFlow.asSharedFlow()

  override fun getCurrentMetrics(): PerceptionMetrics = lastMetrics

  override fun pause() {
    if (_state.value == PerceptionSystemState.READY) {
      _state.value = PerceptionSystemState.PAUSED
    }
  }

  override suspend fun resume(): Boolean {
    if (_state.value == PerceptionSystemState.PAUSED) {
      _state.value = PerceptionSystemState.READY
      return true
    }
    return _state.value == PerceptionSystemState.READY
  }

  override fun isReady(): Boolean = _state.value == PerceptionSystemState.READY

  override fun isPaused(): Boolean = _state.value == PerceptionSystemState.PAUSED

  override fun close() {
    _state.value = PerceptionSystemState.DESTROYED
  }
}
