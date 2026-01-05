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
package me.fleey.futon.domain.automation.models

import kotlinx.serialization.Serializable
import me.fleey.futon.data.ai.models.ActionParameters
import me.fleey.futon.data.ai.models.ActionType

@Serializable
sealed interface ActionResult {
  @Serializable
  data object Success : ActionResult

  @Serializable
  data class Failure(val reason: String) : ActionResult
}

/**
 * Records details of a single executed action during automation.
 * Used for displaying action history and debugging.
 *
 * @property step The step number in the automation sequence
 * @property action The type of action executed (tap, swipe, input, etc.)
 * @property parameters The parameters used for the action (coordinates, text, etc.)
 * @property reasoning The AI's reasoning for choosing this action
 * @property result Whether the action succeeded or failed
 * @property durationMs How long the action took to execute in milliseconds
 * @property timestamp When the action was executed (epoch milliseconds)
 */
@Serializable
data class ActionLogEntry(
  val step: Int,
  val action: ActionType,
  val parameters: ActionParameters? = null,
  val reasoning: String? = null,
  val result: ActionResult,
  val durationMs: Long,
  val timestamp: Long = System.currentTimeMillis(),
)
