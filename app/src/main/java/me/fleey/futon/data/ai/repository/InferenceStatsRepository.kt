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
import me.fleey.futon.data.ai.models.InferenceStatsAggregate
import me.fleey.futon.data.ai.models.ModelInferenceStats

/**
 * Repository for tracking inference statistics.
 */
interface InferenceStatsRepository {

  fun getStatsFlow(): Flow<InferenceStatsAggregate>

  suspend fun getStats(): InferenceStatsAggregate

  /**
   * Record a completed inference request.
   */
  suspend fun recordInference(
    modelId: String,
    providerId: String,
    inputTokens: Long,
    outputTokens: Long,
    cost: Double,
    latencyMs: Long,
  )

  suspend fun getModelStats(modelId: String): ModelInferenceStats?

  suspend fun resetStats()
}
