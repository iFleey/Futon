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
package me.fleey.futon.data.ai.adapters

import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider

/**
 * Adapter interface for AI providers.
 * Each adapter handles a specific API protocol format.
 */
interface ProviderAdapter {
  val protocol: ApiProtocol

  /**
   * Send a chat completion request.
   */
  suspend fun sendRequest(
    provider: Provider,
    modelId: String,
    messages: List<Message>,
    maxTokens: Int = 4096,
    timeoutMs: Long = 120_000L,
  ): AdapterResponse

  /**
   * Fetch available models from the provider's API.
   * Returns empty list if the provider doesn't support model listing.
   */
  suspend fun fetchAvailableModels(provider: Provider): List<String> = emptyList()
}

/**
 * Response from adapter containing raw text and token usage.
 */
data class AdapterResponse(
  val content: String,
  val inputTokens: Long = 0,
  val outputTokens: Long = 0,
)
