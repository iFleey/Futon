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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ModelConfig
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.data.ai.repository.ProviderRepository
import org.koin.android.annotation.KoinViewModel
import java.util.UUID

sealed interface ConnectionTestResult {
  data object Idle : ConnectionTestResult
  data object Testing : ConnectionTestResult
  data class Success(val message: String) : ConnectionTestResult
  data class Failure(val error: String) : ConnectionTestResult
}

data class DetailUiState(
  val providerId: String? = null,
  val editingProvider: Provider? = null,
  val originalProvider: Provider? = null,
  val providerModels: List<ModelConfig> = emptyList(),
  val selectedModelId: String? = null,

  val availableModels: List<String> = emptyList(),
  val isLoadingModels: Boolean = false,
  val connectionTestResult: ConnectionTestResult = ConnectionTestResult.Idle,

  val showDeleteDialog: Boolean = false,
  val showAddModelDialog: Boolean = false,
  val showEditModelDialog: Boolean = false,
  val editingModel: ModelConfig? = null,

  val isLoading: Boolean = true,
  val isSaving: Boolean = false,
  val saveSuccess: Boolean = false,
  val providerDeleted: Boolean = false,
  val errorMessage: String? = null,
) {
  val hasUnsavedChanges: Boolean
    get() {
      val editing = editingProvider ?: return false
      val original = originalProvider ?: return true
      return editing.name.trim() != original.name ||
        editing.apiKey.trim() != original.apiKey ||
        editing.baseUrl.trim() != original.baseUrl ||
        editing.enabled != original.enabled ||
        editing.iconKey != original.iconKey
    }
}

sealed interface DetailUiEvent {
  data class ProviderNameChanged(val name: String) : DetailUiEvent
  data class ApiKeyChanged(val value: String) : DetailUiEvent
  data class BaseUrlChanged(val value: String) : DetailUiEvent
  data class ProviderEnabledChanged(val enabled: Boolean) : DetailUiEvent
  data class ProviderIconChanged(val iconKey: String?) : DetailUiEvent

  data object SaveProviderConfig : DetailUiEvent
  data object FetchAvailableModels : DetailUiEvent
  data object TestConnection : DetailUiEvent
  data object DismissConnectionTestResult : DetailUiEvent

  data object ShowDeleteDialog : DetailUiEvent
  data object DismissDeleteDialog : DetailUiEvent
  data object ConfirmDelete : DetailUiEvent

  data object ShowAddModelDialog : DetailUiEvent
  data object DismissAddModelDialog : DetailUiEvent
  data class CreateModel(val modelId: String, val displayName: String) : DetailUiEvent

  data class ShowEditModelDialog(val model: ModelConfig) : DetailUiEvent
  data object DismissEditModelDialog : DetailUiEvent
  data class UpdateModel(val model: ModelConfig) : DetailUiEvent
  data class DeleteModel(val modelId: String) : DetailUiEvent
  data class SetActiveModel(val modelId: String) : DetailUiEvent

  data object DismissSaveSuccess : DetailUiEvent
  data object DismissError : DetailUiEvent
}

