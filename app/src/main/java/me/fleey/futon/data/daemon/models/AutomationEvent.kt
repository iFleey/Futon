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
package me.fleey.futon.data.daemon.models

sealed interface AutomationEvent {
  val timestamp: Long

  data class Started(
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
  ) : AutomationEvent

  data class Progress(
    override val timestamp: Long = System.currentTimeMillis(),
    val step: Int,
    val totalSteps: Int?,
    val mode: AutomationMode,
    val description: String? = null,
  ) : AutomationEvent

  data class HotPathMatched(
    override val timestamp: Long = System.currentTimeMillis(),
    val ruleId: String,
    val matchedElement: String?,
    val confidence: Float,
  ) : AutomationEvent

  data class AiFallbackTriggered(
    override val timestamp: Long = System.currentTimeMillis(),
    val reason: String,
    val consecutiveNoMatch: Int,
  ) : AutomationEvent

  data class ActionExecuted(
    override val timestamp: Long = System.currentTimeMillis(),
    val actionType: ActionType,
    val success: Boolean,
    val details: String? = null,
  ) : AutomationEvent

  data class LoopDetected(
    override val timestamp: Long = System.currentTimeMillis(),
    val stateHash: Long,
    val consecutiveCount: Int,
  ) : AutomationEvent

  data class Completed(
    override val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val message: String?,
    val totalSteps: Int,
    val hotPathHits: Int,
    val aiFallbacks: Int,
  ) : AutomationEvent

  data class Failed(
    override val timestamp: Long = System.currentTimeMillis(),
    val error: DaemonError,
    val stepAtFailure: Int?,
  ) : AutomationEvent

  data class Cancelled(
    override val timestamp: Long = System.currentTimeMillis(),
    val reason: String?,
  ) : AutomationEvent
}

enum class AutomationMode {
  HOT_PATH,
  AI,
  HYBRID
}

enum class ActionType {
  TAP,
  SWIPE,
  MULTI_TOUCH,
  TEXT_INPUT,
  KEY_PRESS,
  WAIT,
  COMPLETE
}

enum class AIDecisionMode {
  IDLE,
  ANALYZING,
  EXECUTING
}
