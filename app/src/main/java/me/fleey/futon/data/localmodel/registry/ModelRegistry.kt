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
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.ModelStatus

/**
 * Registry for managing available and downloaded local AI models.
 *
 * Maintains the catalog of available models and tracks which models
 * have been downloaded to the device.
 * */
interface ModelRegistry {

  /**
   * Force refresh the catalog from the network.
   *
   * @return Result containing list of models on success, or error on failure
   */
  suspend fun refreshCatalog(): Result<List<ModelInfo>>

  /**
   * Get detailed information about a specific model.
   *
   * @param modelId Unique identifier of the model
   * @return Model information or null if not found
   */
  fun getModelInfo(modelId: String): ModelInfo?

  /**
   * Register a model that has been downloaded to the device.
   *
   * @param model Downloaded model information
   * @return Result indicating success or failure
   */
  suspend fun registerDownloadedModel(model: DownloadedModel): Result<Unit>

  /**
   * Unregister a model (typically when deleted).
   *
   * @param modelId Unique identifier of the model to unregister
   * @return Result indicating success or failure

   */
  suspend fun unregisterModel(modelId: String): Result<Unit>

  /**
   * Get a flow of all downloaded models.
   *
   * @return Flow emitting list of downloaded models
   */
  fun getDownloadedModels(): Flow<List<DownloadedModel>>

  /**
   * Get the current status of a model.
   *
   * @param modelId Unique identifier of the model
   * @return Current status of the model
   */
  fun getModelStatus(modelId: String): ModelStatus

  /**
   * Set a model as currently active.
   *
   * @param modelId Unique identifier of the model to activate
   * @return Result indicating success or failure
   */
  suspend fun setActiveModel(modelId: String?): Result<Unit>

  /**
   * Get the currently active model ID.
   *
   * @return Active model ID or null if none active

   */
  fun getActiveModelId(): String?

  /**
   * Mark a model as currently downloading.
   *
   * @param modelId Unique identifier of the model
   */
  suspend fun setModelDownloading(modelId: String)

  /**
   * Clear the downloading status for a model.
   *
   * @param modelId Unique identifier of the model
   */
  suspend fun clearModelDownloading(modelId: String)
}
