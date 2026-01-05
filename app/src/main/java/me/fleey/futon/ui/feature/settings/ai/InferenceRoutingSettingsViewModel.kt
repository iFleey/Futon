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
package me.fleey.futon.ui.feature.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.routing.InferenceRouter
import me.fleey.futon.data.ai.routing.InferenceSource
import me.fleey.futon.data.ai.routing.RoutingStrategy
import me.fleey.futon.data.ai.routing.SourceAvailability
import org.koin.android.annotation.KoinViewModel

data class InferenceRoutingUiState(
  val priority: List<InferenceSource> = emptyList(),
  val enabledSources: Map<InferenceSource, Boolean> = emptyMap(),
  val availability: Map<InferenceSource, SourceAvailability> = emptyMap(),
  val strategy: RoutingStrategy = RoutingStrategy.DEFAULT,
  val lastUsedSource: InferenceSource? = null,
  val isSaving: Boolean = false,
)

sealed interface InferenceRoutingUiEvent {
  data class ReorderSources(val from: Int, val to: Int) : InferenceRoutingUiEvent
  data class ToggleSource(val source: InferenceSource, val enabled: Boolean) :
    InferenceRoutingUiEvent

  data class SetStrategy(val strategy: RoutingStrategy) : InferenceRoutingUiEvent
}

@KoinViewModel
class InferenceRoutingSettingsViewModel(
  private val inferenceRouter: InferenceRouter,
) : ViewModel() {

  private val _localState = MutableStateFlow(
    InferenceRoutingUiState(),
  )

  val uiState: StateFlow<InferenceRoutingUiState> = combine(
    inferenceRouter.sourceAvailability,
    inferenceRouter.lastUsedSource,
    inferenceRouter.routingConfig,
    _localState,
  ) { availability, lastUsed, config, local ->
    local.copy(
      priority = config.priority,
      enabledSources = config.enabledSources,
      availability = availability,
      strategy = config.strategy,
      lastUsedSource = lastUsed,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = InferenceRoutingUiState(),
  )

  init {
    viewModelScope.launch {
      inferenceRouter.refreshAvailability()
    }
  }

  fun onEvent(event: InferenceRoutingUiEvent) {
    when (event) {
      is InferenceRoutingUiEvent.ReorderSources -> reorderSources(event.from, event.to)
      is InferenceRoutingUiEvent.ToggleSource -> toggleSource(event.source, event.enabled)
      is InferenceRoutingUiEvent.SetStrategy -> setStrategy(event.strategy)
    }
  }

  private fun reorderSources(from: Int, to: Int) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      val currentPriority = uiState.value.priority.toMutableList()
      val item = currentPriority.removeAt(from)
      currentPriority.add(to, item)
      inferenceRouter.updatePriority(currentPriority)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun toggleSource(source: InferenceSource, enabled: Boolean) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      inferenceRouter.setSourceEnabled(source, enabled)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setStrategy(strategy: RoutingStrategy) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      inferenceRouter.updateStrategy(strategy)
      _localState.update { it.copy(isSaving = false) }
    }
  }
}
