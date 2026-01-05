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
package me.fleey.futon.domain.automation.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.domain.automation.models.ActionLogEntry
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.domain.automation.models.DaemonHealth
import me.fleey.futon.domain.automation.models.ExecutionPhase
import org.koin.core.annotation.Single

interface AutomationStateManager {
  val state: StateFlow<AutomationState>
  val automationMode: StateFlow<AutomationMode>
  val aiDecisionMode: StateFlow<AIDecisionMode>
  val daemonHealth: StateFlow<DaemonHealth>

  fun reset()
  fun setMode(mode: AutomationMode)
  fun setAIDecisionMode(mode: AIDecisionMode)

  fun updateRunning(
    task: String,
    step: Int,
    maxSteps: Int,
    phase: ExecutionPhase,
    lastAction: String? = null,
    actionHistory: List<ActionLogEntry> = emptyList(),
    currentReasoning: String? = null,
    taskStartTimeMs: Long = System.currentTimeMillis(),
    stepStartTimeMs: Long = System.currentTimeMillis(),
    retryAttempt: Int = 0,
    retryReason: String? = null,
  )

  fun complete(result: AutomationResult)

  // Daemon health tracking
  fun recordDaemonCrash()
  fun recordDaemonRestart()
  fun recordDaemonRecovery()
  fun recordAiCall(success: Boolean)
  fun recordHotPathResult(hit: Boolean)
  fun markDaemonUnhealthy(message: String)
}

@Single(binds = [AutomationStateManager::class])
class AutomationStateManagerImpl : AutomationStateManager {

  private val _state = MutableStateFlow<AutomationState>(AutomationState.Idle)
  override val state: StateFlow<AutomationState> = _state.asStateFlow()

  private val _automationMode = MutableStateFlow(AutomationMode.HYBRID)
  override val automationMode: StateFlow<AutomationMode> = _automationMode.asStateFlow()

  private val _aiDecisionMode = MutableStateFlow(AIDecisionMode.IDLE)
  override val aiDecisionMode: StateFlow<AIDecisionMode> = _aiDecisionMode.asStateFlow()

  private val _daemonHealth = MutableStateFlow(DaemonHealth.INITIAL)
  override val daemonHealth: StateFlow<DaemonHealth> = _daemonHealth.asStateFlow()

  override fun reset() {
    _state.value = AutomationState.Idle
    _automationMode.value = AutomationMode.HYBRID
    _aiDecisionMode.value = AIDecisionMode.IDLE
    _daemonHealth.update { it.recordRecovery() }
  }

  override fun setMode(mode: AutomationMode) {
    _automationMode.value = mode
  }

  override fun setAIDecisionMode(mode: AIDecisionMode) {
    _aiDecisionMode.value = mode
  }

  override fun updateRunning(
    task: String,
    step: Int,
    maxSteps: Int,
    phase: ExecutionPhase,
    lastAction: String?,
    actionHistory: List<ActionLogEntry>,
    currentReasoning: String?,
    taskStartTimeMs: Long,
    stepStartTimeMs: Long,
    retryAttempt: Int,
    retryReason: String?,
  ) {
    _state.value = AutomationState.Running(
      taskDescription = task,
      currentStep = step,
      maxSteps = maxSteps,
      phase = phase,
      lastAction = lastAction,
      actionHistory = actionHistory,
      currentReasoning = currentReasoning,
      startTimeMs = taskStartTimeMs,
      stepStartTimeMs = stepStartTimeMs,
      retryAttempt = retryAttempt,
      retryReason = retryReason,
    )
  }

  override fun complete(result: AutomationResult) {
    _aiDecisionMode.value = AIDecisionMode.IDLE
    _state.value = AutomationState.Completed(result)
  }

  override fun recordDaemonCrash() {
    _daemonHealth.update { it.recordCrash() }
  }

  override fun recordDaemonRestart() {
    _daemonHealth.update { it.recordRestart() }
  }

  override fun recordDaemonRecovery() {
    _daemonHealth.update { it.recordRecovery() }
  }

  override fun recordAiCall(success: Boolean) {
    _daemonHealth.update { it.recordAiCall(success) }
  }

  override fun recordHotPathResult(hit: Boolean) {
    _daemonHealth.update { it.recordHotPathResult(hit) }
  }

  override fun markDaemonUnhealthy(message: String) {
    _daemonHealth.update { it.markUnhealthy(message) }
  }
}
