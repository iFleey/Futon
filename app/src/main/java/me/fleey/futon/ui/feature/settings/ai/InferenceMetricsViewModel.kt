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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.repository.InferenceStatsRepository
import me.fleey.futon.data.ai.repository.ProviderRepository
import org.koin.android.annotation.KoinViewModel

data class ModelMetricsUi(
  val modelId: String,
  val providerName: String,
  val requestCount: Int = 0,
  val inputTokens: Long = 0,
  val outputTokens: Long = 0,
  val totalLatencyMs: Long = 0,
  val totalCost: Double = 0.0,
) {
  val totalTokens: Long get() = inputTokens + outputTokens
  val avgLatencyMs: Long get() = if (requestCount > 0) totalLatencyMs / requestCount else 0
}

data class InferenceMetricsUiState(
  val modelMetrics: List<ModelMetricsUi> = emptyList(),
  val totalCost: Double = 0.0,
  val totalRequests: Int = 0,
  val totalInputTokens: Long = 0,
  val totalOutputTokens: Long = 0,
  val isLoading: Boolean = true,
)

sealed interface InferenceMetricsUiEvent {
  data object ResetStatistics : InferenceMetricsUiEvent
}

@KoinViewModel
class InferenceMetricsViewModel(
  private val statsRepository: InferenceStatsRepository,
  private val providerRepository: ProviderRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(InferenceMetricsUiState())
  val uiState: StateFlow<InferenceMetricsUiState> = _uiState.asStateFlow()

  init {
    loadMetrics()
  }

  private fun loadMetrics() {
    viewModelScope.launch {
      combine(
        statsRepository.getStatsFlow(),
        providerRepository.getProvidersFlow(),
      ) { aggregate, providers ->
        val providerNameMap = providers.associate { it.id to it.name }

        val metrics = aggregate.modelStats.values.map { stats ->
          ModelMetricsUi(
            modelId = stats.modelId,
            providerName = providerNameMap[stats.providerId] ?: stats.providerId,
            requestCount = stats.requestCount,
            inputTokens = stats.inputTokens,
            outputTokens = stats.outputTokens,
            totalLatencyMs = stats.totalLatencyMs,
            totalCost = stats.totalCost,
          )
        }.sortedByDescending { it.requestCount }

        InferenceMetricsUiState(
          modelMetrics = metrics,
          totalCost = aggregate.totalCost,
          totalRequests = aggregate.totalRequests,
          totalInputTokens = aggregate.totalInputTokens,
          totalOutputTokens = aggregate.totalOutputTokens,
          isLoading = false,
        )
      }.collect { state ->
        _uiState.value = state
      }
    }
  }

  fun onEvent(event: InferenceMetricsUiEvent) {
    when (event) {
      InferenceMetricsUiEvent.ResetStatistics -> resetStatistics()
    }
  }

  private fun resetStatistics() {
    viewModelScope.launch {
      statsRepository.resetStats()
    }
  }
}
