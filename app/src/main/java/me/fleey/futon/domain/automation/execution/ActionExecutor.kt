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
package me.fleey.futon.domain.automation.execution

import android.util.Log
import kotlinx.coroutines.delay
import me.fleey.futon.config.AutomationConfig
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionParameters
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.data.daemon.DaemonInputInjector
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.InputInjectionResult
import me.fleey.futon.data.daemon.ScrollDirection
import me.fleey.futon.data.routing.models.Action
import me.fleey.futon.domain.perception.ElementResult
import me.fleey.futon.domain.perception.MatchQuery
import me.fleey.futon.domain.perception.PerceptionEngine
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree
import me.fleey.futon.platform.input.models.GestureResult
import me.fleey.futon.platform.input.strategy.InputStrategyFactory
import org.koin.core.annotation.Single
import kotlin.math.sqrt

sealed interface ActionExecutionResult {
  data object Success : ActionExecutionResult
  data class Failure(val reason: String, val isRetryable: Boolean) : ActionExecutionResult
}

data class OptimizedActionResult(
  val success: Boolean,
  val response: AIResponse,
)

interface ActionExecutor {
  suspend fun initialize(): Boolean
  suspend fun execute(response: AIResponse): ActionExecutionResult
  suspend fun executeWithOptimization(
    response: AIResponse,
    uiTree: UITree?,
    hybridEnabled: Boolean,
  ): OptimizedActionResult

  suspend fun executeDaemonAction(action: Action): Boolean
  suspend fun executeHotPathAction(action: Action): Boolean
  fun release()
}

