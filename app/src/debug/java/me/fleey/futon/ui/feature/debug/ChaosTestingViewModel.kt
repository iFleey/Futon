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
import me.fleey.futon.debug.ChaosConfig
import me.fleey.futon.debug.ChaosEvent
import me.fleey.futon.debug.ChaosTestingController
import me.fleey.futon.debug.RecoveryMetrics

data class ChaosTestingUiState(
  val isEnabled: Boolean = false,
  val config: ChaosConfig = ChaosConfig(),
  val recoveryMetrics: RecoveryMetrics = RecoveryMetrics(),
  val recentEvents: List<ChaosEventDisplay> = emptyList(),
  val networkDelayMs: Long = 500L,
  val memoryPressureLevel: Int = 2,
)

data class ChaosEventDisplay(
  val timestamp: Long,
  val type: String,
  val description: String,
)

sealed interface ChaosTestingUiEvent {
  data object ToggleEnabled : ChaosTestingUiEvent
  data object SimulateCrash : ChaosTestingUiEvent
  data class UpdateNetworkDelay(val delayMs: Long) : ChaosTestingUiEvent
  data object SimulateNetworkDelay : ChaosTestingUiEvent
  data class UpdateMemoryPressureLevel(val level: Int) : ChaosTestingUiEvent
  data object SimulateMemoryPressure : ChaosTestingUiEvent
  data object SimulateAuthFailure : ChaosTestingUiEvent
  data object ResetMetrics : ChaosTestingUiEvent
  data object ClearEvents : ChaosTestingUiEvent
}

class ChaosTestingViewModel(
  private val chaosController: ChaosTestingController,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ChaosTestingUiState())
  val uiState: StateFlow<ChaosTestingUiState> = _uiState.asStateFlow()

  init {
    observeControllerState()
    observeChaosEvents()
  }

  fun onEvent(event: ChaosTestingUiEvent) {
    when (event) {
      is ChaosTestingUiEvent.ToggleEnabled -> toggleEnabled()
      is ChaosTestingUiEvent.SimulateCrash -> simulateCrash()
      is ChaosTestingUiEvent.UpdateNetworkDelay -> updateNetworkDelay(event.delayMs)
      is ChaosTestingUiEvent.SimulateNetworkDelay -> simulateNetworkDelay()
      is ChaosTestingUiEvent.UpdateMemoryPressureLevel -> updateMemoryPressureLevel(event.level)
      is ChaosTestingUiEvent.SimulateMemoryPressure -> simulateMemoryPressure()
      is ChaosTestingUiEvent.SimulateAuthFailure -> simulateAuthFailure()
      is ChaosTestingUiEvent.ResetMetrics -> resetMetrics()
      is ChaosTestingUiEvent.ClearEvents -> clearEvents()
    }
  }

  private fun observeControllerState() {
    viewModelScope.launch {
      chaosController.config.collect { config ->
        _uiState.update {
          it.copy(
            config = config,
            networkDelayMs = config.networkDelayMs,
            memoryPressureLevel = config.memoryPressureLevel,
          )
        }
      }
    }

    viewModelScope.launch {
      chaosController.recoveryMetrics.collect { metrics ->
        _uiState.update { it.copy(recoveryMetrics = metrics) }
      }
    }
  }

  private fun observeChaosEvents() {
    viewModelScope.launch {
      chaosController.chaosEvents.collect { event ->
        val display = event.toDisplay()
        _uiState.update { state ->
          val updatedEvents = (listOf(display) + state.recentEvents).take(MAX_EVENTS)
          state.copy(recentEvents = updatedEvents)
        }
      }
    }
  }

  private fun toggleEnabled() {
    val newEnabled = !_uiState.value.isEnabled
    if (newEnabled) {
      chaosController.enable()
    } else {
      chaosController.disable()
    }
    _uiState.update { it.copy(isEnabled = newEnabled) }
  }

  private fun simulateCrash() {
    chaosController.simulateDaemonCrash()
  }

  private fun updateNetworkDelay(delayMs: Long) {
    _uiState.update { it.copy(networkDelayMs = delayMs) }
    chaosController.updateConfig(
      chaosController.config.value.copy(networkDelayMs = delayMs),
    )
  }

  private fun simulateNetworkDelay() {
    chaosController.simulateNetworkDelay(_uiState.value.networkDelayMs)
  }

  private fun updateMemoryPressureLevel(level: Int) {
    _uiState.update { it.copy(memoryPressureLevel = level) }
    chaosController.updateConfig(
      chaosController.config.value.copy(memoryPressureLevel = level),
    )
  }

  private fun simulateMemoryPressure() {
    chaosController.simulateMemoryPressure(_uiState.value.memoryPressureLevel)
  }

  private fun simulateAuthFailure() {
    chaosController.simulateAuthenticationFailure()
  }

  private fun resetMetrics() {
    chaosController.resetMetrics()
  }

  private fun clearEvents() {
    chaosController.clearEventHistory()
    _uiState.update { it.copy(recentEvents = emptyList()) }
  }

  private fun ChaosEvent.toDisplay(): ChaosEventDisplay {
    val (type, description) = when (this) {
      is ChaosEvent.ControllerEnabled -> "ENABLED" to "Chaos testing enabled"
      is ChaosEvent.ControllerDisabled -> "DISABLED" to "Chaos testing disabled"
      is ChaosEvent.ConfigUpdated -> "CONFIG" to "Configuration updated"
      is ChaosEvent.CrashSimulated -> "CRASH" to "Daemon crash simulated"
      is ChaosEvent.NetworkDelaySimulated -> "NETWORK" to "Network delay: ${delayMs}ms"
      is ChaosEvent.MemoryPressureSimulated -> "MEMORY" to "Memory pressure level: $level"
      is ChaosEvent.AuthFailureSimulated -> "AUTH" to "Authentication failure simulated"
      is ChaosEvent.CrashDetected -> "DETECTED" to "Crash detected in ${detectionTimeMs}ms"
      is ChaosEvent.ReconciliationStarted -> "RECONCILE" to "Reconciliation started"
      is ChaosEvent.ReconciliationCompleted -> "RECOVERED" to "Recovered in ${totalRecoveryTimeMs}ms"
      is ChaosEvent.RecoveryFailed -> "FAILED" to "Recovery failed: $reason"
      is ChaosEvent.PeriodicChaosStarted -> "PERIODIC" to "Periodic chaos started (${intervalMs}ms)"
      is ChaosEvent.PeriodicChaosStopped -> "PERIODIC" to "Periodic chaos stopped"
      is ChaosEvent.MetricsReset -> "RESET" to "Metrics reset"
    }
    return ChaosEventDisplay(
      timestamp = timestamp,
      type = type,
      description = description,
    )
  }

  companion object {
    private const val MAX_EVENTS = 50
  }
}
