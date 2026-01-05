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
package me.fleey.futon.domain.automation.daemon

import android.util.Log
import kotlinx.coroutines.delay
import me.fleey.futon.config.AutomationConfig
import me.fleey.futon.data.daemon.DaemonLifecycleManager
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.domain.automation.hotpath.HotPathExecutor
import org.koin.core.annotation.Single

sealed interface DaemonCoordinationResult {
  data object Ready : DaemonCoordinationResult
  data object NotAvailable : DaemonCoordinationResult
  data class RecoveryFailed(val reason: String) : DaemonCoordinationResult
}

/**
 * Coordinates daemon lifecycle and recovery operations.
 */
interface DaemonCoordinator {
  val isDaemonAvailable: Boolean
  val restartAttempts: Int

  suspend fun ensureReady(): DaemonCoordinationResult
  suspend fun handleCrash(): Boolean
  suspend fun configureHotPath(): Boolean
  suspend fun stopAutomation()
  fun reset()
}

@Single(binds = [DaemonCoordinator::class])
class DaemonCoordinatorImpl(
  private val daemonRepository: DaemonRepository,
  private val daemonLifecycleManager: DaemonLifecycleManager,
  private val hotPathExecutor: HotPathExecutor,
) : DaemonCoordinator {

  private var _restartAttempts = 0
  override val restartAttempts: Int get() = _restartAttempts

  override val isDaemonAvailable: Boolean
    get() = daemonRepository.daemonState.value is DaemonState.Ready

  override suspend fun ensureReady(): DaemonCoordinationResult {
    val currentState = daemonRepository.daemonState.value

    if (currentState is DaemonState.Ready) {
      Log.d(TAG, "Daemon already ready")
      return DaemonCoordinationResult.Ready
    }

    if (!daemonLifecycleManager.isDaemonRunning()) {
      Log.i(TAG, "Starting daemon...")
      val startResult = daemonLifecycleManager.startDaemon()
      if (startResult.isFailure) {
        Log.e(TAG, "Failed to start daemon: ${startResult.exceptionOrNull()?.message}")
        return DaemonCoordinationResult.NotAvailable
      }
    }

    val connectResult = daemonRepository.connect()
    if (connectResult.isFailure) {
      Log.e(TAG, "Failed to connect to daemon: ${connectResult.exceptionOrNull()?.message}")
      return DaemonCoordinationResult.NotAvailable
    }

    return if (daemonRepository.daemonState.value is DaemonState.Ready) {
      DaemonCoordinationResult.Ready
    } else {
      DaemonCoordinationResult.NotAvailable
    }
  }

  override suspend fun handleCrash(): Boolean {
    if (_restartAttempts >= AutomationConfig.DaemonRecovery.MAX_RESTART_ATTEMPTS) {
      Log.e(TAG, "Max restart attempts exceeded")
      return false
    }

    _restartAttempts++
    Log.i(
      TAG,
      "Attempting daemon restart ($_restartAttempts/${AutomationConfig.DaemonRecovery.MAX_RESTART_ATTEMPTS})",
    )

    // Exponential backoff
    val backoffMs =
      AutomationConfig.DaemonRecovery.INITIAL_BACKOFF_MS * (1 shl (_restartAttempts - 1))
    val cappedBackoff = backoffMs.coerceAtMost(AutomationConfig.DaemonRecovery.MAX_BACKOFF_MS)
    delay(cappedBackoff)

    val restartResult = daemonLifecycleManager.restartDaemon()
    if (restartResult.isFailure) {
      Log.e(TAG, "Daemon restart failed: ${restartResult.exceptionOrNull()?.message}")
      return false
    }

    val connectResult = daemonRepository.connect()
    if (connectResult.isFailure) {
      Log.e(TAG, "Failed to reconnect after restart")
      return false
    }

    hotPathExecutor.configureDaemon()

    Log.i(TAG, "Daemon recovery successful")
    return true
  }

  override suspend fun configureHotPath(): Boolean {
    return hotPathExecutor.configureDaemon()
  }

  override suspend fun stopAutomation() {
    daemonRepository.stopAutomation()
  }

  override fun reset() {
    _restartAttempts = 0
  }

  companion object {
    private const val TAG = "DaemonCoordinator"
  }
}
