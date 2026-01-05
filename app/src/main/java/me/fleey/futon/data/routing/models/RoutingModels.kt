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
package me.fleey.futon.data.routing.models

import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.PerceptionResult

/**
 * Actions that can be executed by the automation system.
 */
sealed interface Action {
  val name: String get() = this::class.simpleName ?: "Unknown"

  data class Tap(val x: Int, val y: Int) : Action
  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long = 200,
  ) : Action

  data class Input(val text: String, val targetElement: DetectedElement? = null) : Action
  data class Wait(val durationMs: Long) : Action
  data class LongPress(val x: Int, val y: Int, val durationMs: Long = 500) : Action
  data class LaunchApp(val packageName: String) : Action
}

sealed interface RoutingResult {
  data class HotPath(
    val action: Action,
    val latencyMs: Long,
    val patternId: String,
  ) : RoutingResult

  /**
   * Cold path: Requires LLM reasoning for complex commands.
   */
  data class ColdPath(
    val prompt: String,
    val context: UIContext,
  ) : RoutingResult
}

/**
 * UI context passed to cold path for LLM reasoning.
 */
data class UIContext(
  val elements: List<DetectedElement>,
  val screenWidth: Int,
  val screenHeight: Int,
  val timestamp: Long,
) {
  companion object {
    fun fromPerceptionResult(result: PerceptionResult): UIContext {
      return UIContext(
        elements = result.elements,
        screenWidth = result.imageWidth,
        screenHeight = result.imageHeight,
        timestamp = result.timestamp,
      )
    }
  }
}

data class HotPathPattern(
  val id: String,
  val pattern: Regex,
  val description: String,
  val actionGenerator: (MatchResult, PerceptionResult) -> Action?,
)

data class RoutingStats(
  val hotPathHits: Long,
  val coldPathHits: Long,
  val totalRouted: Long,
  val averageHotPathLatencyMs: Long,
  val registeredPatterns: Int,
) {
  private val hotPathRatio: Float
    get() = if (totalRouted > 0) hotPathHits.toFloat() / totalRouted else 0f

  val isOptimizationSuccess: Boolean
    get() = hotPathRatio >= 0.8f
}
