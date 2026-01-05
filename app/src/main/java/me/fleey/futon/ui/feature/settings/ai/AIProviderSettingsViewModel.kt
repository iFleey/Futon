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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.data.ai.repository.ProviderRepository
import org.koin.android.annotation.KoinViewModel
import java.util.UUID

data class ProviderUiState(
  val providers: List<Provider> = emptyList(),
  val showAddProviderDialog: Boolean = false,
  val navigateToProviderId: String? = null,
  val isLoading: Boolean = true,
  val errorMessage: String? = null,
)

sealed interface ProviderUiEvent {
  data class ToggleProviderEnabled(val providerId: String, val enabled: Boolean) : ProviderUiEvent
  data object ShowAddProviderDialog : ProviderUiEvent
  data object DismissAddProviderDialog : ProviderUiEvent
  data class CreateProvider(val name: String, val protocol: ApiProtocol, val baseUrl: String) : ProviderUiEvent
  data object NavigationHandled : ProviderUiEvent
  data object RefreshProviders : ProviderUiEvent
  data object DismissError : ProviderUiEvent
}

@KoinViewModel
class AIProviderSettingsViewModel(
  private val providerRepository: ProviderRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ProviderUiState())
  val uiState: StateFlow<ProviderUiState> = _uiState.asStateFlow()

  init {
    loadProviders()
  }

  private fun loadProviders() {
    viewModelScope.launch {
      providerRepository.initializeDefaults()
      val providers = providerRepository.getProviders()

      _uiState.update {
        it.copy(
          providers = providers,
          isLoading = false,
        )
      }
    }
  }

  fun onEvent(event: ProviderUiEvent) {
    when (event) {
      is ProviderUiEvent.ToggleProviderEnabled -> handleToggleProviderEnabled(event.providerId, event.enabled)
      ProviderUiEvent.ShowAddProviderDialog -> _uiState.update { it.copy(showAddProviderDialog = true) }
      ProviderUiEvent.DismissAddProviderDialog -> _uiState.update { it.copy(showAddProviderDialog = false) }
      is ProviderUiEvent.CreateProvider -> createProvider(event.name, event.protocol, event.baseUrl)
      ProviderUiEvent.NavigationHandled -> _uiState.update { it.copy(navigateToProviderId = null) }
      ProviderUiEvent.RefreshProviders -> refreshProviders()
      ProviderUiEvent.DismissError -> _uiState.update { it.copy(errorMessage = null) }
    }
  }

  private fun refreshProviders() {
    viewModelScope.launch {
      val providers = providerRepository.getProviders()
      _uiState.update {
        it.copy(providers = providers)
      }
    }
  }

  private fun handleToggleProviderEnabled(providerId: String, enabled: Boolean) {
    viewModelScope.launch {
      try {
        val provider = providerRepository.getProvider(providerId) ?: return@launch

        if (enabled && !provider.canBeEnabled()) {
          return@launch
        }

        val updatedProvider = provider.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        providerRepository.saveProvider(updatedProvider)
        val updatedProviders = providerRepository.getProviders()
        _uiState.update { it.copy(providers = updatedProviders) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update provider") }
      }
    }
  }

  private fun createProvider(name: String, protocol: ApiProtocol, baseUrl: String) {
    viewModelScope.launch {
      try {
        val newProvider = Provider(
          id = UUID.randomUUID().toString(),
          name = name,
          protocol = protocol,
          baseUrl = baseUrl,
          sortOrder = _uiState.value.providers.size,
        )

        providerRepository.saveProvider(newProvider)
        val updatedProviders = providerRepository.getProviders()

        _uiState.update {
          it.copy(
            providers = updatedProviders,
            showAddProviderDialog = false,
            navigateToProviderId = newProvider.id,
          )
        }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to create provider") }
      }
    }
  }
}
