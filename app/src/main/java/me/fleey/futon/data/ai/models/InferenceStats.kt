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
package me.fleey.futon.data.ai.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Statistics for a single model's inference usage.
 */
@Serializable
data class ModelInferenceStats(
  @SerialName("model_id")
  val modelId: String,
  @SerialName("provider_id")
  val providerId: String,
  @SerialName("input_tokens")
  val inputTokens: Long = 0,
  @SerialName("output_tokens")
  val outputTokens: Long = 0,
  @SerialName("total_cost")
  val totalCost: Double = 0.0,
  @SerialName("request_count")
  val requestCount: Int = 0,
  @SerialName("total_latency_ms")
  val totalLatencyMs: Long = 0,
  @SerialName("last_used_at")
  val lastUsedAt: Long = 0,
) {
  val avgLatencyMs: Long
    get() = if (requestCount > 0) totalLatencyMs / requestCount else 0

  val totalTokens: Long
    get() = inputTokens + outputTokens

  fun addRequest(
    inputTokens: Long,
    outputTokens: Long,
    cost: Double,
    latencyMs: Long,
  ): ModelInferenceStats = copy(
    inputTokens = this.inputTokens + inputTokens,
    outputTokens = this.outputTokens + outputTokens,
    totalCost = this.totalCost + cost,
    requestCount = this.requestCount + 1,
    totalLatencyMs = this.totalLatencyMs + latencyMs,
    lastUsedAt = System.currentTimeMillis(),
  )
}

/**
 * Aggregated inference statistics across all models.
 */
@Serializable
data class InferenceStatsAggregate(
  @SerialName("model_stats")
  val modelStats: Map<String, ModelInferenceStats> = emptyMap(),
  @SerialName("updated_at")
  val updatedAt: Long = System.currentTimeMillis(),
) {
  val totalInputTokens: Long
    get() = modelStats.values.sumOf { it.inputTokens }

  val totalOutputTokens: Long
    get() = modelStats.values.sumOf { it.outputTokens }

  val totalCost: Double
    get() = modelStats.values.sumOf { it.totalCost }

  val totalRequests: Int
    get() = modelStats.values.sumOf { it.requestCount }

  fun getStatsForModel(modelId: String): ModelInferenceStats? = modelStats[modelId]

  fun getStatsForProvider(providerId: String): List<ModelInferenceStats> =
    modelStats.values.filter { it.providerId == providerId }

  fun updateModelStats(stats: ModelInferenceStats): InferenceStatsAggregate = copy(
    modelStats = modelStats + (stats.modelId to stats),
    updatedAt = System.currentTimeMillis(),
  )
}
