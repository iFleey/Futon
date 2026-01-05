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
package me.fleey.futon.data.localmodel.registry

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.localmodel.models.ModelInfo

/**
 * Filter options for model search and filtering.
 */
data class ModelFilter(
  val isVisionLanguageModel: Boolean? = null,

  val maxRamMb: Int? = null,

  val sortBy: SortOption = SortOption.NAME,
)

/**
 * Sort options for model list.
 */
enum class SortOption {
  NAME,

  SIZE,

  POPULARITY
}

/**
 * Interface for the remote model registry that coordinates fetching models
 * from API, caching, and fallback mechanisms.
 */
interface RemoteModelRegistry {

  suspend fun refreshCatalog(): Result<List<ModelInfo>>

  /**
   * Search models by name or description.
   *
   * @param query Search query string
   * @return Flow emitting filtered list of models
   */
  fun searchModels(query: String): Flow<List<ModelInfo>>

  fun filterModels(filter: ModelFilter): Flow<List<ModelInfo>>

  fun getApiEndpoint(): String

  fun getModelInfo(modelId: String): ModelInfo?

  suspend fun setApiEndpoint(url: String): Result<Unit>
}
