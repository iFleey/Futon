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

sealed interface AutomationState {
  data object Idle : AutomationState

  /**
   * Automation is currently running.
   *
   * @property taskDescription The user's task description
   * @property currentStep Current step number in the automation
   * @property maxSteps Maximum allowed steps
   * @property phase Current execution phase (screenshot, AI analysis, action, etc.)
   * @property lastAction String description of the last action (legacy, for backward compatibility)
   * @property actionHistory List of all executed actions with details
   * @property currentReasoning AI's reasoning for the current/last action
   * @property startTimeMs Timestamp when the task started (epoch milliseconds)
   * @property stepStartTimeMs Timestamp when the current step started (epoch milliseconds)
   * @property retryAttempt Current retry attempt number (0 = first attempt)
   * @property retryReason Reason for the current retry, if retrying
   */
  data class Running(
    val taskDescription: String,
    val currentStep: Int,
    val maxSteps: Int,
    val phase: ExecutionPhase = ExecutionPhase.CAPTURING_SCREENSHOT,
    val lastAction: String? = null,
    val actionHistory: List<ActionLogEntry> = emptyList(),
    val currentReasoning: String? = null,
    val startTimeMs: Long = System.currentTimeMillis(),
    val stepStartTimeMs: Long = System.currentTimeMillis(),
    val retryAttempt: Int = 0,
    val retryReason: String? = null,
  ) : AutomationState

  data class Completed(val result: AutomationResult) : AutomationState
}

/**
 * Represents the final result of an automation task.
 */
sealed interface AutomationResult {
  data object Success : AutomationResult
  data class Failure(val reason: String) : AutomationResult
  data object Cancelled : AutomationResult
  data object Timeout : AutomationResult
}
