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
package me.fleey.futon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.fleey.futon.data.daemon.DaemonPerformanceMonitor
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.DaemonPerformanceMetrics
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.history.models.TaskHistoryItem
import org.koin.android.annotation.KoinViewModel

data class MainUiState(
  val isDaemonReady: Boolean = false,
)

sealed interface MainUiEvent {
  data object RetryDaemonConnection : MainUiEvent
}

@KoinViewModel
class MainViewModel(
  private val conversationManager: ConversationManager,
  private val daemonRepository: DaemonRepository,
  private val performanceMonitor: DaemonPerformanceMonitor,
) : ViewModel() {

  private val _uiState = MutableStateFlow(MainUiState())
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  val daemonState: StateFlow<DaemonState> = daemonRepository.daemonState
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = DaemonState.Stopped,
    )

  val daemonMetrics: StateFlow<DaemonPerformanceMetrics?> = performanceMonitor.performanceMetrics
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = null,
    )

  init {
    observeDaemonState()
  }

  private fun observeDaemonState() {
    viewModelScope.launch {
      daemonRepository.daemonState.collect { state ->
        _uiState.value = MainUiState(isDaemonReady = state is DaemonState.Ready)
      }
    }
  }

  fun onEvent(event: MainUiEvent) {
    when (event) {
      MainUiEvent.RetryDaemonConnection -> retryDaemonConnection()
    }
  }

  private fun retryDaemonConnection() {
    viewModelScope.launch {
      daemonRepository.connect()
    }
  }

  fun loadConversation(item: TaskHistoryItem) {
    conversationManager.loadConversation(item)
  }

  fun clearConversation() {
    conversationManager.clearConversation()
  }
}
