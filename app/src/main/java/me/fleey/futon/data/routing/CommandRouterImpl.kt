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
package me.fleey.futon.data.routing

import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.routing.models.HotPathPattern
import me.fleey.futon.data.routing.models.RoutingResult
import me.fleey.futon.data.routing.models.RoutingStats
import me.fleey.futon.data.routing.models.UIContext
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Single(binds = [CommandRouter::class])
class CommandRouterImpl : CommandRouter {

  private val patterns = ConcurrentHashMap<String, HotPathPattern>()

  private val hotPathHits = AtomicLong(0)
  private val coldPathHits = AtomicLong(0)
  private val totalHotPathLatencyNs = AtomicLong(0)

  init {
    registerDefaultPatterns()
  }

  override suspend fun route(command: String, uiState: PerceptionResult): RoutingResult {
    val startTime = System.nanoTime()

    for (pattern in patterns.values) {
      val matchResult = pattern.pattern.find(command)
      if (matchResult != null) {
        val action = pattern.actionGenerator(matchResult, uiState)
        if (action != null) {
          val latencyNs = System.nanoTime() - startTime
          val latencyMs = latencyNs / 1_000_000

          hotPathHits.incrementAndGet()
          totalHotPathLatencyNs.addAndGet(latencyNs)

          return RoutingResult.HotPath(
            action = action,
            latencyMs = latencyMs,
            patternId = pattern.id,
          )
        }
      }
    }

    coldPathHits.incrementAndGet()

    return RoutingResult.ColdPath(
      prompt = command,
      context = UIContext.fromPerceptionResult(uiState),
    )
  }

  override fun registerHotPath(pattern: HotPathPattern) {
    patterns[pattern.id] = pattern
  }

  override fun unregisterHotPath(patternId: String): Boolean {
    return patterns.remove(patternId) != null
  }

  override fun getRegisteredPatterns(): List<HotPathPattern> {
    return patterns.values.toList()
  }

  override fun getStats(): RoutingStats {
    val hits = hotPathHits.get()
    val misses = coldPathHits.get()
    val total = hits + misses
    val avgLatency = if (hits > 0) {
      totalHotPathLatencyNs.get() / hits / 1_000_000
    } else {
      0L
    }

    return RoutingStats(
      hotPathHits = hits,
      coldPathHits = misses,
      totalRouted = total,
      averageHotPathLatencyMs = avgLatency,
      registeredPatterns = patterns.size,
    )
  }

  override fun resetStats() {
    hotPathHits.set(0)
    coldPathHits.set(0)
    totalHotPathLatencyNs.set(0)
  }

