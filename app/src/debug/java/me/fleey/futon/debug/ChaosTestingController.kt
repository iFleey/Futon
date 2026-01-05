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
package me.fleey.futon.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Debug-only controller for chaos testing the daemon integration.
 * Simulates various failure scenarios to test recovery mechanisms.
 */
@RequiresDebugBuild
class ChaosTestingController {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val isEnabled = AtomicBoolean(false)

  // Chaos configuration
  private val _config = MutableStateFlow(ChaosConfig())
  val config: StateFlow<ChaosConfig> = _config.asStateFlow()

  // Recovery metrics
  private val _recoveryMetrics = MutableStateFlow(RecoveryMetrics())
  val recoveryMetrics: StateFlow<RecoveryMetrics> = _recoveryMetrics.asStateFlow()

  // Event history
  private val _chaosEvents = MutableSharedFlow<ChaosEvent>(replay = 100)
  val chaosEvents: SharedFlow<ChaosEvent> = _chaosEvents.asSharedFlow()

  private val eventHistory = CopyOnWriteArrayList<ChaosEvent>()

  // Timing tracking
  private val crashDetectionStartTime = AtomicLong(0)
  private val reconciliationStartTime = AtomicLong(0)

  // Callbacks for triggering chaos
  private var onCrashSimulation: (() -> Unit)? = null
  private var onNetworkDelaySimulation: ((Long) -> Unit)? = null
  private var onMemoryPressureSimulation: ((Int) -> Unit)? = null
  private var onAuthFailureSimulation: (() -> Unit)? = null

  fun enable() {
    isEnabled.set(true)
    emitEvent(ChaosEvent.ControllerEnabled)
  }

  fun disable() {
    isEnabled.set(false)
    emitEvent(ChaosEvent.ControllerDisabled)
  }

  fun isEnabled(): Boolean = isEnabled.get()

  fun updateConfig(newConfig: ChaosConfig) {
    _config.value = newConfig
    emitEvent(ChaosEvent.ConfigUpdated(newConfig))
  }

  // ========== Chaos Triggers ==========

  fun simulateDaemonCrash() {
    if (!isEnabled.get()) return

    crashDetectionStartTime.set(System.currentTimeMillis())
    emitEvent(ChaosEvent.CrashSimulated)
    onCrashSimulation?.invoke()
  }

  fun simulateNetworkDelay(delayMs: Long = config.value.networkDelayMs) {
    if (!isEnabled.get()) return

    emitEvent(ChaosEvent.NetworkDelaySimulated(delayMs))
    onNetworkDelaySimulation?.invoke(delayMs)
  }

  fun simulateMemoryPressure(level: Int = config.value.memoryPressureLevel) {
    if (!isEnabled.get()) return

    emitEvent(ChaosEvent.MemoryPressureSimulated(level))
    onMemoryPressureSimulation?.invoke(level)
  }

  fun simulateAuthenticationFailure() {
    if (!isEnabled.get()) return

    emitEvent(ChaosEvent.AuthFailureSimulated)
    onAuthFailureSimulation?.invoke()
  }

  private fun simulateRandomChaos() {
    if (!isEnabled.get()) return

    val chaosType = Random.nextInt(4)
    when (chaosType) {
      0 -> simulateDaemonCrash()
      1 -> simulateNetworkDelay()
      2 -> simulateMemoryPressure()
      3 -> simulateAuthenticationFailure()
    }
  }

  fun onCrashDetected() {
    val detectionTime = System.currentTimeMillis()
    val startTime = crashDetectionStartTime.get()
    if (startTime > 0) {
      val detectionDuration = detectionTime - startTime
      updateMetrics { it.copy(lastCrashDetectionTimeMs = detectionDuration) }
      emitEvent(ChaosEvent.CrashDetected(detectionDuration))
    }
    reconciliationStartTime.set(detectionTime)
  }

  fun onReconciliationStarted() {
    reconciliationStartTime.set(System.currentTimeMillis())
    emitEvent(ChaosEvent.ReconciliationStarted)
  }

  fun onReconciliationCompleted() {
    val completionTime = System.currentTimeMillis()
    val startTime = reconciliationStartTime.get()
    if (startTime > 0) {
      val reconciliationDuration = completionTime - startTime
      val crashStart = crashDetectionStartTime.get()
      val totalRecoveryTime =
        if (crashStart > 0) completionTime - crashStart else reconciliationDuration

      updateMetrics {
        it.copy(
          lastReconciliationTimeMs = reconciliationDuration,
          lastTotalRecoveryTimeMs = totalRecoveryTime,
          successfulRecoveries = it.successfulRecoveries + 1,
        )
      }
      emitEvent(ChaosEvent.ReconciliationCompleted(reconciliationDuration, totalRecoveryTime))
    }
    crashDetectionStartTime.set(0)
    reconciliationStartTime.set(0)
  }

