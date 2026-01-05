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
package me.fleey.futon.data.ai

import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.Message

interface AIClient {
  /**
   * Analyze screen content with optional screenshot and/or UI structure context.
   *
   * At least one of screenshot or uiContext must be provided.
   *
   * @param screenshot Base64-encoded screenshot image (optional for UI_TREE_ONLY mode)
   * @param taskDescription User's task description
   * @param uiContext Optional structured UI tree context for hybrid/UI-tree perception
   * @param conversationHistory Previous conversation messages
   * @param appContext Optional context about installed apps to help AI understand app names
   * @return AI response with action to perform
   * @throws AIClientException if neither screenshot nor uiContext is provided
   */
  suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String? = null,
    conversationHistory: List<Message> = emptyList(),
    appContext: String? = null,
  ): AIResponse
}
