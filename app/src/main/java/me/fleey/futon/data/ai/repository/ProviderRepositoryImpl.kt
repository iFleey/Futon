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
package me.fleey.futon.data.ai.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.models.BuiltInProviders
import me.fleey.futon.data.ai.models.ModelConfig
import me.fleey.futon.data.ai.models.Provider
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [ProviderRepository::class])
class ProviderRepositoryImpl(
  @Named("provider_config") private val dataStore: DataStore<Preferences>,
  private val json: Json,
) : ProviderRepository {

  companion object {
    private val KEY_PROVIDERS = stringPreferencesKey("providers")
    private val KEY_MODELS = stringPreferencesKey("models")
    private val KEY_INITIALIZED = stringPreferencesKey("initialized")
  }

  override fun getProvidersFlow(): Flow<List<Provider>> = dataStore.data.map { prefs ->
    prefs[KEY_PROVIDERS]?.let { decodeProviders(it) } ?: emptyList()
  }

  override suspend fun getProviders(): List<Provider> = getProvidersFlow().first()

  override suspend fun getProvider(id: String): Provider? =
    getProviders().find { it.id == id }

  override suspend fun saveProvider(provider: Provider) {
    dataStore.edit { prefs ->
      val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) }?.toMutableList()
        ?: mutableListOf()

      val index = providers.indexOfFirst { it.id == provider.id }
      val updatedProvider = provider.copy(updatedAt = System.currentTimeMillis())

      if (index >= 0) {
        providers[index] = updatedProvider
      } else {
        providers.add(updatedProvider.copy(sortOrder = providers.size))
      }

      prefs[KEY_PROVIDERS] = json.encodeToString(providers)
    }
  }

  override suspend fun deleteProvider(id: String) {
    dataStore.edit { prefs ->
      val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) }?.toMutableList()
        ?: return@edit
      providers.removeAll { it.id == id }
      prefs[KEY_PROVIDERS] = json.encodeToString(providers)

      val models = prefs[KEY_MODELS]?.let { decodeModels(it) }?.toMutableList()
        ?: return@edit
      models.removeAll { it.providerId == id }
      prefs[KEY_MODELS] = json.encodeToString(models)
    }
  }

  override suspend fun updateProviderOrder(providerIds: List<String>) {
    dataStore.edit { prefs ->
      val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) }?.toMutableList()
        ?: return@edit

      val reordered = providerIds.mapIndexedNotNull { index, id ->
        providers.find { it.id == id }?.copy(sortOrder = index)
      }

      val remaining = providers.filter { p -> providerIds.none { it == p.id } }
        .mapIndexed { index, p -> p.copy(sortOrder = providerIds.size + index) }

      prefs[KEY_PROVIDERS] = json.encodeToString(reordered + remaining)
    }
  }

  override fun getModelsFlow(providerId: String): Flow<List<ModelConfig>> = dataStore.data.map { prefs ->
    prefs[KEY_MODELS]?.let { decodeModels(it) }?.filter { it.providerId == providerId }
      ?: emptyList()
  }

  override suspend fun getModels(providerId: String): List<ModelConfig> =
    getModelsFlow(providerId).first()

  override suspend fun getModel(id: String): ModelConfig? {
    val allModels = dataStore.data.first()[KEY_MODELS]?.let { decodeModels(it) } ?: emptyList()
    return allModels.find { it.id == id }
  }

  override suspend fun saveModel(model: ModelConfig) {
    dataStore.edit { prefs ->
      val models = prefs[KEY_MODELS]?.let { decodeModels(it) }?.toMutableList()
        ?: mutableListOf()

      val index = models.indexOfFirst { it.id == model.id }
      if (index >= 0) {
        models[index] = model
      } else {
        models.add(model)
      }

      prefs[KEY_MODELS] = json.encodeToString(models)
    }
  }

  override suspend fun deleteModel(id: String) {
    dataStore.edit { prefs ->
      val models = prefs[KEY_MODELS]?.let { decodeModels(it) }?.toMutableList()
        ?: return@edit
      models.removeAll { it.id == id }
      prefs[KEY_MODELS] = json.encodeToString(models)
    }
  }

  override suspend fun setProviderSelectedModel(providerId: String, modelId: String) {
    dataStore.edit { prefs ->
      val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) }?.toMutableList()
        ?: return@edit

      val index = providers.indexOfFirst { it.id == providerId }
      if (index >= 0) {
        providers[index] = providers[index].copy(
          selectedModelId = modelId,
          updatedAt = System.currentTimeMillis(),
        )
        prefs[KEY_PROVIDERS] = json.encodeToString(providers)
      }
    }
  }

  override suspend fun getProviderWithSelectedModel(providerId: String): Pair<Provider, ModelConfig>? {
    val provider = getProvider(providerId) ?: return null
    val selectedModelId = provider.selectedModelId ?: return null
    val models = getModels(providerId)
    val model = models.find { it.modelId == selectedModelId } ?: return null
    return provider to model
  }

  override suspend fun getFirstEnabledProviderWithModel(): Pair<Provider, ModelConfig>? {
    val providers = getProviders()
      .filter { it.enabled && it.isConfigured() && it.selectedModelId != null }
      .sortedBy { it.sortOrder }

    for (provider in providers) {
      val models = getModels(provider.id)
      val model = models.find { it.modelId == provider.selectedModelId }
      if (model != null) {
        return provider to model
      }
    }
    return null
  }

  override suspend fun initializeDefaults() {
    val prefs = dataStore.data.first()
    if (prefs[KEY_INITIALIZED] != null) return

    dataStore.edit { editPrefs ->
      val defaultProviders = mutableListOf<Provider>()
      val defaultModels = mutableListOf<ModelConfig>()

      BuiltInProviders.all.forEachIndexed { index, template ->
        val provider = template.toProvider().copy(sortOrder = index)
        defaultProviders.add(provider)
        defaultModels.addAll(template.toModels(provider.id))
      }

      editPrefs[KEY_PROVIDERS] = json.encodeToString(defaultProviders)
      editPrefs[KEY_MODELS] = json.encodeToString(defaultModels)
      editPrefs[KEY_INITIALIZED] = "true"
    }
  }

  private fun decodeProviders(jsonStr: String): List<Provider> = runCatching {
    json.decodeFromString<List<Provider>>(jsonStr)
  }.getOrDefault(emptyList())

  private fun decodeModels(jsonStr: String): List<ModelConfig> = runCatching {
    json.decodeFromString<List<ModelConfig>>(jsonStr)
  }.getOrDefault(emptyList())
}
