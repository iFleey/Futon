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
package me.fleey.futon.domain.som

import android.util.Log
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionParameters
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.domain.som.models.SomAction
import me.fleey.futon.domain.som.models.SomActionType
import me.fleey.futon.domain.som.models.SomAnnotation
import org.koin.core.annotation.Single

sealed interface SomParseResult {
  data class Success(
    val action: SomAction,
    val resolvedX: Int?,
    val resolvedY: Int?,
    val reasoning: String?,
  ) : SomParseResult

  data class Failure(
    val reason: String,
    val rawResponse: String,
  ) : SomParseResult
}

/**
 * Parses LLM responses into executable SoM actions.
 * Resolves element IDs to screen coordinates.
 */
interface SomActionParser {
  /**
   * Parse LLM response and resolve element coordinates.
   */
  fun parse(
    response: String,
    annotation: SomAnnotation,
  ): SomParseResult
}

@Single(binds = [SomActionParser::class])
class SomActionParserImpl(
  private val json: Json,
) : SomActionParser {

  override fun parse(
    response: String,
    annotation: SomAnnotation,
  ): SomParseResult {
    return try {
      // Extract JSON from response (handle <think>...</think><answer>...</answer> format)
      val jsonStr = extractJson(response)
        ?: return SomParseResult.Failure("No JSON found in response", response)

      // Parse action
      val action = json.decodeFromString<SomAction>(jsonStr)

      // Resolve element ID to coordinates
      val (resolvedX, resolvedY) = resolveCoordinates(action, annotation)

      // Extract reasoning from <think> block
      val reasoning = extractReasoning(response) ?: action.reasoning

      SomParseResult.Success(
        action = action.copy(reasoning = reasoning),
        resolvedX = resolvedX,
        resolvedY = resolvedY,
        reasoning = reasoning,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse SoM action: ${e.message}", e)
      SomParseResult.Failure(
        reason = "Parse error: ${e.message}",
        rawResponse = response,
      )
    }
  }

  private fun extractJson(response: String): String? {
    // Try to extract from <answer>...</answer> tags
    val answerPattern = """<answer>\s*([\s\S]*?)\s*</answer>""".toRegex()
    answerPattern.find(response)?.let { match ->
      return match.groupValues[1].trim()
    }

    // Try to find JSON object directly
    val jsonPattern = """\{[\s\S]*"action"[\s\S]*\}""".toRegex()
    jsonPattern.find(response)?.let { match ->
      return match.value
    }

    // Try markdown code block
    val codeBlockPattern = """```(?:json)?\s*([\s\S]*?)\s*```""".toRegex()
    codeBlockPattern.find(response)?.let { match ->
      val content = match.groupValues[1].trim()
      if (content.contains("\"action\"")) {
        return content
      }
    }

    return null
  }

  private fun extractReasoning(response: String): String? {
    val thinkPattern = """<think>\s*([\s\S]*?)\s*</think>""".toRegex()
    return thinkPattern.find(response)?.groupValues?.get(1)?.trim()
  }

  private fun resolveCoordinates(
    action: SomAction,
    annotation: SomAnnotation,
  ): Pair<Int?, Int?> {
    // If action has element_id, resolve to coordinates
    val elementId = action.elementId
    if (elementId != null) {
      val element = annotation.getElementById(elementId)
      if (element != null) {
        return element.centerX to element.centerY
      }
      Log.w(TAG, "Element ID $elementId not found in annotation")
    }

    val params = action.parameters
    if (params != null) {
      if (params.x != null && params.y != null) {
        return params.x to params.y
      }
    }

    return null to null
  }

  companion object {
    private const val TAG = "SomActionParser"
  }
}

/**
 * Extension to convert SomAction to legacy AIResponse format for compatibility.
 */
fun SomParseResult.Success.toAIResponse(): AIResponse {
  val actionType = when (action.action) {
    SomActionType.TAP, SomActionType.TAP_COORDINATE ->
      ActionType.TAP

    SomActionType.LONG_PRESS -> ActionType.LONG_PRESS
    SomActionType.DOUBLE_TAP -> ActionType.DOUBLE_TAP
    SomActionType.SWIPE -> ActionType.SWIPE
    SomActionType.SCROLL -> ActionType.SCROLL
    SomActionType.INPUT -> ActionType.INPUT
    SomActionType.BACK -> ActionType.BACK
    SomActionType.HOME -> ActionType.HOME
    SomActionType.LAUNCH_APP -> ActionType.LAUNCH_APP
    SomActionType.WAIT -> ActionType.WAIT
    SomActionType.COMPLETE -> ActionType.COMPLETE
    SomActionType.ERROR -> ActionType.ERROR
  }

  val params = action.parameters
  val actionParams = ActionParameters(
    x = resolvedX ?: params?.x,
    y = resolvedY ?: params?.y,
    x1 = params?.x1,
    y1 = params?.y1,
    x2 = params?.x2,
    y2 = params?.y2,
    text = params?.text,
    direction = params?.direction,
    distance = params?.distance,
    duration = params?.duration,
    packageName = params?.packageName,
    message = params?.message,
  )

  return AIResponse(
    action = actionType,
    parameters = actionParams,
    reasoning = reasoning,
    taskComplete = action.taskComplete,
  )
}
