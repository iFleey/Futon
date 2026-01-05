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
package me.fleey.futon.ui.feature.localmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.download.DownloadState
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.SortOption
import me.fleey.futon.domain.localmodel.LocalModelManager
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class LocalModelViewModel(
  private val localModelManager: LocalModelManager,
) : ViewModel() {

  private val _uiState = MutableStateFlow(LocalModelUiState())
  val uiState: StateFlow<LocalModelUiState> = _uiState.asStateFlow()

  private val downloadJobs = mutableMapOf<String, Job>()

  init {
    loadInitialData()
    observeFlows()
  }

  fun onEvent(event: LocalModelUiEvent) {
    when (event) {
      // Download events
      is LocalModelUiEvent.DownloadModel -> handleDownloadModel(event.modelId, event.quantization)
      is LocalModelUiEvent.PauseDownload -> handlePauseDownload(event.modelId)
      is LocalModelUiEvent.ResumeDownload -> handleResumeDownload(event.modelId)
      is LocalModelUiEvent.CancelDownload -> handleCancelDownload(event.modelId)

      // Model lifecycle events
      is LocalModelUiEvent.EnableModel -> handleEnableModel(event.modelId)
      LocalModelUiEvent.DisableModel -> handleDisableModel()
      is LocalModelUiEvent.RequestDeleteModel -> handleRequestDeleteModel(event.modelId)
      LocalModelUiEvent.ConfirmDeleteModel -> handleConfirmDeleteModel()
      LocalModelUiEvent.CancelDeleteModel -> handleCancelDeleteModel()

      // Import events
      LocalModelUiEvent.OpenImportPicker -> handleOpenImportPicker()
      LocalModelUiEvent.CloseImportPicker -> handleCloseImportPicker()
      is LocalModelUiEvent.ImportModel -> handleImportModel(event.modelPath, event.mmprojPath)
      LocalModelUiEvent.CancelImport -> handleCancelImport()

      // Configuration events
      is LocalModelUiEvent.SetDownloadSource -> handleSetDownloadSource(event.source)
      is LocalModelUiEvent.UpdateInferenceConfig -> handleUpdateInferenceConfig(event.config)
      is LocalModelUiEvent.ApplyInferencePreset -> handleApplyInferencePreset(event.preset)
      is LocalModelUiEvent.SetContextLength -> handleSetContextLength(event.length)
      is LocalModelUiEvent.SetThreadCount -> handleSetThreadCount(event.count)
      is LocalModelUiEvent.SetUseNnapi -> handleSetUseNnapi(event.enabled)

      is LocalModelUiEvent.SetSearchQuery -> handleSetSearchQuery(event.query)
      is LocalModelUiEvent.SetModelTypeFilter -> handleSetModelTypeFilter(event.isVlm)
      is LocalModelUiEvent.SetRamFilter -> handleSetRamFilter(event.maxRamMb)
      is LocalModelUiEvent.SetSortOption -> handleSetSortOption(event.sortOption)
      LocalModelUiEvent.ClearFilters -> handleClearFilters()
      LocalModelUiEvent.ResetFilters -> handleResetFilters()

      is LocalModelUiEvent.SetSearchExpanded -> handleSetSearchExpanded(event.expanded)
      is LocalModelUiEvent.SetDeviceCapabilitiesCollapsed -> handleSetDeviceCapabilitiesCollapsed(
        event.collapsed,
      )

      is LocalModelUiEvent.ToggleModelCardExpanded -> handleToggleModelCardExpanded(event.modelId)

      LocalModelUiEvent.RefreshCatalog -> handleRefreshCatalog()
      LocalModelUiEvent.DismissCatalogError -> handleDismissCatalogError()

      LocalModelUiEvent.DismissSuccess -> dismissSuccess()
      LocalModelUiEvent.DismissError -> dismissError()
      LocalModelUiEvent.Refresh -> refresh()
    }
  }


  private fun loadInitialData() {
    viewModelScope.launch {
      try {
        val capabilities = localModelManager.getDeviceCapabilities()
        val recommendedQuantization = localModelManager.getRecommendedQuantization()

        val availableStorage = localModelManager.getAvailableStorage()

        val downloadSource = localModelManager.getDownloadSource()

        _uiState.update { state ->
          state.copy(
            deviceCapabilities = capabilities,
            recommendedQuantization = recommendedQuantization,
            availableStorageBytes = availableStorage,
            downloadSource = downloadSource,
            activeModelId = localModelManager.getActiveModelId(),
            isLoading = false,
          )
        }
      } catch (e: Exception) {
        _uiState.update { state ->
          state.copy(
            isLoading = false,
            errorMessage = e.message,
          )
        }
      }
    }
  }

  private fun observeFlows() {
    viewModelScope.launch {
      combine(
        localModelManager.getDownloadedModels(),
        localModelManager.getActiveDownloads(),
        localModelManager.getInferenceConfig(),
      ) { downloaded, downloads, config ->
        FlowData(downloaded, downloads, config)
      }
        .catch { e ->
          _uiState.update { it.copy(errorMessage = e.message) }
        }
        .collect { data ->
          _uiState.update { state ->
            state.copy(
              downloadedModels = data.downloadedModels,
              activeDownloads = data.activeDownloads.associateBy { it.modelId },
              inferenceConfig = data.inferenceConfig,
              activeModelId = localModelManager.getActiveModelId(),
            )
          }
        }
    }

  }

  private fun handleDownloadModel(modelId: String, quantization: QuantizationType) {
    // Cancel any existing download job for this model
    downloadJobs[modelId]?.cancel()

    val job = viewModelScope.launch {
      localModelManager.downloadModel(modelId, quantization)
        .catch { e ->
          _uiState.update { it.copy(errorMessage = e.message) }
        }
        .collect { state ->
          when (state) {
            is DownloadState.Completed -> {
              _uiState.update { it.copy(successMessageRes = R.string.success_download_complete) }
              downloadJobs.remove(modelId)
            }

            is DownloadState.Failed -> {
              _uiState.update { it.copy(errorMessage = state.error.message) }
              downloadJobs.remove(modelId)
            }

            else -> {
              // Progress updates are handled by the flow observer
            }
          }
        }
    }

    downloadJobs[modelId] = job
  }

  /**
   * Pause an active download.
   */
  private fun handlePauseDownload(modelId: String) {
    viewModelScope.launch {
      localModelManager.pauseDownload(modelId)
        .onSuccess { _uiState.update { it.copy(successMessageRes = R.string.success_download_paused) } }
        .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
    }
  }

  /**
   * Resume a paused download.
   */
  private fun handleResumeDownload(modelId: String) {
    viewModelScope.launch {
      localModelManager.resumeDownload(modelId)
        .onSuccess { _uiState.update { it.copy(successMessageRes = R.string.success_download_resumed) } }
        .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
    }
  }

  /**
   * Cancel an active or paused download.
   */
  private fun handleCancelDownload(modelId: String) {
    downloadJobs[modelId]?.cancel()
    downloadJobs.remove(modelId)

    viewModelScope.launch {
      localModelManager.cancelDownload(modelId)
        .onSuccess { _uiState.update { it.copy(successMessageRes = R.string.success_download_cancelled) } }
        .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message) } }
    }
  }


  /**
   * Enable a downloaded model as the active AI provider.
   */
  private fun handleEnableModel(modelId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSaving = true) }

      localModelManager.enableModel(modelId)
        .onSuccess {
          _uiState.update { state ->
            state.copy(
              activeModelId = modelId,
              isSaving = false,
              successMessageRes = R.string.success_model_enabled,
            )
          }
        }
        .onFailure { e ->
          _uiState.update { state ->
            state.copy(
              isSaving = false,
              errorMessage = e.message,
            )
          }
        }
    }
  }

  /**
   * Disable the currently active local model.
   */
  private fun handleDisableModel() {
    viewModelScope.launch {
      _uiState.update { it.copy(isSaving = true) }

      localModelManager.disableModel()
        .onSuccess {
          _uiState.update { state ->
            state.copy(
              activeModelId = null,
              isSaving = false,
              successMessageRes = R.string.success_model_disabled,
            )
          }
        }
        .onFailure { e ->
          _uiState.update { state ->
            state.copy(
              isSaving = false,
              errorMessage = e.message,
            )
          }
        }
    }
  }

  /**
   * Request deletion of a model (shows confirmation dialog).
   */
  private fun handleRequestDeleteModel(modelId: String) {
    _uiState.update { it.copy(modelPendingDeletion = modelId) }
  }

  /**
   * Confirm deletion of the pending model.
   */
  private fun handleConfirmDeleteModel() {
    val modelId = _uiState.value.modelPendingDeletion ?: return

    viewModelScope.launch {
      _uiState.update { it.copy(modelPendingDeletion = null, isSaving = true) }

      localModelManager.deleteModel(modelId)
        .onSuccess {
          _uiState.update { it.copy(isSaving = false, successMessageRes = R.string.success_model_deleted) }
          refreshStorage()
        }
        .onFailure { e ->
          _uiState.update { state ->
            state.copy(
              isSaving = false,
              errorMessage = e.message,
            )
          }
        }
    }
  }

  /**
   * Cancel the pending model deletion.
   */
  private fun handleCancelDeleteModel() {
    _uiState.update { it.copy(modelPendingDeletion = null) }
  }


  private fun handleOpenImportPicker() {
    _uiState.update { it.copy(showImportPicker = true) }
  }

  private fun handleCloseImportPicker() {
    _uiState.update { it.copy(showImportPicker = false) }
  }

  /**
   * Import a model from the selected file path.
   */
  private fun handleImportModel(modelPath: String, mmprojPath: String?) {
    viewModelScope.launch {
      _uiState.update { state ->
        state.copy(
          showImportPicker = false,
          importProgress = ImportUiState(
            isImporting = true,
            stageRes = R.string.import_stage_validating,
            progress = 0,
            modelPath = modelPath,
          ),
        )
      }

      val requiresMmproj = localModelManager.isVisionLanguageModel(modelPath)
      if (requiresMmproj && mmprojPath == null) {
        _uiState.update { state ->
          state.copy(
            importProgress = state.importProgress?.copy(
              requiresMmproj = true,
              stageRes = R.string.import_stage_mmproj_required,
              isImporting = false,
            ),
          )
        }
        return@launch
      }

      _uiState.update { state ->
        state.copy(
          importProgress = state.importProgress?.copy(
            stageRes = R.string.import_stage_importing,
            progress = 50,
          ),
        )
      }

      localModelManager.importModel(modelPath, mmprojPath)
        .onSuccess { model ->
          _uiState.update { it.copy(importProgress = null, successMessageRes = R.string.success_model_imported) }
          refreshStorage()
        }
        .onFailure { e ->
          _uiState.update { state ->
            state.copy(
              importProgress = state.importProgress?.copy(
                isImporting = false,
                error = e.message,
              ),
            )
          }
        }
    }
  }

  private fun handleCancelImport() {
    _uiState.update { it.copy(importProgress = null) }
  }


  /**
   * Change the download source preference.
   */
  private fun handleSetDownloadSource(source: DownloadSource) {
    viewModelScope.launch {
      localModelManager.setDownloadSource(source)
        .onSuccess {
          _uiState.update { it.copy(downloadSource = source) }
        }
        .onFailure { e ->
          _uiState.update { it.copy(errorMessage = e.message) }
        }
    }
  }

  /**
   * Update the inference configuration.
   */
  private fun handleUpdateInferenceConfig(config: InferenceConfig) {
    viewModelScope.launch {
      localModelManager.updateInferenceConfig(config)
        .onSuccess {
          _uiState.update { it.copy(inferenceConfig = config) }
        }
        .onFailure { e ->
          _uiState.update { it.copy(errorMessage = e.message) }
        }
    }
  }

  /**
   * Apply a preset inference configuration.
   */
  private fun handleApplyInferencePreset(preset: InferencePreset) {
    val config = when (preset) {
      InferencePreset.FAST -> InferenceConfig.FAST
      InferencePreset.QUALITY -> InferenceConfig.QUALITY
      InferencePreset.DEFAULT -> InferenceConfig()
    }
    handleUpdateInferenceConfig(config)
  }

  /**
   * Update context length setting.
   */
  private fun handleSetContextLength(length: Int) {
    val currentConfig = _uiState.value.inferenceConfig
    handleUpdateInferenceConfig(currentConfig.copy(contextLength = length))
  }

  /**
   * Update thread count setting.
   */
  private fun handleSetThreadCount(count: Int) {
    val currentConfig = _uiState.value.inferenceConfig
    handleUpdateInferenceConfig(currentConfig.copy(numThreads = count))
  }

  /**
   * Toggle NNAPI acceleration.
   */
  private fun handleSetUseNnapi(enabled: Boolean) {
    val currentConfig = _uiState.value.inferenceConfig
    handleUpdateInferenceConfig(currentConfig.copy(useNnapi = enabled))
  }


  /**
   * Update the search query for filtering models.
   */
  private fun handleSetSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  /**
   * Set the model type filter.
   *
  )
   */
  private fun handleSetModelTypeFilter(isVlm: Boolean?) {
    _uiState.update { it.copy(modelTypeFilter = isVlm) }
  }

  /**
   * Set the RAM requirement filter.
   */
  private fun handleSetRamFilter(maxRamMb: Int?) {
    _uiState.update { it.copy(ramFilter = maxRamMb) }
  }

  /**
   * Set the sort option for the model list.
   */
  private fun handleSetSortOption(sortOption: SortOption) {
    _uiState.update { it.copy(sortOption = sortOption) }
  }

  /**
   * Clear all search and filter settings.
   */
  private fun handleClearFilters() {
    _uiState.update { state ->
      state.copy(
        searchQuery = "",
        modelTypeFilter = null,
        ramFilter = null,
        sortOption = SortOption.NAME,
      )
    }
  }

  /**
   * Reset all filters to default state.
   */
  private fun handleResetFilters() {
    _uiState.update { state ->
      state.copy(
        searchQuery = "",
        modelTypeFilter = null,
        ramFilter = null,
        sortOption = SortOption.NAME,
        isSearchExpanded = false,
      )
    }
  }


  /**
   * Set the search bar expanded state.
   */
  private fun handleSetSearchExpanded(expanded: Boolean) {
    _uiState.update { it.copy(isSearchExpanded = expanded) }
  }

  /**
   * Set the device capabilities section collapsed state.
   */
  private fun handleSetDeviceCapabilitiesCollapsed(collapsed: Boolean) {
    _uiState.update { it.copy(isDeviceCapabilitiesCollapsed = collapsed) }
  }

  /**
   * Toggle a model card's expanded/collapsed state.
   */
  private fun handleToggleModelCardExpanded(modelId: String) {
    _uiState.update { state ->
      val expandedCards = state.expandedModelCards.toMutableSet()
      if (modelId in expandedCards) {
        expandedCards.remove(modelId)
      } else {
        expandedCards.add(modelId)
      }
      state.copy(expandedModelCards = expandedCards)
    }
  }


  /**
   * Refresh the model catalog from the network.
   *
   * This handles pull-to-refresh functionality by forcing a network fetch
   * and updating the UI state accordingly.
   */
  private fun handleRefreshCatalog() {
  }


  private fun handleDismissCatalogError() {
  }

  private fun dismissSuccess() {
    _uiState.update { it.copy(successMessage = null, successMessageRes = null) }
  }

  private fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  private fun refresh() {
    _uiState.update { it.copy(isLoading = true) }
    loadInitialData()
  }

  private fun refreshStorage() {
    viewModelScope.launch {
      val availableStorage = localModelManager.getAvailableStorage()
      _uiState.update { it.copy(availableStorageBytes = availableStorage) }
    }
  }

  override fun onCleared() {
    super.onCleared()
    // Cancel all active download jobs
    downloadJobs.values.forEach { it.cancel() }
    downloadJobs.clear()
  }
}


private data class FlowData(
  val downloadedModels: List<DownloadedModel>,
  val activeDownloads: List<DownloadProgress>,
  val inferenceConfig: InferenceConfig,
)
