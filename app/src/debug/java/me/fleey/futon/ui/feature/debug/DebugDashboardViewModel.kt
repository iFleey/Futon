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
package me.fleey.futon.ui.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.daemon.ConfigurationSynchronizer
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.DebugStreamClient
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.DebugDetection
import me.fleey.futon.data.daemon.models.DebugStreamState
import me.fleey.futon.data.perception.models.DelegateType

data class DebugDashboardUiState(
  val streamState: DebugStreamState = DebugStreamState.Disconnected,
  val currentFps: Float = 0f,
  val currentLatencyMs: Float = 0f,
  val frameCount: Long = 0L,
  val activeDelegate: DelegateType = DelegateType.NONE,
  val latestDetections: List<DebugDetection> = emptyList(),
  val fpsHistory: List<Float> = emptyList(),
  val latencyHistory: List<Float> = emptyList(),
  val screenWidth: Int = 1080,
  val screenHeight: Int = 2400,
)

sealed interface DebugDashboardUiEvent {
  data object Connect : DebugDashboardUiEvent
  data object Disconnect : DebugDashboardUiEvent
}

class DebugDashboardViewModel(
  private val debugStreamClient: DebugStreamClient,
  private val configSynchronizer: ConfigurationSynchronizer,
  private val daemonRepository: DaemonRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(DebugDashboardUiState())
  val uiState: StateFlow<DebugDashboardUiState> = _uiState.asStateFlow()

  private val fpsHistoryBuffer = ArrayDeque<Float>(MAX_HISTORY_SIZE)
  private val latencyHistoryBuffer = ArrayDeque<Float>(MAX_HISTORY_SIZE)

  init {
    observeStreamState()
    observeDebugFrames()
    observeDaemonState()
  }

  fun onEvent(event: DebugDashboardUiEvent) {
    when (event) {
      is DebugDashboardUiEvent.Connect -> connect()
      is DebugDashboardUiEvent.Disconnect -> disconnect()
    }
  }

  private fun connect() {
    viewModelScope.launch {
      val currentState = daemonRepository.daemonState.value
      if (currentState !is DaemonState.Ready) {
        if (currentState !is DaemonState.Connecting) {
          daemonRepository.connect()
        }
        // Wait for daemon to become ready - observeDaemonState will handle enabling debug stream
        // For now, just try to enable and connect
      }

      if (daemonRepository.isConnected()) {
        configSynchronizer.setDebugStreamEnabled(true)
      }

      debugStreamClient.enable()
    }
  }

  private fun disconnect() {
    debugStreamClient.disable()
    clearHistory()
  }

  /**
   * Observes daemon state to auto-enable debug stream when daemon becomes ready.
   */
  private fun observeDaemonState() {
    viewModelScope.launch {
      daemonRepository.daemonState.collect { state ->
        if (state is DaemonState.Ready && debugStreamClient.isEnabled()) {
          configSynchronizer.setDebugStreamEnabled(true)
        }
      }
    }
  }

  private fun observeStreamState() {
    viewModelScope.launch {
      debugStreamClient.debugStreamState.collect { state ->
        _uiState.update { it.copy(streamState = state) }
      }
    }
  }

  private fun observeDebugFrames() {
    viewModelScope.launch {
      debugStreamClient.debugFrames.collect { frame ->
        updateFpsHistory(frame.fps)
        updateLatencyHistory(frame.latencyMs)

        _uiState.update {
          it.copy(
            currentFps = frame.fps,
            currentLatencyMs = frame.latencyMs,
            frameCount = frame.frameCount,
            activeDelegate = frame.getActiveDelegateType(),
            latestDetections = frame.detections,
            fpsHistory = fpsHistoryBuffer.toList(),
            latencyHistory = latencyHistoryBuffer.toList(),
          )
        }
      }
    }
  }

  private fun updateFpsHistory(fps: Float) {
    if (fpsHistoryBuffer.size >= MAX_HISTORY_SIZE) {
      fpsHistoryBuffer.removeFirst()
    }
    fpsHistoryBuffer.addLast(fps)
  }

  private fun updateLatencyHistory(latencyMs: Float) {
    if (latencyHistoryBuffer.size >= MAX_HISTORY_SIZE) {
      latencyHistoryBuffer.removeFirst()
    }
    latencyHistoryBuffer.addLast(latencyMs)
  }

  private fun clearHistory() {
    fpsHistoryBuffer.clear()
    latencyHistoryBuffer.clear()
    _uiState.update {
      it.copy(
        currentFps = 0f,
        currentLatencyMs = 0f,
        frameCount = 0L,
        activeDelegate = DelegateType.NONE,
        latestDetections = emptyList(),
        fpsHistory = emptyList(),
        latencyHistory = emptyList(),
      )
    }
  }

  companion object {
    private const val MAX_HISTORY_SIZE = 60
  }
}
