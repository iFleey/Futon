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
package me.fleey.futon.data.ai.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.data.ai.models.ApiProtocol

/**
 * Represents an AI inference source for routing decisions.
 */
@Serializable
sealed class InferenceSource {
  abstract val id: String
  abstract val displayName: String

  @Serializable
  @SerialName("cloud")
  data class CloudProvider(
    val providerId: String,
    val providerName: String,
    val protocol: ApiProtocol,
    val modelId: String,
  ) : InferenceSource() {
    override val id: String get() = "cloud_${providerId}_$modelId"
    override val displayName: String get() = "$providerName / $modelId"
  }

  @Serializable
  @SerialName("local")
  data object LocalModel : InferenceSource() {
    override val id: String = "local_model"
    override val displayName: String = "Local Model"
  }
}
