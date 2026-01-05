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
package me.fleey.futon.data.trace.models

import kotlinx.serialization.Serializable
import me.fleey.futon.data.routing.models.Action

@JvmInline
value class TraceId(val id: String)

/**
 * A recorded trace of user operations.
 */
data class Trace(
  val id: TraceId,
  val taskDescription: String,
  val steps: List<TraceStep>,
  val success: Boolean,
  val createdAt: Long,
) {
  val stepCount: Int get() = steps.size
  val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * A single step in a trace.
 */
data class TraceStep(
  val uiHash: String,
  val action: Action,
  val latencyMs: Long,
  val success: Boolean,
  val stepOrder: Int,
)

/**
 * A cached action from successful traces.
 */
data class CachedAction(
  val uiHash: String,
  val action: Action,
  val confidence: Float,
  val successCount: Int,
  val lastUsed: Long,
) {
  val isHighConfidence: Boolean get() = successCount >= 3
}

/**
 * Serializable action data for Room storage.
 */
@Serializable
data class ActionData(
  val type: ActionType,
  val x: Int? = null,
  val y: Int? = null,
  val startX: Int? = null,
  val startY: Int? = null,
  val endX: Int? = null,
  val endY: Int? = null,
  val durationMs: Long? = null,
  val text: String? = null,
) {
  fun toAction(): Action {
    return when (type) {
      ActionType.TAP -> Action.Tap(x!!, y!!)
      ActionType.SWIPE -> Action.Swipe(startX!!, startY!!, endX!!, endY!!, durationMs ?: 200)
      ActionType.INPUT -> Action.Input(text!!)
      ActionType.WAIT -> Action.Wait(durationMs!!)
      ActionType.LONG_PRESS -> Action.LongPress(x!!, y!!, durationMs ?: 500)
      ActionType.LAUNCH_APP -> Action.LaunchApp(text!!)
    }
  }

  companion object {
    fun fromAction(action: Action): ActionData {
      return when (action) {
        is Action.Tap -> ActionData(ActionType.TAP, x = action.x, y = action.y)
        is Action.Swipe -> ActionData(
          ActionType.SWIPE,
          startX = action.startX,
          startY = action.startY,
          endX = action.endX,
          endY = action.endY,
          durationMs = action.durationMs,
        )

        is Action.Input -> ActionData(ActionType.INPUT, text = action.text)
        is Action.Wait -> ActionData(ActionType.WAIT, durationMs = action.durationMs)
        is Action.LongPress -> ActionData(
          ActionType.LONG_PRESS,
          x = action.x,
          y = action.y,
          durationMs = action.durationMs,
        )

        is Action.LaunchApp -> ActionData(ActionType.LAUNCH_APP, text = action.packageName)
      }
    }
  }
}

@Serializable
enum class ActionType {
  TAP, SWIPE, INPUT, WAIT, LONG_PRESS, LAUNCH_APP
}

data class TraceStats(
  val totalTraces: Int,
  val successfulTraces: Int,
  val hotPathEntries: Int,
  val storageSizeBytes: Long,
) {
  val successRate: Float
    get() = if (totalTraces > 0) successfulTraces.toFloat() / totalTraces else 0f
  val storageSizeMb: Float
    get() = storageSizeBytes / (1024f * 1024f)
}