@KoinViewModel
class AIProviderDetailViewModel(
  private val providerRepository: ProviderRepository,
  private val adapters: Map<ApiProtocol, ProviderAdapter>,
) : ViewModel() {

  private val _uiState = MutableStateFlow(DetailUiState())
  val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

  fun loadProvider(providerId: String) {
    if (_uiState.value.providerId == providerId && !_uiState.value.isLoading) return

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }

      val provider = providerRepository.getProvider(providerId)
      val models = providerRepository.getModels(providerId)

      _uiState.update {
        it.copy(
          providerId = providerId,
          editingProvider = provider,
          originalProvider = provider,
          providerModels = models,
          selectedModelId = provider?.selectedModelId,
          isLoading = false,
        )
      }

      if (provider != null) {
        fetchAvailableModels()
      }
    }
  }

  fun onEvent(event: DetailUiEvent) {
    when (event) {
      is DetailUiEvent.ProviderNameChanged -> updateEditingProvider { it.copy(name = event.name) }
      is DetailUiEvent.ApiKeyChanged -> updateEditingProvider { it.copy(apiKey = event.value) }
      is DetailUiEvent.BaseUrlChanged -> updateEditingProvider { it.copy(baseUrl = event.value) }
      is DetailUiEvent.ProviderEnabledChanged -> updateEditingProvider { it.copy(enabled = event.enabled) }
      is DetailUiEvent.ProviderIconChanged -> updateEditingProvider { it.copy(iconKey = event.iconKey) }
      DetailUiEvent.SaveProviderConfig -> saveProviderConfig()
      DetailUiEvent.FetchAvailableModels -> fetchAvailableModels()
      DetailUiEvent.TestConnection -> testConnection()
      DetailUiEvent.DismissConnectionTestResult -> _uiState.update { it.copy(connectionTestResult = ConnectionTestResult.Idle) }
      DetailUiEvent.ShowDeleteDialog -> _uiState.update { it.copy(showDeleteDialog = true) }
      DetailUiEvent.DismissDeleteDialog -> _uiState.update { it.copy(showDeleteDialog = false) }
      DetailUiEvent.ConfirmDelete -> deleteProvider()
      DetailUiEvent.ShowAddModelDialog -> _uiState.update { it.copy(showAddModelDialog = true) }
      DetailUiEvent.DismissAddModelDialog -> _uiState.update { it.copy(showAddModelDialog = false) }
      is DetailUiEvent.CreateModel -> createModel(event.modelId, event.displayName)
      is DetailUiEvent.ShowEditModelDialog -> _uiState.update {
        it.copy(
          showEditModelDialog = true,
          editingModel = event.model,
        )
      }

      DetailUiEvent.DismissEditModelDialog -> _uiState.update {
        it.copy(
          showEditModelDialog = false,
          editingModel = null,
        )
      }

      is DetailUiEvent.UpdateModel -> updateModel(event.model)
      is DetailUiEvent.DeleteModel -> deleteModel(event.modelId)
      is DetailUiEvent.SetActiveModel -> setActiveModel(event.modelId)
      DetailUiEvent.DismissSaveSuccess -> _uiState.update { it.copy(saveSuccess = false) }
      DetailUiEvent.DismissError -> _uiState.update { it.copy(errorMessage = null) }
    }
  }

  private fun updateEditingProvider(transform: (Provider) -> Provider) {
    val currentProvider = _uiState.value.editingProvider ?: return
    _uiState.update { it.copy(editingProvider = transform(currentProvider)) }
  }

  private fun saveProviderConfig() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return

    if (provider.baseUrl.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Please enter API Base URL") }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isSaving = true) }
      try {
        val trimmedProvider = provider.copy(
          apiKey = provider.apiKey.trim(),
          baseUrl = provider.baseUrl.trim(),
          name = provider.name.trim(),
          updatedAt = System.currentTimeMillis(),
        )

        providerRepository.saveProvider(trimmedProvider)

        _uiState.update {
          it.copy(
            editingProvider = trimmedProvider,
            originalProvider = trimmedProvider,
            isSaving = false,
            saveSuccess = true,
          )
        }

        fetchAvailableModels()

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isSaving = false, errorMessage = e.message ?: "Save failed") }
      }
    }
  }

  private fun deleteProvider() {
    val providerId = _uiState.value.providerId ?: return

    viewModelScope.launch {
      try {
        providerRepository.deleteProvider(providerId)
        _uiState.update { it.copy(showDeleteDialog = false, providerDeleted = true) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to delete provider") }
      }
    }
  }

  private fun createModel(modelId: String, displayName: String) {
    val providerId = _uiState.value.providerId ?: return

    viewModelScope.launch {
      try {
        val newModel = ModelConfig(
          id = UUID.randomUUID().toString(),
          providerId = providerId,
          modelId = modelId,
          displayName = displayName,
        )

        providerRepository.saveModel(newModel)
        val updatedModels = providerRepository.getModels(providerId)

        _uiState.update {
          it.copy(
            providerModels = updatedModels,
            showAddModelDialog = false,
            saveSuccess = true,
          )
        }

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to create model") }
      }
    }
  }

  private fun updateModel(model: ModelConfig) {
    viewModelScope.launch {
      try {
        providerRepository.saveModel(model)
        val updatedModels = providerRepository.getModels(model.providerId)

        _uiState.update {
          it.copy(
            providerModels = updatedModels,
            showEditModelDialog = false,
            editingModel = null,
            saveSuccess = true,
          )
        }

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to update model") }
      }
    }
  }

  private fun deleteModel(modelId: String) {
    val providerId = _uiState.value.providerId ?: return

    viewModelScope.launch {
      try {
        providerRepository.deleteModel(modelId)
        val updatedModels = providerRepository.getModels(providerId)

        _uiState.update {
          it.copy(
            providerModels = updatedModels,
            showEditModelDialog = false,
            editingModel = null,
          )
        }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to delete model") }
      }
    }
  }

  private fun setActiveModel(modelId: String) {
    val providerId = _uiState.value.providerId ?: return
    val provider = _uiState.value.editingProvider ?: return

    if (!provider.isConfigured()) {
      _uiState.update { it.copy(errorMessage = "Please configure API key first") }
      return
    }

    viewModelScope.launch {
      try {
        providerRepository.setProviderSelectedModel(providerId, modelId)

        val updatedProvider = provider.copy(selectedModelId = modelId)
        _uiState.update {
          it.copy(
            editingProvider = updatedProvider,
            originalProvider = updatedProvider,
            selectedModelId = modelId,
            saveSuccess = true,
          )
        }

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to set default model") }
      }
    }
  }

  private fun fetchAvailableModels() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return
    val adapter = adapters[provider.protocol] ?: return

    if (!provider.isConfigured() || provider.baseUrl.isBlank()) {
      _uiState.update {
        it.copy(availableModels = state.providerModels.map { m -> m.modelId })
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingModels = true) }
      try {
        val models = adapter.fetchAvailableModels(provider)
        _uiState.update {
          it.copy(
            availableModels = models.ifEmpty { state.providerModels.map { m -> m.modelId } },
            isLoadingModels = false,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            availableModels = state.providerModels.map { m -> m.modelId },
            isLoadingModels = false,
          )
        }
      }
    }
  }

  private fun testConnection() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return

    if (!provider.isConfigured()) {
      _uiState.update {
        it.copy(connectionTestResult = ConnectionTestResult.Failure("Please configure API key first"))
      }
      return
    }

    val adapter = adapters[provider.protocol]
    if (adapter == null) {
      _uiState.update {
        it.copy(connectionTestResult = ConnectionTestResult.Failure("Unsupported protocol"))
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(connectionTestResult = ConnectionTestResult.Testing) }
      try {
        val models = adapter.fetchAvailableModels(provider)
        _uiState.update {
          it.copy(
            connectionTestResult = ConnectionTestResult.Success(
              if (models.isNotEmpty()) "Connected! Found ${models.size} models"
              else "Connected, but no models found",
            ),
            availableModels = models.ifEmpty { it.availableModels },
          )
        }

        delay(3000)
        _uiState.update {
          if (it.connectionTestResult is ConnectionTestResult.Success) {
            it.copy(connectionTestResult = ConnectionTestResult.Idle)
          } else it
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(connectionTestResult = ConnectionTestResult.Failure(e.message ?: "Connection failed"))
        }
      }
    }
  }
}
