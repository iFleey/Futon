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
import java.util.UUID

/**
 * Model configuration with pricing information for cost tracking.
 * Each model belongs to a provider.
 */
@Serializable
data class ModelConfig(
  val id: String = UUID.randomUUID().toString(),
  @SerialName("provider_id")
  val providerId: String,
  @SerialName("model_id")
  val modelId: String,
  @SerialName("display_name")
  val displayName: String = "",
  @SerialName("input_price")
  val inputPrice: Double = 0.0,
  @SerialName("output_price")
  val outputPrice: Double = 0.0,
  @SerialName("context_window")
  val contextWindow: Int = 128000,
  @SerialName("supports_vision")
  val supportsVision: Boolean = true,
  val enabled: Boolean = true,
) {
  val getDisplayName: String get() = displayName.ifBlank { modelId }

  fun calculateCost(inputTokens: Long, outputTokens: Long): Double {
    if (inputPrice == 0.0 && outputPrice == 0.0) return 0.0
    return (inputTokens * inputPrice + outputTokens * outputPrice) / 1_000_000.0
  }

  fun hasPricing(): Boolean = inputPrice > 0.0 || outputPrice > 0.0
}
