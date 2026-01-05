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

/**
 * Implementation of ModelRegistry that delegates to RemoteModelRegistry for
 * available models and tracks downloaded models using DataStore.
 * */
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.ModelStatus
import org.koin.core.annotation.Single

@Single(binds = [ModelRegistry::class])
class ModelRegistryImpl(
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
  private val remoteModelRegistry: RemoteModelRegistry,
) : ModelRegistry {

  companion object {
    private val DOWNLOADED_MODELS_KEY = stringPreferencesKey("downloaded_models")
    private val ACTIVE_MODEL_KEY = stringPreferencesKey("active_model_id")
  }

  // Track models currently being downloaded (in-memory state)
  private val downloadingModels = MutableStateFlow<Set<String>>(emptySet())

  private var cachedActiveModelId: String? = null

  override suspend fun refreshCatalog(): Result<List<ModelInfo>> =
    remoteModelRegistry.refreshCatalog()

  override fun getModelInfo(modelId: String): ModelInfo? {
    return remoteModelRegistry.getModelInfo(modelId)
  }


  override suspend fun registerDownloadedModel(model: DownloadedModel): Result<Unit> {
    return try {
      dataStore.edit { preferences ->
        val currentModels = getDownloadedModelsFromPrefs(preferences)
        val updatedModels = currentModels.filter { it.modelId != model.modelId } + model
        preferences[DOWNLOADED_MODELS_KEY] = json.encodeToString(updatedModels)
      }
      clearModelDownloading(model.modelId)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override suspend fun unregisterModel(modelId: String): Result<Unit> {
    return try {
      dataStore.edit { preferences ->
        val currentModels = getDownloadedModelsFromPrefs(preferences)
        val updatedModels = currentModels.filter { it.modelId != modelId }
        preferences[DOWNLOADED_MODELS_KEY] = json.encodeToString(updatedModels)

        // Clear active model if it was the one being unregistered
        if (preferences[ACTIVE_MODEL_KEY] == modelId) {
          preferences.remove(ACTIVE_MODEL_KEY)
          cachedActiveModelId = null
        }
      }
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override fun getDownloadedModels(): Flow<List<DownloadedModel>> {
    return dataStore.data.map { preferences ->
      getDownloadedModelsFromPrefs(preferences)
    }
  }

  override fun getModelStatus(modelId: String): ModelStatus {
    if (cachedActiveModelId == modelId) {
      return ModelStatus.ACTIVE
    }

    if (downloadingModels.value.contains(modelId)) {
      return ModelStatus.DOWNLOADING
    }

    // This is a synchronous check - for async status, use getDownloadedModels() flow
    // Default to AVAILABLE if we can't determine status synchronously
    return ModelStatus.AVAILABLE
  }

  override suspend fun setActiveModel(modelId: String?): Result<Unit> {
    return try {
      dataStore.edit { preferences ->
        if (modelId != null) {
          preferences[ACTIVE_MODEL_KEY] = modelId
        } else {
          preferences.remove(ACTIVE_MODEL_KEY)
        }
      }
      cachedActiveModelId = modelId
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override fun getActiveModelId(): String? {
    return cachedActiveModelId
  }

  override suspend fun setModelDownloading(modelId: String) {
    downloadingModels.value += modelId
  }

  override suspend fun clearModelDownloading(modelId: String) {
    downloadingModels.value -= modelId
  }

  suspend fun initialize() {
    try {
      val preferences = dataStore.data.first()
      cachedActiveModelId = preferences[ACTIVE_MODEL_KEY]
    } catch (e: Exception) {
      // Ignore initialization errors - will use default values
    }
  }

  /**
   * Helper function to parse downloaded models from preferences.
   */
  private fun getDownloadedModelsFromPrefs(preferences: Preferences): List<DownloadedModel> {
    val modelsJson = preferences[DOWNLOADED_MODELS_KEY] ?: return emptyList()
    return try {
      json.decodeFromString<List<DownloadedModel>>(modelsJson)
    } catch (e: Exception) {
      emptyList()
    }
  }
}
