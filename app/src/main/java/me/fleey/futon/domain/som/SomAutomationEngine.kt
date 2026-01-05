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
import kotlinx.coroutines.delay
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.domain.automation.execution.ActionExecutionResult
import me.fleey.futon.domain.automation.execution.ActionExecutor
import me.fleey.futon.domain.som.models.SomAnnotation
import org.koin.core.annotation.Single

sealed interface SomStepResult {
  data class Success(
    val action: AIResponse,
    val annotation: SomAnnotation,
    val executionTimeMs: Long,
    val taskComplete: Boolean,
  ) : SomStepResult

  data class Failure(
    val reason: String,
    val phase: SomStepPhase,
    val isRecoverable: Boolean,
  ) : SomStepResult
}

enum class SomStepPhase {
  PERCEPTION,
  AI_DECISION,
  ACTION_EXECUTION,
}

interface SomAutomationEngine {
  suspend fun executeStep(
    task: String,
    actionHistory: List<String> = emptyList(),
    appContext: String? = null,
  ): SomStepResult

  suspend fun runAutomation(
    task: String,
    maxSteps: Int,
    stepDelayMs: Long = 1000L,
    onStepComplete: suspend (step: Int, result: SomStepResult) -> Unit = { _, _ -> },
  ): SomAutomationResult
}

sealed interface SomAutomationResult {
  data class Success(
    val totalSteps: Int,
    val totalTimeMs: Long,
  ) : SomAutomationResult

  data class Failure(
    val reason: String,
    val stepsCompleted: Int,
  ) : SomAutomationResult

  data object Cancelled : SomAutomationResult

  data class Timeout(
    val stepsCompleted: Int,
  ) : SomAutomationResult
}

@Single(binds = [SomAutomationEngine::class])
class SomAutomationEngineImpl(
  private val somPerceptionCoordinator: SomPerceptionCoordinator,
  private val somAIClient: SomAIClient,
  private val actionExecutor: ActionExecutor,
  private val settingsRepository: SettingsRepository,
) : SomAutomationEngine {

  override suspend fun executeStep(
    task: String,
    actionHistory: List<String>,
    appContext: String?,
  ): SomStepResult {
    val startTime = System.currentTimeMillis()
    val somSettings = settingsRepository.getSomSettings()

    if (!somSettings.enabled) {
      return SomStepResult.Failure(
        reason = "SoM mode is disabled in settings",
        phase = SomStepPhase.PERCEPTION,
        isRecoverable = false,
      )
    }

    Log.d(TAG, "SoM Step: Capturing perception...")
    val captureResult = somPerceptionCoordinator.capture()

    when (captureResult) {
      is SomCaptureResult.Failure -> {
        return SomStepResult.Failure(
          reason = captureResult.reason,
          phase = SomStepPhase.PERCEPTION,
          isRecoverable = captureResult.isRecoverable,
        )
      }

      is SomCaptureResult.Success -> {
        Log.d(TAG, "SoM Step: Captured ${captureResult.annotation.elementCount} elements")

        Log.d(TAG, "SoM Step: Requesting AI decision...")
        val aiResult = somAIClient.requestDecision(
          annotatedScreenshot = captureResult.annotatedScreenshot,
          annotation = captureResult.annotation,
          task = task,
          actionHistory = actionHistory,
          appContext = appContext,
        )

        when (aiResult) {
          is SomAIResult.Failure -> {
            return SomStepResult.Failure(
              reason = aiResult.reason,
              phase = SomStepPhase.AI_DECISION,
              isRecoverable = aiResult.isRetryable,
            )
          }

          is SomAIResult.Success -> {
            val response = aiResult.response
            Log.d(TAG, "SoM Step: AI decided ${response.action}, taskComplete=${response.taskComplete}")

            if (response.action == ActionType.COMPLETE) {
              return SomStepResult.Success(
                action = response,
                annotation = captureResult.annotation,
                executionTimeMs = System.currentTimeMillis() - startTime,
                taskComplete = true,
              )
            }

            if (response.action == ActionType.ERROR) {
              return SomStepResult.Failure(
                reason = response.parameters?.message ?: "AI reported error",
                phase = SomStepPhase.AI_DECISION,
                isRecoverable = false,
              )
            }

            Log.d(TAG, "SoM Step: Executing action...")
            val executionResult = actionExecutor.execute(response)

            return when (executionResult) {
              is ActionExecutionResult.Success -> {
                SomStepResult.Success(
                  action = response,
                  annotation = captureResult.annotation,
                  executionTimeMs = System.currentTimeMillis() - startTime,
                  taskComplete = response.taskComplete,
                )
              }

              is ActionExecutionResult.Failure -> {
                SomStepResult.Failure(
                  reason = executionResult.reason,
                  phase = SomStepPhase.ACTION_EXECUTION,
                  isRecoverable = executionResult.isRetryable,
                )
              }
            }
          }
        }
      }
    }
  }

  override suspend fun runAutomation(
    task: String,
    maxSteps: Int,
    stepDelayMs: Long,
    onStepComplete: suspend (step: Int, result: SomStepResult) -> Unit,
  ): SomAutomationResult {
    val startTime = System.currentTimeMillis()
    val actionHistory = mutableListOf<String>()

    for (step in 1..maxSteps) {
      Log.i(TAG, "SoM Automation: Step $step/$maxSteps")

      val stepResult = executeStep(
        task = task,
        actionHistory = actionHistory,
      )

      onStepComplete(step, stepResult)

      when (stepResult) {
        is SomStepResult.Success -> {
          // Record action in history
          val actionDesc = buildActionDescription(stepResult.action)
          actionHistory.add(actionDesc)

          if (stepResult.taskComplete) {
            return SomAutomationResult.Success(
              totalSteps = step,
              totalTimeMs = System.currentTimeMillis() - startTime,
            )
          }

          delay(stepDelayMs)
        }

        is SomStepResult.Failure -> {
          if (!stepResult.isRecoverable) {
            return SomAutomationResult.Failure(
              reason = stepResult.reason,
              stepsCompleted = step - 1,
            )
          }
          actionHistory.add("FAILED: ${stepResult.reason}")
          delay(stepDelayMs)
        }
      }
    }

    return SomAutomationResult.Timeout(stepsCompleted = maxSteps)
  }

  private fun buildActionDescription(response: AIResponse): String {
    val params = response.parameters
    return when (response.action) {
      ActionType.TAP, ActionType.TAP_COORDINATE -> "tap(${params?.x},${params?.y})"
      ActionType.LONG_PRESS -> "long_press(${params?.x},${params?.y})"
      ActionType.SWIPE -> "swipe(${params?.x1},${params?.y1}${params?.x2},${params?.y2})"
      ActionType.SCROLL -> "scroll(${params?.direction})"
      ActionType.INPUT -> "input(\"${params?.text?.take(20)}\")"
      ActionType.BACK -> "back()"
      ActionType.HOME -> "home()"
      ActionType.LAUNCH_APP -> "launch_app(${params?.packageName ?: params?.text})"
      ActionType.WAIT -> "wait(${params?.duration}ms)"
      else -> "${response.action.name.lowercase()}()"
    }
  }

  companion object {
    private const val TAG = "SomAutomationEngine"
  }
}
