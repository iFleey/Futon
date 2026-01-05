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

import android.hardware.HardwareBuffer
import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.ai.AIClient
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.Message

/**
 * Routes AI inference requests to multiple sources based on priority and availability.
 */
interface InferenceRouter : AIClient {

  /**
   * Last successfully used inference source.
   */
  val lastUsedSource: StateFlow<InferenceSource?>

  /**
   * Current source availability states.
   */
  val sourceAvailability: StateFlow<Map<InferenceSource, SourceAvailability>>

  /**
   * Current routing configuration.
   */
  val routingConfig: StateFlow<RoutingConfig>

  /**
   * Analyze screenshot with optional source override.
   *
   * @param screenshot Base64-encoded screenshot
   * @param taskDescription Task description
   * @param uiContext Optional UI context
   * @param conversationHistory Conversation history
   * @param appContext Optional app context
   * @param sourceOverride Force use of specific source (bypasses routing)
   * @return AI response
   */
  suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String? = null,
    conversationHistory: List<Message> = emptyList(),
    appContext: String? = null,
    sourceOverride: InferenceSource? = null,
  ): AIResponse

  suspend fun analyzeBuffer(
    buffer: HardwareBuffer,
    taskDescription: String,
    uiContext: String? = null,
    conversationHistory: List<Message> = emptyList(),
    appContext: String? = null,
    sourceOverride: InferenceSource? = null,
  ): AIResponse

  suspend fun updateConfig(config: RoutingConfig)

  suspend fun updatePriority(sources: List<InferenceSource>)

  suspend fun updateStrategy(strategy: RoutingStrategy)

  /**
   * Enable or disable a specific source.
   */
  suspend fun setSourceEnabled(source: InferenceSource, enabled: Boolean)

  /**
   * Preload local model if it's first in priority list.
   */
  suspend fun warmupLocalModel()

  suspend fun refreshAvailability()
}

/**
 * Configuration for inference routing.
 */
data class RoutingConfig(
  val priority: List<InferenceSource> = listOf(InferenceSource.LocalModel),

  val strategy: RoutingStrategy = RoutingStrategy.DEFAULT,

  val enabledSources: Map<InferenceSource, Boolean> = emptyMap(),
) {
  fun getEnabledSourcesInOrder(): List<InferenceSource> =
    priority.filter { enabledSources[it] != false }
}
