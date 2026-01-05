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
package me.fleey.futon.data.history.models

import kotlinx.serialization.Serializable
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.domain.automation.models.ActionLogEntry
import java.util.UUID

/**
 * Complete execution log for a single automation task run.
 * Contains all details needed for debugging and reviewing past executions.
 *
 * @property id Unique identifier for this execution log
 * @property taskDescription The user's original task description
 * @property startTimeMs Timestamp when the task started (epoch milliseconds)
 * @property endTimeMs Timestamp when the task ended (epoch milliseconds)
 * @property totalDurationMs Total duration of the task in milliseconds
 * @property result Final result of the automation (SUCCESS, FAILURE, CANCELLED, TIMEOUT)
 * @property stepCount Total number of steps executed
 * @property actions List of all executed actions with details
 * @property aiResponses List of all AI responses received during execution
 * @property errors List of all errors encountered during execution
 */
@Serializable
data class ExecutionLog(
  val id: String = UUID.randomUUID().toString(),
  val taskDescription: String,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val totalDurationMs: Long,
  val result: AutomationResultType,
  val stepCount: Int,
  val actions: List<ActionLogEntry>,
  val aiResponses: List<AIResponseLog>,
  val errors: List<ErrorLogEntry>,
)

/**
 * Records an AI response received during automation.
 *
 * @property step The step number when this response was received
 * @property action The action type returned by the AI
 * @property reasoning The AI's reasoning for the action
 * @property responseTimeMs How long the AI took to respond in milliseconds
 * @property timestamp When the response was received (epoch milliseconds)
 */
@Serializable
data class AIResponseLog(
  val step: Int,
  val action: ActionType,
  val reasoning: String? = null,
  val responseTimeMs: Long,
  val timestamp: Long,
)

@Serializable
data class ErrorLogEntry(
  val step: Int,
  val errorType: ErrorType,
  val message: String,
  val isRetryable: Boolean,
  val timestamp: Long,
)

/**
 * Classification of errors that can occur during automation.
 */
@Serializable
enum class ErrorType {
  NETWORK_ERROR,

  TIMEOUT_ERROR,

  /** API returned an error response */
  API_ERROR,

  INVALID_RESPONSE,

  ACTION_FAILED,

  UNKNOWN
}