  private fun registerDefaultPatterns() {
    // Pattern: "tap <element_text>" or "click <element_text>"
    registerHotPath(
      HotPathPattern(
        id = "tap_by_text",
        pattern = Regex("""(?i)^(?:tap|click|点击|按)\s+(.+)$"""),
        description = "Tap element by text content",
      ) { match, uiState ->
        val targetText = match.groupValues[1].trim()
        findElementByText(targetText, uiState)?.let { element ->
          me.fleey.futon.data.routing.models.Action.Tap(
            x = element.centerX,
            y = element.centerY,
          )
        }
      },
    )

    // Pattern: "tap at x,y" or "click at x,y"
    registerHotPath(
      HotPathPattern(
        id = "tap_coordinates",
        pattern = Regex("""(?i)^(?:tap|click|点击)\s+(?:at\s+)?(\d+)\s*[,，]\s*(\d+)$"""),
        description = "Tap at specific coordinates",
      ) { match, _ ->
        val x = match.groupValues[1].toIntOrNull()
        val y = match.groupValues[2].toIntOrNull()
        if (x != null && y != null) {
          me.fleey.futon.data.routing.models.Action.Tap(x = x, y = y)
        } else null
      },
    )

    // Pattern: "swipe up/down/left/right"
    registerHotPath(
      HotPathPattern(
        id = "swipe_direction",
        pattern = Regex("""(?i)^(?:swipe|滑动|划)\s+(up|down|left|right|上|下|左|右)$"""),
        description = "Swipe in a direction",
      ) { match, uiState ->
        val direction = match.groupValues[1].lowercase()
        val centerX = uiState.imageWidth / 2
        val centerY = uiState.imageHeight / 2
        val distance = minOf(uiState.imageWidth, uiState.imageHeight) / 3

        when (direction) {
          "up", "上" -> me.fleey.futon.data.routing.models.Action.Swipe(
            startX = centerX, startY = centerY + distance,
            endX = centerX, endY = centerY - distance,
          )

          "down", "下" -> me.fleey.futon.data.routing.models.Action.Swipe(
            startX = centerX, startY = centerY - distance,
            endX = centerX, endY = centerY + distance,
          )

          "left", "左" -> me.fleey.futon.data.routing.models.Action.Swipe(
            startX = centerX + distance, startY = centerY,
            endX = centerX - distance, endY = centerY,
          )

          "right", "右" -> me.fleey.futon.data.routing.models.Action.Swipe(
            startX = centerX - distance, startY = centerY,
            endX = centerX + distance, endY = centerY,
          )

          else -> null
        }
      },
    )

    // Pattern: "wait <duration>ms" or "wait <duration> seconds"
    registerHotPath(
      HotPathPattern(
        id = "wait_duration",
        pattern = Regex("""(?i)^(?:wait|等待)\s+(\d+)\s*(?:ms|毫秒|milliseconds?)?$"""),
        description = "Wait for specified milliseconds",
      ) { match, _ ->
        val duration = match.groupValues[1].toLongOrNull()
        if (duration != null && duration > 0) {
          me.fleey.futon.data.routing.models.Action.Wait(durationMs = duration)
        } else null
      },
    )

    // Pattern: "wait <duration> seconds"
    registerHotPath(
      HotPathPattern(
        id = "wait_seconds",
        pattern = Regex("""(?i)^(?:wait|等待)\s+(\d+)\s*(?:s|秒|seconds?)$"""),
        description = "Wait for specified seconds",
      ) { match, _ ->
        val seconds = match.groupValues[1].toLongOrNull()
        if (seconds != null && seconds > 0) {
          me.fleey.futon.data.routing.models.Action.Wait(durationMs = seconds * 1000)
        } else null
      },
    )

    // Pattern: "input <text>" or "type <text>"
    registerHotPath(
      HotPathPattern(
        id = "input_text",
        pattern = Regex("""(?i)^(?:input|type|输入)\s+(.+)$"""),
        description = "Input text into focused field",
      ) { match, uiState ->
        val text = match.groupValues[1].trim()
        if (text.isNotEmpty()) {
          val textField = uiState.elements.find {
            it.elementType == me.fleey.futon.data.perception.models.ElementType.TEXT_FIELD
          }
          me.fleey.futon.data.routing.models.Action.Input(
            text = text,
            targetElement = textField,
          )
        } else null
      },
    )

    // Pattern: "long press <element_text>"
    registerHotPath(
      HotPathPattern(
        id = "long_press_by_text",
        pattern = Regex("""(?i)^(?:long\s*press|长按)\s+(.+)$"""),
        description = "Long press element by text content",
      ) { match, uiState ->
        val targetText = match.groupValues[1].trim()
        findElementByText(targetText, uiState)?.let { element ->
          me.fleey.futon.data.routing.models.Action.LongPress(
            x = element.centerX,
            y = element.centerY,
          )
        }
      },
    )

    // Pattern: "back" or "go back"
    registerHotPath(
      HotPathPattern(
        id = "back_button",
        pattern = Regex("""(?i)^(?:back|go\s*back|返回)$"""),
        description = "Press back button",
      ) { _, uiState ->
        me.fleey.futon.data.routing.models.Action.Swipe(
          startX = 0,
          startY = uiState.imageHeight / 2,
          endX = uiState.imageWidth / 3,
          endY = uiState.imageHeight / 2,
          durationMs = 150,
        )
      },
    )

    // Pattern: "scroll to <element_text>"
    registerHotPath(
      HotPathPattern(
        id = "scroll_to_text",
        pattern = Regex("""(?i)^(?:scroll\s*to|滚动到)\s+(.+)$"""),
        description = "Scroll to find element by text",
      ) { match, uiState ->
        val targetText = match.groupValues[1].trim()
        val element = findElementByText(targetText, uiState)
        if (element != null) {
          me.fleey.futon.data.routing.models.Action.Tap(
            x = element.centerX,
            y = element.centerY,
          )
        } else {
          // Element not visible, scroll down to find it
          val centerX = uiState.imageWidth / 2
          val centerY = uiState.imageHeight / 2
          val distance = uiState.imageHeight / 3
          me.fleey.futon.data.routing.models.Action.Swipe(
            startX = centerX,
            startY = centerY + distance,
            endX = centerX,
            endY = centerY - distance,
          )
        }
      },
    )
  }

  private fun findElementByText(
    targetText: String,
    uiState: PerceptionResult,
  ): me.fleey.futon.data.perception.models.DetectedElement? {
    val normalizedTarget = targetText.lowercase().trim()

    uiState.elements.find { element ->
      element.text?.lowercase()?.trim() == normalizedTarget
    }?.let { return it }

    uiState.elements.find { element ->
      element.text?.lowercase()?.contains(normalizedTarget) == true
    }?.let { return it }

    // Fuzzy match (element text contains target or target contains element text)
    return uiState.elements.find { element ->
      val elementText = element.text?.lowercase()?.trim() ?: return@find false
      elementText.contains(normalizedTarget) || normalizedTarget.contains(elementText)
    }
  }
}
