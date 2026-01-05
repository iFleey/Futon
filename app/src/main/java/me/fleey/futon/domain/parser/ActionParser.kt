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
package me.fleey.futon.domain.parser

import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionType
import org.koin.core.annotation.Single

/**
 * Parser for converting natural language actions into executable commands.
 */
@Single
class ActionParser {

  private val json = Json { ignoreUnknownKeys = true }

  private val thinkAnswerPattern = Regex(
    """<think>(.*?)</think>\s*<answer>(.*?)</answer>""",
    RegexOption.DOT_MATCHES_ALL,
  )

  private val codeBlockPattern = Regex("""```(?:json)?\s*([\s\S]*?)```""")

  fun parse(rawResponse: String): ActionParseResult {
    return try {
      val (reasoning, jsonString) = extractJsonAndReasoning(rawResponse)
      val response = json.decodeFromString<AIResponse>(jsonString)

      // If reasoning was extracted from <think> tag, add it to response
      val finalResponse = if (reasoning != null && response.reasoning == null) {
        response.copy(reasoning = reasoning)
      } else {
        response
      }

      validateResponse(finalResponse)
    } catch (e: Exception) {
      ActionParseResult.Error("Failed to parse response: ${e.message}")
    }
  }

  private fun extractJsonAndReasoning(rawResponse: String): Pair<String?, String> {
    val trimmed = rawResponse.trim()

    thinkAnswerPattern.find(trimmed)?.let { match ->
      val reasoning = match.groupValues[1].trim()
      val answerContent = match.groupValues[2].trim()
      return reasoning to answerContent
    }

    codeBlockPattern.find(trimmed)?.let { match ->
      return null to match.groupValues[1].trim()
    }

    val jsonStart = trimmed.indexOf('{')
    val jsonEnd = trimmed.lastIndexOf('}')
    if (jsonStart != -1 && jsonEnd > jsonStart) {
      return null to trimmed.substring(jsonStart, jsonEnd + 1)
    }

    return null to trimmed
  }

  private fun validateResponse(response: AIResponse): ActionParseResult {
    val params = response.parameters

    return when (response.action) {
      ActionType.TAP, ActionType.TAP_COORDINATE -> {
        if (params?.x == null || params.y == null) {
          ActionParseResult.Error("Tap action requires x and y coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.LONG_PRESS -> {
        if (params?.x == null || params.y == null) {
          ActionParseResult.Error("Long press action requires x and y coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.DOUBLE_TAP -> {
        if (params?.x == null || params.y == null) {
          ActionParseResult.Error("Double tap action requires x and y coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.SWIPE -> {
        if (params?.x1 == null || params.y1 == null || params.x2 == null || params.y2 == null) {
          ActionParseResult.Error("Swipe action requires x1, y1, x2, y2 coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.SCROLL -> {
        if (params?.x == null || params.y == null) {
          ActionParseResult.Error("Scroll action requires x and y coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.PINCH -> {
        if (params?.x == null || params.y == null) {
          ActionParseResult.Error("Pinch action requires center x and y coordinates")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.INPUT -> {
        if (params?.text == null) {
          ActionParseResult.Error("Input action requires text parameter")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.LAUNCH_APP -> {
        // Support both "package" and "text" for backwards compatibility
        if (params?.packageName == null && params?.text == null) {
          ActionParseResult.Error("Launch app action requires package name")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.LAUNCH_ACTIVITY -> {
        if (params?.packageName == null || params.activity == null) {
          ActionParseResult.Error("Launch activity requires package and activity names")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.SCREENSHOT -> {
        if (params?.path == null) {
          ActionParseResult.Error("Screenshot action requires file path")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.CALL -> {
        if (params?.command == null) {
          ActionParseResult.Error("Call action requires command name")
        } else {
          ActionParseResult.Success(response)
        }
      }

      ActionType.WAIT,
      ActionType.BACK,
      ActionType.HOME,
      ActionType.RECENTS,
      ActionType.NOTIFICATIONS,
      ActionType.QUICK_SETTINGS,
      ActionType.INTERVENE,
      ActionType.COMPLETE,
      ActionType.ERROR,
        -> ActionParseResult.Success(response)
    }
  }
}

sealed interface ActionParseResult {
  data class Success(val response: AIResponse) : ActionParseResult
  data class Error(val message: String) : ActionParseResult
}
