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

import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.SortOption

/**
 * Events that can be triggered from the Local Model Settings UI.
 */
sealed interface LocalModelUiEvent {


  data class DownloadModel(
    val modelId: String,
    val quantization: QuantizationType,
  ) : LocalModelUiEvent

  data class PauseDownload(val modelId: String) : LocalModelUiEvent

  data class ResumeDownload(val modelId: String) : LocalModelUiEvent

  data class CancelDownload(val modelId: String) : LocalModelUiEvent

  data class EnableModel(val modelId: String) : LocalModelUiEvent

  data object DisableModel : LocalModelUiEvent

  data class RequestDeleteModel(val modelId: String) : LocalModelUiEvent

  data object ConfirmDeleteModel : LocalModelUiEvent

  data object CancelDeleteModel : LocalModelUiEvent

  data object OpenImportPicker : LocalModelUiEvent

  data object CloseImportPicker : LocalModelUiEvent

  /**
   * Import a model from the selected file path.
   */
  data class ImportModel(
    val modelPath: String,
    val mmprojPath: String? = null,
  ) : LocalModelUiEvent

  data object CancelImport : LocalModelUiEvent


  data class SetDownloadSource(val source: DownloadSource) : LocalModelUiEvent

  data class UpdateInferenceConfig(val config: InferenceConfig) : LocalModelUiEvent

  data class ApplyInferencePreset(val preset: InferencePreset) : LocalModelUiEvent

  data class SetContextLength(val length: Int) : LocalModelUiEvent

  data class SetThreadCount(val count: Int) : LocalModelUiEvent

  data class SetUseNnapi(val enabled: Boolean) : LocalModelUiEvent


  data object DismissSuccess : LocalModelUiEvent

  data object DismissError : LocalModelUiEvent

  data object Refresh : LocalModelUiEvent

  data class SetSearchQuery(val query: String) : LocalModelUiEvent

  data class SetModelTypeFilter(val isVlm: Boolean?) : LocalModelUiEvent

  data class SetRamFilter(val maxRamMb: Int?) : LocalModelUiEvent

  data class SetSortOption(val sortOption: SortOption) : LocalModelUiEvent

  data object ClearFilters : LocalModelUiEvent

  data object ResetFilters : LocalModelUiEvent

  data class SetSearchExpanded(val expanded: Boolean) : LocalModelUiEvent

  data class SetDeviceCapabilitiesCollapsed(val collapsed: Boolean) : LocalModelUiEvent

  data class ToggleModelCardExpanded(val modelId: String) : LocalModelUiEvent

  data object RefreshCatalog : LocalModelUiEvent

  data object DismissCatalogError : LocalModelUiEvent
}

enum class InferencePreset {
  FAST,

  QUALITY,

  DEFAULT
}
