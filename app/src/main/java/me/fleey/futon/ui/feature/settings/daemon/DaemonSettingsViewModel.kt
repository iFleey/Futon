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
package me.fleey.futon.ui.feature.settings.daemon

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.DaemonLifecycleManager
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.deployment.RootState
import me.fleey.futon.data.daemon.models.DaemonLifecycleState
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.platform.root.RootChecker
import org.koin.android.annotation.KoinViewModel

data class DaemonSettingsUiState(
  val lifecycleState: DaemonLifecycleState = DaemonLifecycleState.Stopped,
  val rootState: RootState = RootState.NotChecked,
  val keepAliveMs: Long = DaemonConfig.Lifecycle.DEFAULT_KEEP_ALIVE_MS,
  val autoStopEnabled: Boolean = false,
  val isStarting: Boolean = false,
  val isStopping: Boolean = false,
  val error: DaemonSettingsError? = null,
) {
  val isRunning: Boolean
    get() = lifecycleState is DaemonLifecycleState.Running

  val canStart: Boolean
    get() = !isRunning && !isStarting && !isStopping &&
      rootState is RootState.Available

  val canStop: Boolean
    get() = isRunning && !isStopping

  val keepAliveSeconds: Int
    get() = (keepAliveMs / 1000).toInt()
}

sealed interface DaemonSettingsError {
  @get:StringRes
  val messageRes: Int

  data object RootUnavailable : DaemonSettingsError {
    override val messageRes = R.string.daemon_error_root_unavailable
  }

  data object StartFailed : DaemonSettingsError {
    override val messageRes = R.string.daemon_error_start_failed
  }

  data object StopFailed : DaemonSettingsError {
    override val messageRes = R.string.daemon_error_stop_failed
  }

  data class Custom(@param:StringRes override val messageRes: Int) : DaemonSettingsError
}

sealed interface DaemonSettingsUiEvent {
  data object StartDaemon : DaemonSettingsUiEvent
  data object StopDaemon : DaemonSettingsUiEvent
  data object RestartDaemon : DaemonSettingsUiEvent
  data object RefreshStatus : DaemonSettingsUiEvent
  data class SetKeepAliveSeconds(val seconds: Int) : DaemonSettingsUiEvent
  data class SetAutoStopEnabled(val enabled: Boolean) : DaemonSettingsUiEvent
  data object DismissError : DaemonSettingsUiEvent
}

@KoinViewModel
class DaemonSettingsViewModel(
  private val lifecycleManager: DaemonLifecycleManager,
  private val rootChecker: RootChecker,
  private val daemonRepository: DaemonRepository,
) : ViewModel() {

  private val _localState = MutableStateFlow(
    DaemonSettingsUiState(),
  )

  val uiState: StateFlow<DaemonSettingsUiState> = combine(
    lifecycleManager.daemonLifecycle,
    rootChecker.rootState,
    _localState,
  ) { lifecycle, root, local ->
    local.copy(
      lifecycleState = lifecycle,
      rootState = root,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = DaemonSettingsUiState(),
  )

  init {
    viewModelScope.launch {
      rootChecker.checkRoot()
      lifecycleManager.syncLifecycleState()
      // Try to connect to daemon if it's running but not connected
      initializeDaemonConnection()
    }
  }

  private suspend fun initializeDaemonConnection() {
    val currentState = daemonRepository.daemonState.value
    if (currentState !is DaemonState.Ready && currentState !is DaemonState.Connecting) {
      daemonRepository.connect()
    }
  }

  fun onEvent(event: DaemonSettingsUiEvent) {
    when (event) {
      DaemonSettingsUiEvent.StartDaemon -> startDaemon()
      DaemonSettingsUiEvent.StopDaemon -> stopDaemon()
      DaemonSettingsUiEvent.RestartDaemon -> restartDaemon()
      DaemonSettingsUiEvent.RefreshStatus -> refreshStatus()
      is DaemonSettingsUiEvent.SetKeepAliveSeconds -> setKeepAlive(event.seconds)
      is DaemonSettingsUiEvent.SetAutoStopEnabled -> setAutoStop(event.enabled)
      DaemonSettingsUiEvent.DismissError -> dismissError()
    }
  }

  private fun startDaemon() {
    viewModelScope.launch {
      _localState.update { it.copy(isStarting = true, error = null) }
      val result = lifecycleManager.startDaemon()
      if (result.isSuccess) {
        daemonRepository.connect()
      }
      _localState.update {
        it.copy(
          isStarting = false,
          error = if (result.isFailure) DaemonSettingsError.StartFailed else null,
        )
      }
    }
  }

  private fun stopDaemon() {
    viewModelScope.launch {
      _localState.update { it.copy(isStopping = true, error = null) }
      val result = lifecycleManager.stopDaemon()
      _localState.update {
        it.copy(
          isStopping = false,
          error = if (result.isFailure) DaemonSettingsError.StopFailed else null,
        )
      }
    }
  }

  private fun restartDaemon() {
    viewModelScope.launch {
      _localState.update { it.copy(isStarting = true, error = null) }
      val result = lifecycleManager.restartDaemon()
      _localState.update {
        it.copy(
          isStarting = false,
          error = if (result.isFailure) DaemonSettingsError.StartFailed else null,
        )
      }
    }
  }

  private fun refreshStatus() {
    viewModelScope.launch {
      rootChecker.checkRoot(forceRecheck = true)
    }
  }

  private fun setKeepAlive(seconds: Int) {
    val ms = seconds * 1000L
    lifecycleManager.setKeepAliveMs(ms)
    _localState.update { it.copy(keepAliveMs = ms) }
  }

  private fun setAutoStop(enabled: Boolean) {
    _localState.update { it.copy(autoStopEnabled = enabled) }
    if (enabled) {
      lifecycleManager.scheduleKeepAlive()
    } else {
      lifecycleManager.cancelKeepAlive()
    }
  }

  private fun dismissError() {
    _localState.update { it.copy(error = null) }
  }
}