@Single(binds = [ActionExecutor::class])
class ActionExecutorImpl(
  private val inputStrategyFactory: InputStrategyFactory,
  private val daemonInputInjector: DaemonInputInjector,
  private val perceptionEngine: PerceptionEngine,
  private val daemonRepository: DaemonRepository,
) : ActionExecutor {

  override suspend fun initialize(): Boolean {
    return try {
      inputStrategyFactory.selectBestMethod()
      true
    } catch (e: IllegalStateException) {
      Log.e(TAG, "No input method available", e)
      false
    }
  }

  override suspend fun execute(response: AIResponse): ActionExecutionResult {
    val params = response.parameters
      ?: return if (response.action in listOf(
          ActionType.WAIT,
          ActionType.BACK,
          ActionType.HOME,
          ActionType.RECENTS,
          ActionType.NOTIFICATIONS,
          ActionType.QUICK_SETTINGS,
        )
      ) {
        executeNoParamAction(response.action)
      } else {
        ActionExecutionResult.Failure("Missing parameters", isRetryable = false)
      }

    return when (response.action) {
      ActionType.TAP, ActionType.TAP_COORDINATE -> executeTap(params)
      ActionType.LONG_PRESS -> executeLongPress(params)
      ActionType.DOUBLE_TAP -> executeDoubleTap(params)
      ActionType.SWIPE -> executeSwipe(params)
      ActionType.SCROLL -> executeScroll(params)
      ActionType.PINCH -> executePinch(params)
      ActionType.INPUT -> executeInput(params)
      ActionType.WAIT -> executeWait(params)
      ActionType.LAUNCH_APP -> executeLaunchApp(params)
      ActionType.LAUNCH_ACTIVITY -> executeLaunchActivity(params)
      ActionType.BACK -> executeBack()
      ActionType.HOME -> executeHome()
      ActionType.RECENTS -> executeRecents()
      ActionType.NOTIFICATIONS -> executeNotifications()
      ActionType.QUICK_SETTINGS -> executeQuickSettings()
      ActionType.SCREENSHOT -> executeScreenshot(params)
      ActionType.INTERVENE -> executeIntervene(params)
      ActionType.CALL -> executeCall(params)
      ActionType.COMPLETE, ActionType.ERROR -> ActionExecutionResult.Success
    }
  }

  private suspend fun executeNoParamAction(action: ActionType): ActionExecutionResult {
    return when (action) {
      ActionType.BACK -> executeBack()
      ActionType.HOME -> executeHome()
      ActionType.RECENTS -> executeRecents()
      ActionType.NOTIFICATIONS -> executeNotifications()
      ActionType.QUICK_SETTINGS -> executeQuickSettings()
      ActionType.WAIT -> {
        delay(AutomationConfig.ActionExecution.DEFAULT_WAIT_DURATION_MS)
        ActionExecutionResult.Success
      }

      else -> ActionExecutionResult.Failure(
        "Unsupported action without parameters",
        isRetryable = false,
      )
    }
  }

  override suspend fun executeWithOptimization(
    response: AIResponse,
    uiTree: UITree?,
    hybridEnabled: Boolean,
  ): OptimizedActionResult {
    // Handle INPUT with UI tree assistance
    if (response.action == ActionType.INPUT && uiTree != null && hybridEnabled) {
      return executeInputWithUITree(response, uiTree)
    }

    // For non-TAP or when UI tree unavailable, execute directly
    if (response.action != ActionType.TAP || uiTree == null || !hybridEnabled) {
      val result = execute(response)
      return OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }

    val params = response.parameters ?: run {
      val result = execute(response)
      return OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }
    val x = params.x ?: run {
      val result = execute(response)
      return OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }
    val y = params.y ?: run {
      val result = execute(response)
      return OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }

    // Strategy 1: Find element by text from AI reasoning
    val reasoning = response.reasoning ?: ""
    val targetElement = findTargetElementByReasoning(uiTree, reasoning)

    if (targetElement != null) {
      val centerPoint = targetElement.centerPoint()
      val success = executeTapWithFallback(centerPoint.x, centerPoint.y)

      if (success) {
        perceptionEngine.recordSuccessfulMatch(
          targetElement,
          MatchQuery(
            resourceId = targetElement.resourceId,
            text = targetElement.text,
            contentDesc = targetElement.contentDesc,
            mustBeClickable = true,
          ),
        )
      }

      val optimizedResponse = response.copy(
        parameters = params.copy(x = centerPoint.x, y = centerPoint.y),
        reasoning = "$reasoning [Found by text: ${targetElement.simpleClassName()} at (${centerPoint.x},${centerPoint.y})]",
      )
      return OptimizedActionResult(success, optimizedResponse)
    }

    // Strategy 2: Coordinate-based element finding
    val elementResult = perceptionEngine.findElementAt(x, y, mustBeClickable = true)

    return when (elementResult) {
      is ElementResult.Found -> {
        val node = elementResult.node
        val centerPoint = node.centerPoint()
        val distance = sqrt(
          ((centerPoint.x - x) * (centerPoint.x - x) +
            (centerPoint.y - y) * (centerPoint.y - y)).toDouble(),
        ).toInt()

        if (distance > AutomationConfig.CoordinateOptimization.MAX_DISTANCE) {
          val result = execute(response)
          return OptimizedActionResult(result is ActionExecutionResult.Success, response)
        }

        val success = executeTapWithFallback(centerPoint.x, centerPoint.y)

        if (success) {
          perceptionEngine.recordSuccessfulMatch(
            node,
            MatchQuery(
              resourceId = node.resourceId,
              text = node.text,
              contentDesc = node.contentDesc,
              mustBeClickable = true,
            ),
          )
        }

        val optimizedResponse = response.copy(
          parameters = params.copy(x = centerPoint.x, y = centerPoint.y),
          reasoning = "${response.reasoning ?: ""} [Optimized: ($x,$y) -> (${centerPoint.x},${centerPoint.y})]",
        )
        OptimizedActionResult(success, optimizedResponse)
      }

      else -> {
        val result = execute(response)
        OptimizedActionResult(result is ActionExecutionResult.Success, response)
      }
    }
  }

  override suspend fun executeDaemonAction(action: Action): Boolean {
    val result = when (action) {
      is Action.Tap -> daemonInputInjector.tap(action.x, action.y)
      is Action.Swipe -> daemonInputInjector.swipe(
        action.startX, action.startY,
        action.endX, action.endY,
        action.durationMs.toInt(),
      )

      is Action.Input -> daemonInputInjector.inputText(action.text)
      is Action.LongPress -> {
        daemonRepository.longPress(action.x, action.y, action.durationMs.toInt()).fold(
          onSuccess = { InputInjectionResult.Success },
          onFailure = {
            InputInjectionResult.Failure(
              me.fleey.futon.data.daemon.models.DaemonError.runtime(
                me.fleey.futon.data.daemon.models.ErrorCode.RUNTIME_INPUT_INJECTION_FAILED,
                "Long press failed: ${it.message}",
              ),
            )
          },
        )
      }

      is Action.Wait -> {
        delay(action.durationMs)
        InputInjectionResult.Success
      }

      is Action.LaunchApp -> {
        daemonRepository.launchAppSmart(action.packageName).fold(
          onSuccess = { InputInjectionResult.Success },
          onFailure = {
            InputInjectionResult.Failure(
              me.fleey.futon.data.daemon.models.DaemonError.runtime(
                me.fleey.futon.data.daemon.models.ErrorCode.RUNTIME_INPUT_INJECTION_FAILED,
                "Failed to launch app: ${action.packageName}",
              ),
            )
          },
        )
      }
    }
    return result is InputInjectionResult.Success
  }

  override suspend fun executeHotPathAction(action: Action): Boolean {
    return when (action) {
      is Action.Tap -> executeTapWithFallback(action.x, action.y)
      is Action.Swipe -> executeSwipeWithFallback(
        action.startX, action.startY,
        action.endX, action.endY,
        action.durationMs,
      )

      is Action.Wait -> {
        delay(action.durationMs)
        true
      }

      is Action.LongPress -> {
        daemonRepository.longPress(action.x, action.y, action.durationMs.toInt()).isSuccess
      }

      is Action.Input -> daemonInputInjector.inputText(action.text) is InputInjectionResult.Success

      is Action.LaunchApp -> {
        daemonRepository.launchAppSmart(action.packageName).isSuccess
      }
    }
  }

  override fun release() {
    // No resources to release
  }

  private suspend fun executeTap(params: ActionParameters): ActionExecutionResult {
    val x = params.x ?: return ActionExecutionResult.Failure("Missing x", isRetryable = false)
    val y = params.y ?: return ActionExecutionResult.Failure("Missing y", isRetryable = false)

    // Try daemon input injection first
    if (daemonInputInjector.isDaemonAvailable()) {
      return when (val result = daemonInputInjector.tap(x, y)) {
        is InputInjectionResult.Success -> ActionExecutionResult.Success
        is InputInjectionResult.Failure -> ActionExecutionResult.Failure(
          result.error.message,
          isRetryable = !result.retriesExhausted,
        )
      }
    }

    // Fallback to InputStrategyFactory
    val result = inputStrategyFactory.executeWithFallback { it.tap(x, y) }
    return when (result) {
      is GestureResult.Success -> ActionExecutionResult.Success
      is GestureResult.Failure -> ActionExecutionResult.Failure(result.message, isRetryable = false)
      is GestureResult.Timeout -> ActionExecutionResult.Failure("Timeout", isRetryable = true)
    }
  }

  private suspend fun executeSwipe(params: ActionParameters): ActionExecutionResult {
    val x1 = params.x1 ?: return ActionExecutionResult.Failure("Missing x1", isRetryable = false)
    val y1 = params.y1 ?: return ActionExecutionResult.Failure("Missing y1", isRetryable = false)
    val x2 = params.x2 ?: return ActionExecutionResult.Failure("Missing x2", isRetryable = false)
    val y2 = params.y2 ?: return ActionExecutionResult.Failure("Missing y2", isRetryable = false)
    val duration =
      params.duration ?: AutomationConfig.ActionExecution.DEFAULT_SWIPE_DURATION_MS.toInt()

    if (daemonInputInjector.isDaemonAvailable()) {
      return when (val result = daemonInputInjector.swipe(x1, y1, x2, y2, duration)) {
        is InputInjectionResult.Success -> ActionExecutionResult.Success
        is InputInjectionResult.Failure -> ActionExecutionResult.Failure(
          result.error.message,
          isRetryable = !result.retriesExhausted,
        )
      }
    }

    val result =
      inputStrategyFactory.executeWithFallback { it.swipe(x1, y1, x2, y2, duration.toLong()) }
    return when (result) {
      is GestureResult.Success -> ActionExecutionResult.Success
      is GestureResult.Failure -> ActionExecutionResult.Failure(result.message, isRetryable = false)
      is GestureResult.Timeout -> ActionExecutionResult.Failure("Timeout", isRetryable = true)
    }
  }

  private suspend fun executeInput(params: ActionParameters): ActionExecutionResult {
    val text =
      params.text ?: return ActionExecutionResult.Failure("Missing text", isRetryable = false)

    Log.d("ActionExecutor", "executeInput called: text.length=${text.length}")
    return when (val result = daemonInputInjector.inputText(text)) {
      is InputInjectionResult.Success -> {
        Log.d("ActionExecutor", "executeInput: Success")
        ActionExecutionResult.Success
      }

      is InputInjectionResult.Failure -> {
        Log.e("ActionExecutor", "executeInput: Failure - ${result.error.message}")
        ActionExecutionResult.Failure(
          result.error.message,
          isRetryable = !result.retriesExhausted,
        )
      }
    }
  }

  private suspend fun executeWait(params: ActionParameters): ActionExecutionResult {
    val duration =
      params.duration?.toLong() ?: AutomationConfig.ActionExecution.DEFAULT_WAIT_DURATION_MS
    delay(duration)
    return ActionExecutionResult.Success
  }

  private suspend fun executeLaunchApp(params: ActionParameters): ActionExecutionResult {
    Log.d(TAG, "executeLaunchApp: params=$params")
    Log.d(TAG, "executeLaunchApp: text=${params.text}, packageName=${params.packageName}")

    val appNameOrPackage = params.text ?: params.packageName
    if (appNameOrPackage == null) {
      Log.e(TAG, "executeLaunchApp: Both text and packageName are null!")
      return ActionExecutionResult.Failure("Missing package name or app name", isRetryable = false)
    }

    Log.d(TAG, "executeLaunchApp: launching '$appNameOrPackage'")

    return daemonRepository.launchAppSmart(appNameOrPackage).fold(
      onSuccess = {
        Log.d(TAG, "executeLaunchApp: Success for '$appNameOrPackage'")
        ActionExecutionResult.Success
      },
      onFailure = { e ->
        Log.e(TAG, "executeLaunchApp: Failed for '$appNameOrPackage'", e)
        ActionExecutionResult.Failure(
          e.message ?: "Failed to launch: $appNameOrPackage",
          isRetryable = false,
        )
      },
    )
  }

  private suspend fun executeLongPress(params: ActionParameters): ActionExecutionResult {
    val x = params.x ?: return ActionExecutionResult.Failure("Missing x", isRetryable = false)
    val y = params.y ?: return ActionExecutionResult.Failure("Missing y", isRetryable = false)
    val duration = params.duration ?: 500

    return daemonRepository.longPress(x, y, duration).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Long press failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeDoubleTap(params: ActionParameters): ActionExecutionResult {
    val x = params.x ?: return ActionExecutionResult.Failure("Missing x", isRetryable = false)
    val y = params.y ?: return ActionExecutionResult.Failure("Missing y", isRetryable = false)

    return daemonRepository.doubleTap(x, y).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Double tap failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeScroll(params: ActionParameters): ActionExecutionResult {
    val x = params.x ?: 540  // Default to center of 1080 width screen
    val y = params.y ?: 1200 // Default to center of 2400 height screen
    val directionStr = params.direction ?: "down"
    val distance = params.distance ?: 500

    val direction = when (directionStr.lowercase()) {
      "up" -> ScrollDirection.UP
      "down" -> ScrollDirection.DOWN
      "left" -> ScrollDirection.LEFT
      "right" -> ScrollDirection.RIGHT
      else -> ScrollDirection.DOWN
    }

    return daemonRepository.scroll(x, y, direction, distance).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Scroll failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executePinch(params: ActionParameters): ActionExecutionResult {
    val x =
      params.x ?: return ActionExecutionResult.Failure("Missing center x", isRetryable = false)
    val y =
      params.y ?: return ActionExecutionResult.Failure("Missing center y", isRetryable = false)
    val startDistance = params.startDistance ?: 200
    val endDistance = params.endDistance ?: 100
    val duration = params.duration ?: 300

    return daemonRepository.pinch(x, y, startDistance, endDistance, duration).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Pinch failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeLaunchActivity(params: ActionParameters): ActionExecutionResult {
    val packageName = params.packageName
      ?: return ActionExecutionResult.Failure("Missing package name", isRetryable = false)
    val activity = params.activity
      ?: return ActionExecutionResult.Failure("Missing activity name", isRetryable = false)

    return daemonRepository.launchActivity(packageName, activity).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Launch activity failed",
          isRetryable = false,
        )
      },
    )
  }

  private suspend fun executeBack(): ActionExecutionResult {
    return daemonRepository.pressBack().fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = { ActionExecutionResult.Failure(it.message ?: "Back failed", isRetryable = true) },
    )
  }

  private suspend fun executeHome(): ActionExecutionResult {
    return daemonRepository.pressHome().fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = { ActionExecutionResult.Failure(it.message ?: "Home failed", isRetryable = true) },
    )
  }

  private suspend fun executeRecents(): ActionExecutionResult {
    return daemonRepository.pressRecents().fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Recents failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeNotifications(): ActionExecutionResult {
    return daemonRepository.openNotifications().fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Open notifications failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeQuickSettings(): ActionExecutionResult {
    return daemonRepository.openQuickSettings().fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Open quick settings failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeScreenshot(params: ActionParameters): ActionExecutionResult {
    val path = params.path
      ?: return ActionExecutionResult.Failure("Missing file path", isRetryable = false)

    return daemonRepository.saveScreenshot(path).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Screenshot failed",
          isRetryable = true,
        )
      },
    )
  }

  private suspend fun executeIntervene(params: ActionParameters): ActionExecutionResult {
    val reason = params.reason ?: params.message ?: "User intervention required"
    val hint = params.hint ?: ""

    return daemonRepository.requestIntervention(reason, hint).fold(
      onSuccess = { ActionExecutionResult.Success },
      onFailure = {
        ActionExecutionResult.Failure(
          it.message ?: "Intervention request failed",
          isRetryable = false,
        )
      },
    )
  }

  private suspend fun executeCall(params: ActionParameters): ActionExecutionResult {
    val command = params.command
      ?: return ActionExecutionResult.Failure("Missing command", isRetryable = false)
    val args = params.args?.mapValues { it.value } ?: emptyMap()

    return daemonRepository.call(command, args).fold(
      onSuccess = { result ->
        if (result.success) {
          ActionExecutionResult.Success
        } else {
          ActionExecutionResult.Failure(result.error ?: "Call command failed", isRetryable = false)
        }
      },
      onFailure = { ActionExecutionResult.Failure(it.message ?: "Call failed", isRetryable = true) },
    )
  }

  private suspend fun executeTapWithFallback(x: Int, y: Int): Boolean {
    if (daemonInputInjector.isDaemonAvailable()) {
      return daemonInputInjector.tap(x, y) is InputInjectionResult.Success
    }

    val result = inputStrategyFactory.executeWithFallback { it.tap(x, y) }
    return result is GestureResult.Success
  }

  private suspend fun executeSwipeWithFallback(
    startX: Int, startY: Int,
    endX: Int, endY: Int,
    durationMs: Long,
  ): Boolean {
    if (daemonInputInjector.isDaemonAvailable()) {
      return daemonInputInjector.swipe(
        startX,
        startY,
        endX,
        endY,
        durationMs.toInt(),
      ) is InputInjectionResult.Success
    }

    val result = inputStrategyFactory.executeWithFallback {
      it.swipe(startX, startY, endX, endY, durationMs)
    }
    return result is GestureResult.Success
  }

  private suspend fun executeInputWithUITree(
    response: AIResponse,
    uiTree: UITree,
  ): OptimizedActionResult {
    Log.d("ActionExecutor", "executeInputWithUITree called")
    val text = response.parameters?.text ?: run {
      Log.d("ActionExecutor", "executeInputWithUITree: no text, falling back to execute()")
      val result = execute(response)
      return OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }

    val inputNode = findInputFieldInTree(uiTree.root)
    return if (inputNode != null) {
      val centerPoint = inputNode.centerPoint()
      Log.d("ActionExecutor", "executeInputWithUITree: found input field at (${centerPoint.x}, ${centerPoint.y})")
      // Tap on input field first to focus it
      daemonInputInjector.tap(centerPoint.x, centerPoint.y)
      // Use daemon for text input
      val result = daemonInputInjector.inputText(text)
      val success = result is InputInjectionResult.Success
      Log.d("ActionExecutor", "executeInputWithUITree: inputText result=$success")
      val optimizedResponse = response.copy(
        reasoning = "${response.reasoning ?: ""} [Input field found at (${centerPoint.x}, ${centerPoint.y})]",
      )
      OptimizedActionResult(success, optimizedResponse)
    } else {
      Log.d("ActionExecutor", "executeInputWithUITree: no input field found, falling back to execute()")
      val result = execute(response)
      OptimizedActionResult(result is ActionExecutionResult.Success, response)
    }
  }

  private fun findTargetElementByReasoning(uiTree: UITree, reasoning: String): UINode? {
    if (reasoning.isBlank()) return null

    val keywords = extractTargetKeywords(reasoning)
    if (keywords.isEmpty()) return null

    val clickableWithText = uiTree.root.findAll { it.isClickable && it.hasTextContent() }

    // Exact match first
    for (keyword in keywords) {
      val match = clickableWithText.find {
        it.text?.equals(keyword, ignoreCase = true) == true ||
          it.contentDesc?.equals(keyword, ignoreCase = true) == true
      }
      if (match != null) return match
    }

    // Partial match
    for (keyword in keywords) {
      val match = clickableWithText.find {
        it.text?.contains(keyword, ignoreCase = true) == true ||
          it.contentDesc?.contains(keyword, ignoreCase = true) == true
      }
      if (match != null) return match
    }

    return null
  }

  private fun extractTargetKeywords(reasoning: String): List<String> {
    val keywords = mutableListOf<String>()

    // Quoted text
    val quotedPattern = """['""'「」『』]([^'""'「」『』]+)['""'「」『』]""".toRegex()
    quotedPattern.findAll(reasoning).forEach { match ->
      val keyword = match.groupValues[1].trim()
      if (keyword.isNotBlank() && keyword.length <= 20) {
        keywords.add(keyword)
      }
    }

    // Common button keywords
    AutomationConfig.ButtonKeywords.ALL.forEach { keyword ->
      if (reasoning.contains(keyword, ignoreCase = true)) {
        keywords.add(keyword)
      }
    }

    return keywords.distinct()
  }

  private fun findInputFieldInTree(node: UINode): UINode? {
    val isEditTextClass = AutomationConfig.InputFieldPatterns.CLASS_PATTERNS.any {
      node.className.contains(it, ignoreCase = true)
    }
    if (isEditTextClass && node.isEnabled) return node

    val hasInputHint = AutomationConfig.InputFieldPatterns.HINT_PATTERNS.any {
      node.text?.contains(it, ignoreCase = true) == true ||
        node.contentDesc?.contains(it, ignoreCase = true) == true
    }
    if (hasInputHint && node.isEnabled && node.isClickable) return node

    for (child in node.children) {
      val found = findInputFieldInTree(child)
      if (found != null) return found
    }
    return null
  }

  companion object {
    private const val TAG = "ActionExecutor"
  }
}
