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

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.ai.models.ModelConfig
import me.fleey.futon.data.ai.models.Provider

/**
 * Repository for managing AI providers and models.
 */
interface ProviderRepository {

  fun getProvidersFlow(): Flow<List<Provider>>

  suspend fun getProviders(): List<Provider>

  suspend fun getProvider(id: String): Provider?

  suspend fun saveProvider(provider: Provider)

  suspend fun deleteProvider(id: String)

  suspend fun updateProviderOrder(providerIds: List<String>)

  fun getModelsFlow(providerId: String): Flow<List<ModelConfig>>

  suspend fun getModels(providerId: String): List<ModelConfig>

  suspend fun getModel(id: String): ModelConfig?

  suspend fun saveModel(model: ModelConfig)

  suspend fun deleteModel(id: String)

  suspend fun setProviderSelectedModel(providerId: String, modelId: String)

  suspend fun getProviderWithSelectedModel(providerId: String): Pair<Provider, ModelConfig>?

  /**
   * Get the first enabled and configured provider with its selected model.
   * Used by AIClient when no specific provider is requested.
   */
  suspend fun getFirstEnabledProviderWithModel(): Pair<Provider, ModelConfig>?

  /**
   * Initialize with default providers if empty.
   */
  suspend fun initializeDefaults()
}
