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

import androidx.annotation.StringRes
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.download.DownloadState
import me.fleey.futon.data.localmodel.inference.DeviceCapabilities
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.SortOption

data class LocalModelUiState(
  val availableModels: List<ModelInfo> = emptyList(),

  val downloadedModels: List<DownloadedModel> = emptyList(),

  val activeModelId: String? = null,

  val activeDownloads: Map<String, DownloadProgress> = emptyMap(),

  val deviceCapabilities: DeviceCapabilities? = null,

  val recommendedQuantization: QuantizationType = QuantizationType.INT4,

  /** Available storage space in bytes */
  val availableStorageBytes: Long = 0L,

  val downloadSource: DownloadSource = DownloadSource.HUGGING_FACE,

  val inferenceConfig: InferenceConfig = InferenceConfig(),

  val isLoadingCatalog: Boolean = false,

  /** Timestamp when the catalog was last updated */
  val catalogLastUpdated: Long? = null,

  val searchQuery: String = "",

  val modelTypeFilter: Boolean? = null,

  val ramFilter: Int? = null,

  val sortOption: SortOption = SortOption.NAME,

  val isLoading: Boolean = true,

  val isSaving: Boolean = false,

  val successMessage: String? = null,

  @param:StringRes val successMessageRes: Int? = null,

  val errorMessage: String? = null,

  val isSearchExpanded: Boolean = false,

  val isDeviceCapabilitiesCollapsed: Boolean = true,

  val expandedModelCards: Set<String> = emptySet(),

  val modelPendingDeletion: String? = null,

  val showImportPicker: Boolean = false,

  /** Import progress state (null if not importing) */
  val importProgress: ImportUiState? = null,
) {

  fun getDownloadProgress(modelId: String): DownloadProgress? {
    return activeDownloads[modelId]
  }

  fun isDownloading(modelId: String): Boolean {
    val progress = activeDownloads[modelId] ?: return false
    return progress.state is DownloadState.Downloading
  }

  fun isDownloaded(modelId: String): Boolean {
    return downloadedModels.any { it.modelId == modelId }
  }

  fun isActive(modelId: String): Boolean {
    return activeModelId == modelId
  }

  fun isModelCardExpanded(modelId: String): Boolean {
    return modelId in expandedModelCards
  }

  fun getDownloadedModel(modelId: String): DownloadedModel? {
    return downloadedModels.find { it.modelId == modelId }
  }

  val availableStorageFormatted: String
    get() = when {
      availableStorageBytes >= 1_000_000_000 ->
        "%.1fGB".format(availableStorageBytes / 1_000_000_000.0)

      availableStorageBytes >= 1_000_000 ->
        "%.1fMB".format(availableStorageBytes / 1_000_000.0)

      else ->
        "%.1fKB".format(availableStorageBytes / 1_000.0)
    }

  /**
   * Check if there are any active downloads.
   */
  val hasActiveDownloads: Boolean
    get() = activeDownloads.values.any {
      it.state is DownloadState.Downloading || it.state is DownloadState.Paused
    }

  val filteredModels: List<ModelInfo>
    get() {
      var result = availableModels

      if (searchQuery.isNotBlank()) {
        val query = searchQuery.lowercase()
        result = result.filter { model ->
          model.name.lowercase().contains(query) ||
            model.description.lowercase().contains(query)
        }
      }

      modelTypeFilter?.let { isVlm ->
        result = result.filter { it.isVisionLanguageModel == isVlm }
      }

      ramFilter?.let { maxRam ->
        result = result.filter { model ->
          model.quantizations.any { it.minRamMb <= maxRam }
        }
      }

      result = when (sortOption) {
        SortOption.NAME -> result.sortedBy { it.name.lowercase() }
        SortOption.SIZE -> result.sortedBy { model ->
          model.quantizations.minOfOrNull { it.mainModelSize } ?: Long.MAX_VALUE
        }

        SortOption.POPULARITY -> result.sortedByDescending { it.popularity }
      }

      return result
    }
}

data class ImportUiState(

  val isImporting: Boolean = false,

  @param:StringRes val stageRes: Int? = null,

  val progress: Int = 0,

  val requiresMmproj: Boolean = false,

  val modelPath: String? = null,

  val error: String? = null,
)
