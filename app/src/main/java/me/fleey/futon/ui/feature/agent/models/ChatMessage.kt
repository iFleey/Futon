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
package me.fleey.futon.ui.feature.agent.models

import java.util.UUID

sealed interface ChatMessage {
  val id: String
  val timestamp: Long

  data class UserTask(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val taskDescription: String,
  ) : ChatMessage

  data class AIResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val type: AIResponseType,
    val content: String,
    val metadata: AIResponseMetadata? = null,
  ) : ChatMessage

  data class SystemMessage(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val type: SystemMessageType,
    val content: String,
  ) : ChatMessage
}

enum class AIResponseType {
  THINKING,
  REASONING,
  ACTION,
  STEP_PROGRESS,
  RESULT_SUCCESS,
  RESULT_FAILURE,
  ERROR
}

enum class SystemMessageType {
  WELCOME,
  SETTINGS_REQUIRED,
  CLEARED
}

data class AIResponseMetadata(
  val actionType: String? = null,
  val actionParams: String? = null,
  val isSuccess: Boolean? = null,
  val durationMs: Long? = null,
  val stepNumber: Int? = null,
  val maxSteps: Int? = null,
  val suggestions: List<String>? = null,
  val reasoning: String? = null,
)