  fun onRecoveryFailed(reason: String) {
    updateMetrics { it.copy(failedRecoveries = it.failedRecoveries + 1) }
    emitEvent(ChaosEvent.RecoveryFailed(reason))
    crashDetectionStartTime.set(0)
    reconciliationStartTime.set(0)
  }

  // ========== Callback Registration ==========

  fun setOnCrashSimulation(callback: () -> Unit) {
    onCrashSimulation = callback
  }

  fun setOnNetworkDelaySimulation(callback: (Long) -> Unit) {
    onNetworkDelaySimulation = callback
  }

  fun setOnMemoryPressureSimulation(callback: (Int) -> Unit) {
    onMemoryPressureSimulation = callback
  }

  fun setOnAuthFailureSimulation(callback: () -> Unit) {
    onAuthFailureSimulation = callback
  }

  fun startPeriodicChaos(intervalMs: Long = config.value.periodicChaosIntervalMs) {
    if (!isEnabled.get()) return

    scope.launch {
      while (isEnabled.get() && config.value.enablePeriodicChaos) {
        delay(intervalMs)
        if (isEnabled.get() && config.value.enablePeriodicChaos) {
          simulateRandomChaos()
        }
      }
    }
    emitEvent(ChaosEvent.PeriodicChaosStarted(intervalMs))
  }

  fun stopPeriodicChaos() {
    updateConfig(config.value.copy(enablePeriodicChaos = false))
    emitEvent(ChaosEvent.PeriodicChaosStopped)
  }

  fun resetMetrics() {
    _recoveryMetrics.value = RecoveryMetrics()
    emitEvent(ChaosEvent.MetricsReset)
  }

  fun clearEventHistory() {
    eventHistory.clear()
  }

  fun getEventHistory(): List<ChaosEvent> = eventHistory.toList()

  fun getAverageRecoveryTime(): Long {
    val metrics = _recoveryMetrics.value
    val totalRecoveries = metrics.successfulRecoveries
    return if (totalRecoveries > 0) {
      metrics.lastTotalRecoveryTimeMs
    } else {
      0L
    }
  }

  private fun emitEvent(event: ChaosEvent) {
    eventHistory.add(event)
    scope.launch {
      _chaosEvents.emit(event)
    }
  }

  private fun updateMetrics(update: (RecoveryMetrics) -> RecoveryMetrics) {
    _recoveryMetrics.value = update(_recoveryMetrics.value)
  }
}

/**
 * Configuration for chaos testing behavior.
 */
data class ChaosConfig(
  val networkDelayMs: Long = 500L,
  val memoryPressureLevel: Int = 2,
  val enablePeriodicChaos: Boolean = false,
  val periodicChaosIntervalMs: Long = 30_000L,
  val crashProbability: Float = 0.1f,
  val networkDelayProbability: Float = 0.2f,
  val memoryPressureProbability: Float = 0.1f,
  val authFailureProbability: Float = 0.05f,
)

/**
 * Metrics tracking recovery performance.
 */
data class RecoveryMetrics(
  val lastCrashDetectionTimeMs: Long = 0L,
  val lastReconciliationTimeMs: Long = 0L,
  val lastTotalRecoveryTimeMs: Long = 0L,
  val successfulRecoveries: Int = 0,
  val failedRecoveries: Int = 0,
  val timestamp: Long = System.currentTimeMillis(),
) {
  val recoverySuccessRate: Float
    get() {
      val total = successfulRecoveries + failedRecoveries
      return if (total > 0) successfulRecoveries.toFloat() / total else 0f
    }
}

/**
 * Events emitted during chaos testing.
 */
sealed interface ChaosEvent {
  val timestamp: Long get() = System.currentTimeMillis()

  data object ControllerEnabled : ChaosEvent
  data object ControllerDisabled : ChaosEvent
  data class ConfigUpdated(val config: ChaosConfig) : ChaosEvent

  data object CrashSimulated : ChaosEvent
  data class NetworkDelaySimulated(val delayMs: Long) : ChaosEvent
  data class MemoryPressureSimulated(val level: Int) : ChaosEvent
  data object AuthFailureSimulated : ChaosEvent

  data class CrashDetected(val detectionTimeMs: Long) : ChaosEvent
  data object ReconciliationStarted : ChaosEvent
  data class ReconciliationCompleted(
    val reconciliationTimeMs: Long,
    val totalRecoveryTimeMs: Long,
  ) : ChaosEvent

  data class RecoveryFailed(val reason: String) : ChaosEvent

  data class PeriodicChaosStarted(val intervalMs: Long) : ChaosEvent
  data object PeriodicChaosStopped : ChaosEvent
  data object MetricsReset : ChaosEvent
}
