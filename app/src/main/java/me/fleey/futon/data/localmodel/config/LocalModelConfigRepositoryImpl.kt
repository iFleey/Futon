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
package me.fleey.futon.data.localmodel.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.LocalModelConfig

/**
 * Implementation of LocalModelConfigRepository using DataStore for persistence.
 *
 * Configuration is stored in DataStore preferences. No sensitive data is stored
 * in this repository, so encryption is not required.
 */
import org.koin.core.annotation.Single

@Single(binds = [LocalModelConfigRepository::class])
class LocalModelConfigRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
) : LocalModelConfigRepository {

  override fun getConfigFlow(): Flow<LocalModelConfig> = dataStore.data.map { prefs ->
    buildConfigFromPreferences(prefs)
  }

  override suspend fun getConfig(): LocalModelConfig = getConfigFlow().first()

  override suspend fun updateConfig(config: LocalModelConfig) {
    dataStore.edit { prefs ->
      if (config.activeModelId != null) {
        prefs[ACTIVE_MODEL_ID_KEY] = config.activeModelId
      } else {
        prefs.remove(ACTIVE_MODEL_ID_KEY)
      }

      prefs[DOWNLOAD_SOURCE_KEY] = config.downloadSource.name

      // Inference config
      prefs[CONTEXT_LENGTH_KEY] = config.inferenceConfig.contextLength
      prefs[NUM_THREADS_KEY] = config.inferenceConfig.numThreads
      prefs[USE_NNAPI_KEY] = config.inferenceConfig.useNnapi
      prefs[BACKGROUND_UNLOAD_TIMEOUT_KEY] = config.inferenceConfig.backgroundUnloadTimeoutMs
      prefs[INFERENCE_TIMEOUT_KEY] = config.inferenceConfig.inferenceTimeoutMs
      prefs[MAX_IMAGE_DIMENSION_KEY] = config.inferenceConfig.maxImageDimension
    }
  }

  override suspend fun getDownloadSource(): DownloadSource {
    return getConfig().downloadSource
  }

  override suspend fun setDownloadSource(source: DownloadSource) {
    dataStore.edit { prefs ->
      prefs[DOWNLOAD_SOURCE_KEY] = source.name
    }
  }

  override suspend fun getInferenceConfig(): InferenceConfig {
    return getConfig().inferenceConfig
  }

  override suspend fun updateInferenceConfig(config: InferenceConfig) {
    dataStore.edit { prefs ->
      prefs[CONTEXT_LENGTH_KEY] = config.contextLength
      prefs[NUM_THREADS_KEY] = config.numThreads
      prefs[USE_NNAPI_KEY] = config.useNnapi
      prefs[BACKGROUND_UNLOAD_TIMEOUT_KEY] = config.backgroundUnloadTimeoutMs
      prefs[INFERENCE_TIMEOUT_KEY] = config.inferenceTimeoutMs
      prefs[MAX_IMAGE_DIMENSION_KEY] = config.maxImageDimension
    }
  }

  override suspend fun getActiveModelId(): String? {
    return getConfig().activeModelId
  }

  override suspend fun setActiveModelId(modelId: String?) {
    dataStore.edit { prefs ->
      if (modelId != null) {
        prefs[ACTIVE_MODEL_ID_KEY] = modelId
      } else {
        prefs.remove(ACTIVE_MODEL_ID_KEY)
      }
    }
  }

  override suspend fun clearConfig() {
    dataStore.edit { prefs ->
      prefs.remove(ACTIVE_MODEL_ID_KEY)
      prefs.remove(DOWNLOAD_SOURCE_KEY)
      prefs.remove(CONTEXT_LENGTH_KEY)
      prefs.remove(NUM_THREADS_KEY)
      prefs.remove(USE_NNAPI_KEY)
      prefs.remove(BACKGROUND_UNLOAD_TIMEOUT_KEY)
      prefs.remove(INFERENCE_TIMEOUT_KEY)
      prefs.remove(MAX_IMAGE_DIMENSION_KEY)
    }
  }

  /**
   * Build LocalModelConfig from DataStore preferences.
   */
  private fun buildConfigFromPreferences(prefs: Preferences): LocalModelConfig {
    val activeModelId = prefs[ACTIVE_MODEL_ID_KEY]

    val downloadSource = prefs[DOWNLOAD_SOURCE_KEY]?.let { sourceName ->
      try {
        DownloadSource.valueOf(sourceName)
      } catch (e: IllegalArgumentException) {
        DownloadSource.HUGGING_FACE
      }
    } ?: DownloadSource.HUGGING_FACE

    val inferenceConfig = InferenceConfig(
      contextLength = prefs[CONTEXT_LENGTH_KEY] ?: InferenceConfig.DEFAULT_CONTEXT_LENGTH,
      numThreads = prefs[NUM_THREADS_KEY] ?: InferenceConfig.DEFAULT_NUM_THREADS,
      useNnapi = prefs[USE_NNAPI_KEY] ?: false,
      backgroundUnloadTimeoutMs = prefs[BACKGROUND_UNLOAD_TIMEOUT_KEY]
        ?: InferenceConfig.DEFAULT_BACKGROUND_TIMEOUT_MS,
      inferenceTimeoutMs = prefs[INFERENCE_TIMEOUT_KEY]
        ?: InferenceConfig.DEFAULT_INFERENCE_TIMEOUT_MS,
      maxImageDimension = prefs[MAX_IMAGE_DIMENSION_KEY]
        ?: InferenceConfig.DEFAULT_MAX_IMAGE_DIMENSION,
    )

    return LocalModelConfig(
      activeModelId = activeModelId,
      downloadSource = downloadSource,
      inferenceConfig = inferenceConfig,
    )
  }

  companion object {
    private val ACTIVE_MODEL_ID_KEY = stringPreferencesKey("local_model_active_id")
    private val DOWNLOAD_SOURCE_KEY = stringPreferencesKey("local_model_download_source")
    private val CONTEXT_LENGTH_KEY = intPreferencesKey("local_model_context_length")
    private val NUM_THREADS_KEY = intPreferencesKey("local_model_num_threads")
    private val USE_NNAPI_KEY = booleanPreferencesKey("local_model_use_nnapi")
    private val BACKGROUND_UNLOAD_TIMEOUT_KEY = longPreferencesKey("local_model_background_timeout")
    private val INFERENCE_TIMEOUT_KEY = longPreferencesKey("local_model_inference_timeout")
    private val MAX_IMAGE_DIMENSION_KEY = intPreferencesKey("local_model_max_image_dimension")
  }
}
