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
import me.fleey.futon.data.ai.models.InferenceStatsAggregate
import me.fleey.futon.data.ai.models.ModelInferenceStats
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [InferenceStatsRepository::class])
class InferenceStatsRepositoryImpl(
  @Named("inference_stats") private val dataStore: DataStore<Preferences>,
  private val json: Json,
) : InferenceStatsRepository {

  companion object {
    private val KEY_STATS = stringPreferencesKey("inference_stats")
  }

  override fun getStatsFlow(): Flow<InferenceStatsAggregate> = dataStore.data.map { prefs ->
    prefs[KEY_STATS]?.let { decodeStats(it) } ?: InferenceStatsAggregate()
  }

  override suspend fun getStats(): InferenceStatsAggregate = getStatsFlow().first()

  override suspend fun recordInference(
    modelId: String,
    providerId: String,
    inputTokens: Long,
    outputTokens: Long,
    cost: Double,
    latencyMs: Long,
  ) {
    dataStore.edit { prefs ->
      val stats = prefs[KEY_STATS]?.let { decodeStats(it) } ?: InferenceStatsAggregate()

      val existingModelStats = stats.modelStats[modelId] ?: ModelInferenceStats(
        modelId = modelId,
        providerId = providerId,
      )

      val updatedModelStats = existingModelStats.addRequest(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cost = cost,
        latencyMs = latencyMs,
      )

      val updatedStats = stats.updateModelStats(updatedModelStats)
      prefs[KEY_STATS] = json.encodeToString(updatedStats)
    }
  }

  override suspend fun getModelStats(modelId: String): ModelInferenceStats? =
    getStats().getStatsForModel(modelId)

  override suspend fun resetStats() {
    dataStore.edit { prefs ->
      prefs[KEY_STATS] = json.encodeToString(InferenceStatsAggregate())
    }
  }

  private fun decodeStats(jsonStr: String): InferenceStatsAggregate = runCatching {
    json.decodeFromString<InferenceStatsAggregate>(jsonStr)
  }.getOrDefault(InferenceStatsAggregate())
}
