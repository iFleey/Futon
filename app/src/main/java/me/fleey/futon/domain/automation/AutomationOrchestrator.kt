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
package me.fleey.futon.domain.automation

/**
 * Refactored AutomationEngine implementation using composition.
 * Orchestrates perception, AI decision making, and action execution.
 */
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.fleey.futon.config.AutomationConfig
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.apps.AppDiscovery
import me.fleey.futon.data.daemon.AutomationCompleteListener
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.data.history.models.AIResponseLog
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.ErrorLogEntry
import me.fleey.futon.data.history.models.ErrorType
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.routing.models.Action
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.trace.TraceRecorder
import me.fleey.futon.data.trace.UIHashComputer
import me.fleey.futon.domain.automation.ai.AIDecisionMaker
import me.fleey.futon.domain.automation.ai.AIDecisionResult
import me.fleey.futon.domain.automation.daemon.DaemonCoordinationResult
import me.fleey.futon.domain.automation.daemon.DaemonCoordinator
import me.fleey.futon.domain.automation.execution.ActionExecutor
import me.fleey.futon.domain.automation.history.AutomationHistoryManager
import me.fleey.futon.domain.automation.hotpath.HotPathExecutor
import me.fleey.futon.domain.automation.hotpath.HotPathResult
import me.fleey.futon.domain.automation.models.ActionLogEntry
import me.fleey.futon.domain.automation.models.ActionResult
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.domain.automation.models.DaemonHealth
import me.fleey.futon.domain.automation.models.ExecutionPhase
import me.fleey.futon.domain.automation.perception.PerceptionCaptureResult
import me.fleey.futon.domain.automation.perception.PerceptionCoordinator
import me.fleey.futon.domain.automation.state.AutomationStateManager
import org.koin.core.annotation.Single
import me.fleey.futon.data.perception.models.PerceptionResult as DspPerceptionResult

@Single(binds = [AutomationEngine::class])
class AutomationOrchestrator(
  private val stateManager: AutomationStateManager,
  private val perceptionCoordinator: PerceptionCoordinator,
  private val actionExecutor: ActionExecutor,
  private val aiDecisionMaker: AIDecisionMaker,
  private val hotPathExecutor: HotPathExecutor,
  private val daemonCoordinator: DaemonCoordinator,
  private val historyManager: AutomationHistoryManager,
  private val traceRecorder: TraceRecorder,
  private val uiHashComputer: UIHashComputer,
  private val settingsRepository: SettingsRepository,
  private val providerRepository: ProviderRepository,
  private val appDiscovery: AppDiscovery,
  private val daemonRepository: DaemonRepository,
) : AutomationEngine, AutomationCompleteListener {

  override val state: StateFlow<AutomationState> = stateManager.state
  override val automationMode: StateFlow<AutomationMode> = stateManager.automationMode
  override val aiDecisionMode: StateFlow<AIDecisionMode> = stateManager.aiDecisionMode
  override val daemonHealth: StateFlow<DaemonHealth> = stateManager.daemonHealth

  private var currentJob: Job? = null
  private var isDaemonHotPathActive = false

  override fun onAutomationComplete(success: Boolean, message: String?) {
    Log.i(TAG, "Daemon automation complete: success=$success, message=$message")
    isDaemonHotPathActive = false
  }

  override fun onHotPathNoMatch(consecutiveFrames: Int) {
    Log.d(TAG, "Hot path no match: $consecutiveFrames consecutive frames")
    hotPathExecutor.consecutiveNoMatchFrames
  }

  override suspend fun startTask(taskDescription: String): AutomationResult {
    Log.i(TAG, "Starting task: $taskDescription")

    val hasConfiguredProvider = providerRepository.getProviders().any { it.isConfigured() && it.enabled }
    if (!hasConfiguredProvider) {
      return AutomationResult.Failure("No AI provider configured")
    }

    val settings = settingsRepository.getSettings()

    stateManager.reset()
    hotPathExecutor.reset()
    daemonCoordinator.reset()
    isDaemonHotPathActive = false

    if (!initializeComponents()) {
      return AutomationResult.Failure("Failed to initialize automation components")
    }

    // Setup daemon
    val daemonReady = setupDaemon()
    stateManager.setMode(
      if (daemonReady && hotPathExecutor.consecutiveNoMatchFrames == 0) {
        AutomationMode.HYBRID
      } else {
        AutomationMode.AI
      },
    )

    return coroutineScope {
      currentJob = launch {
        runAutomationLoop(taskDescription, settings)
      }
      currentJob?.join()

      when (val finalState = stateManager.state.value) {
        is AutomationState.Completed -> finalState.result
        else -> AutomationResult.Failure("Unexpected state")
      }
    }
  }

  override fun stopTask() {
    Log.i(TAG, "Task stopped by user")
    currentJob?.cancel()
    isDaemonHotPathActive = false
    stateManager.setAIDecisionMode(AIDecisionMode.IDLE)
    stateManager.setMode(AutomationMode.HYBRID)

    daemonRepository.setAutomationCompleteListener(null)
    kotlinx.coroutines.runBlocking {
      daemonCoordinator.stopAutomation()
    }

    releaseComponents()
    stateManager.complete(AutomationResult.Cancelled)
  }

  private suspend fun initializeComponents(): Boolean {
    if (!perceptionCoordinator.initialize()) {
      Log.e(TAG, "Failed to initialize perception coordinator")
      return false
    }
    if (!actionExecutor.initialize()) {
      Log.e(TAG, "Failed to initialize action executor")
      return false
    }
    return true
  }

  private fun releaseComponents() {
    perceptionCoordinator.release()
    actionExecutor.release()
  }

  private suspend fun setupDaemon(): Boolean {
    return when (daemonCoordinator.ensureReady()) {
      is DaemonCoordinationResult.Ready -> {
        daemonCoordinator.configureHotPath()
        daemonRepository.setAutomationCompleteListener(this)
        true
      }

      else -> {
        Log.w(TAG, "Daemon not available, using app-side automation")
        false
      }
    }
  }

  private suspend fun runAutomationLoop(
    task: String,
    settings: me.fleey.futon.data.settings.models.AISettings,
  ) {
    var stepCount = 0
    val taskStartTimeMs = System.currentTimeMillis()
    val actionHistory = mutableListOf<ActionLogEntry>()
    val aiResponses = mutableListOf<AIResponseLog>()
    val errors = mutableListOf<ErrorLogEntry>()

    val traceId = traceRecorder.startRecording(task)
    Log.i(TAG, "Started trace recording: ${traceId.id}")

    handleAppLaunchIntent(task, taskStartTimeMs, actionHistory)

    val appContext = try {
      appDiscovery.getAIContext()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to get app context: ${e.message}")
      null
    }

    val hybridSettings = settingsRepository.getHybridPerceptionSettings()

    for (step in 1..settings.maxSteps) {
      stepCount = step
      val stepStartTimeMs = System.currentTimeMillis()
      Log.i(TAG, "Step $step/${settings.maxSteps}")

      stateManager.updateRunning(
        task = task,
        step = step,
        maxSteps = settings.maxSteps,
        phase = ExecutionPhase.CAPTURING_SCREENSHOT,
        actionHistory = actionHistory.toList(),
        taskStartTimeMs = taskStartTimeMs,
        stepStartTimeMs = stepStartTimeMs,
      )

      val captureResult = perceptionCoordinator.capture(settings.screenshotQuality.compressionValue)
      when (captureResult) {
        is PerceptionCaptureResult.Failure -> {
          errors.add(createErrorEntry(step, ErrorType.ACTION_FAILED, captureResult.reason))
          traceRecorder.stopRecording(success = false)
          completeWithFailure(
            task,
            stepCount,
            captureResult.reason,
            taskStartTimeMs,
            actionHistory,
            aiResponses,
            errors,
          )
          return
        }

        is PerceptionCaptureResult.Success -> {
          if (captureResult.detectedElements.isNotEmpty() && !hotPathExecutor.shouldTriggerAiFallback) {
            val hotPathResult = hotPathExecutor.tryExecute(
              captureResult.detectedElements,
              imageWidth = 1080,
              imageHeight = 2400,
            )

            when (hotPathResult) {
              is HotPathResult.Hit -> {
                stateManager.setMode(AutomationMode.HOT_PATH)
                stateManager.recordHotPathResult(hit = true)

                actionHistory.add(createHotPathLogEntry(step, hotPathResult))
                hotPathExecutor.recordSuccess(hotPathResult.uiHash, hotPathResult.action)

                stateManager.updateRunning(
                  task = task,
                  step = step,
                  maxSteps = settings.maxSteps,
                  phase = ExecutionPhase.WAITING,
                  lastAction = "Hot Path: ${hotPathResult.action::class.simpleName}",
                  actionHistory = actionHistory.toList(),
                  currentReasoning = "Executed from learned pattern",
                  taskStartTimeMs = taskStartTimeMs,
                  stepStartTimeMs = stepStartTimeMs,
                )

                delay(settings.stepDelayMs)
                continue
              }

              is HotPathResult.ExecutionFailed -> {
                hotPathExecutor.recordFailure(hotPathResult.action)
                stateManager.recordHotPathResult(hit = false)
              }

              is HotPathResult.Miss -> {
                stateManager.recordHotPathResult(hit = false)
              }
            }
          }

          // AI fallback
          stateManager.setAIDecisionMode(AIDecisionMode.ANALYZING)
          stateManager.setMode(AutomationMode.AI)
          stateManager.updateRunning(
            task = task,
            step = step,
            maxSteps = settings.maxSteps,
            phase = ExecutionPhase.ANALYZING_WITH_AI,
            actionHistory = actionHistory.toList(),
            taskStartTimeMs = taskStartTimeMs,
            stepStartTimeMs = stepStartTimeMs,
          )

          // Build action history with clear result indicators
          val historyForAI = actionHistory.takeLast(10).map { entry ->
            val params = entry.parameters?.let { p ->
              listOfNotNull(
                p.x?.let { "x=$it" },
                p.y?.let { "y=$it" },
                p.text?.takeIf { it.isNotBlank() }?.let { "text=\"$it\"" },
              ).joinToString(",")
            } ?: ""
            val resultStr = when (entry.result) {
              is ActionResult.Success -> "OK"
              is ActionResult.Failure -> "FAILED"
            }
            "${entry.action.name}($params) -> $resultStr"
          }

          // Request AI decision
          val aiResult = aiDecisionMaker.requestDecision(
            screenshot = captureResult.screenshot,
            uiContext = captureResult.combinedContext,
            appContext = if (step == 1) appContext else null,
            task = task,
            maxRetries = settings.maxRetries,
            actionHistory = historyForAI,
          ) { attempt, reason ->
            stateManager.updateRunning(
              task = task,
              step = step,
              maxSteps = settings.maxSteps,
              phase = ExecutionPhase.RETRYING,
              actionHistory = actionHistory.toList(),
              taskStartTimeMs = taskStartTimeMs,
              stepStartTimeMs = stepStartTimeMs,
              retryAttempt = attempt,
              retryReason = reason,
            )
          }

          when (aiResult) {
            is AIDecisionResult.Failure -> {
              stateManager.setAIDecisionMode(AIDecisionMode.IDLE)
              stateManager.recordAiCall(success = false)
              errors.add(createErrorEntry(step, aiResult.errorType, aiResult.reason))
              traceRecorder.stopRecording(success = false)
              completeWithFailure(
                task,
                stepCount,
                "AI request failed: ${aiResult.reason}",
                taskStartTimeMs,
                actionHistory,
                aiResponses,
                errors,
              )
              return
            }

            is AIDecisionResult.Success -> {
              stateManager.recordAiCall(success = true)
              aiResponses.add(
                AIResponseLog(
                  step = step,
                  action = aiResult.response.action,
                  reasoning = aiResult.response.reasoning,
                  responseTimeMs = aiResult.responseTimeMs,
                  timestamp = System.currentTimeMillis(),
                ),
              )

              stateManager.setAIDecisionMode(AIDecisionMode.EXECUTING)
              stateManager.updateRunning(
                task = task,
                step = step,
                maxSteps = settings.maxSteps,
                phase = ExecutionPhase.EXECUTING_ACTION,
                actionHistory = actionHistory.toList(),
                currentReasoning = aiResult.response.reasoning,
                taskStartTimeMs = taskStartTimeMs,
                stepStartTimeMs = stepStartTimeMs,
              )

              if (aiResult.response.action == ActionType.COMPLETE) {
                stateManager.setAIDecisionMode(AIDecisionMode.IDLE)
                traceRecorder.stopRecording(success = true)
                completeWithSuccess(
                  task,
                  stepCount,
                  taskStartTimeMs,
                  actionHistory,
                  aiResponses,
                  errors,
                )
                return
              }

              if (aiResult.response.action == ActionType.ERROR) {
                val errorMsg = aiResult.response.parameters?.message ?: "Unknown error"
                errors.add(createErrorEntry(step, ErrorType.API_ERROR, errorMsg))
                traceRecorder.stopRecording(success = false)
                completeWithFailure(
                  task,
                  stepCount,
                  errorMsg,
                  taskStartTimeMs,
                  actionHistory,
                  aiResponses,
                  errors,
                )
                return
              }

              val actionStartTime = System.currentTimeMillis()

              // Resolve element_id to coordinates if SOM mode is active
              val resolvedResponse = if (captureResult.isSomMode && aiResult.response.elementId != null) {
                resolveElementIdToCoordinates(aiResult.response, captureResult.somAnnotation)
              } else {
                aiResult.response
              }

              val (actionSuccess, optimizedResponse) = actionExecutor.executeWithOptimization(
                response = resolvedResponse,
                uiTree = captureResult.uiTree,
                hybridEnabled = hybridSettings.enabled && hybridSettings.adaptiveLearningEnabled,
              )
              val actionDurationMs = System.currentTimeMillis() - actionStartTime

              actionHistory.add(
                ActionLogEntry(
                  step = step,
                  action = optimizedResponse.action,
                  parameters = optimizedResponse.parameters,
                  reasoning = optimizedResponse.reasoning,
                  result = if (actionSuccess) ActionResult.Success else ActionResult.Failure("Execution failed"),
                  durationMs = actionDurationMs,
                ),
              )

              // Record for trace learning
              recordTraceStep(captureResult, optimizedResponse, actionSuccess)

              if (!actionSuccess) {
                errors.add(
                  createErrorEntry(
                    step,
                    ErrorType.ACTION_FAILED,
                    "Action execution failed",
                  ),
                )
                traceRecorder.stopRecording(success = false)
                completeWithFailure(
                  task,
                  stepCount,
                  "Action execution failed",
                  taskStartTimeMs,
                  actionHistory,
                  aiResponses,
                  errors,
                )
                return
              }

              if (aiResult.response.taskComplete) {
                traceRecorder.stopRecording(success = true)
                completeWithSuccess(
                  task,
                  stepCount,
                  taskStartTimeMs,
                  actionHistory,
                  aiResponses,
                  errors,
                )
                return
              }

              stateManager.updateRunning(
                task = task,
                step = step,
                maxSteps = settings.maxSteps,
                phase = ExecutionPhase.WAITING,
                lastAction = "${optimizedResponse.action}: ${optimizedResponse.reasoning ?: ""}",
                actionHistory = actionHistory.toList(),
                currentReasoning = optimizedResponse.reasoning,
                taskStartTimeMs = taskStartTimeMs,
                stepStartTimeMs = stepStartTimeMs,
              )

              delay(settings.stepDelayMs)
            }
          }
        }
      }
    }

    // Timeout
    traceRecorder.stopRecording(success = false)
    val executionLogId = historyManager.saveExecutionLog(
      task,
      taskStartTimeMs,
      AutomationResultType.TIMEOUT,
      stepCount,
      actionHistory,
      aiResponses,
      errors,
    )
    historyManager.saveTaskHistory(task, AutomationResultType.TIMEOUT, stepCount, executionLogId)
    stateManager.complete(AutomationResult.Timeout)
  }

  private suspend fun handleAppLaunchIntent(
    task: String,
    taskStartTimeMs: Long,
    actionHistory: MutableList<ActionLogEntry>,
  ) {
    val targetApp = try {
      appDiscovery.detectAppLaunchIntent(task)
    } catch (e: Exception) {
      null
    }

    if (targetApp != null) {
      Log.i(TAG, "Detected app launch intent: ${targetApp.appName}")
      if (appDiscovery.launchApp(targetApp.packageName)) {
        actionHistory.add(
          ActionLogEntry(
            step = 1,
            action = ActionType.LAUNCH_APP,
            parameters = me.fleey.futon.data.ai.models.ActionParameters(text = targetApp.packageName),
            result = ActionResult.Success,
            durationMs = System.currentTimeMillis() - taskStartTimeMs,
            reasoning = "Directly launched ${targetApp.appName}",
          ),
        )
        delay(AutomationConfig.ActionExecution.APP_LAUNCH_DELAY_MS)
      }
    }
  }

  private fun recordTraceStep(
    captureResult: PerceptionCaptureResult.Success,
    response: AIResponse,
    success: Boolean,
  ) {
    if (captureResult.detectedElements.isNotEmpty()) {
      val perceptionResult = DspPerceptionResult(
        elements = captureResult.detectedElements,
        imageWidth = 1080,
        imageHeight = 2400,
        captureLatencyMs = 0,
        detectionLatencyMs = 0,
        ocrLatencyMs = 0,
        totalLatencyMs = 0,
        activeDelegate = DelegateType.NONE,
        timestamp = System.currentTimeMillis(),
      )
      val uiHash = uiHashComputer.computeHash(perceptionResult)
      val action = mapAIResponseToAction(response)
      traceRecorder.recordStep(uiHash, action, success)
    }
  }

  private fun createHotPathLogEntry(step: Int, result: HotPathResult.Hit): ActionLogEntry {
    return ActionLogEntry(
      step = step,
      action = mapActionToAIActionType(result.action),
      parameters = mapActionToParams(result.action),
      reasoning = "[Hot Path] Executed from cache (confidence: ${result.confidence})",
      result = ActionResult.Success,
      durationMs = result.executionTimeMs,
    )
  }

  private fun createErrorEntry(step: Int, type: ErrorType, message: String): ErrorLogEntry {
    return ErrorLogEntry(
      step = step,
      errorType = type,
      message = message,
      isRetryable = false,
      timestamp = System.currentTimeMillis(),
    )
  }

  private suspend fun completeWithSuccess(
    task: String,
    stepCount: Int,
    taskStartTimeMs: Long,
    actionHistory: List<ActionLogEntry>,
    aiResponses: List<AIResponseLog>,
    errors: List<ErrorLogEntry>,
  ) {
    stateManager.setAIDecisionMode(AIDecisionMode.IDLE)
    val executionLogId = historyManager.saveExecutionLog(
      task,
      taskStartTimeMs,
      AutomationResultType.SUCCESS,
      stepCount,
      actionHistory,
      aiResponses,
      errors,
    )
    historyManager.saveTaskHistory(task, AutomationResultType.SUCCESS, stepCount, executionLogId)
    stateManager.complete(AutomationResult.Success)
  }

  private suspend fun completeWithFailure(
    task: String,
    stepCount: Int,
    reason: String,
    taskStartTimeMs: Long,
    actionHistory: List<ActionLogEntry>,
    aiResponses: List<AIResponseLog>,
    errors: List<ErrorLogEntry>,
  ) {
    val executionLogId = historyManager.saveExecutionLog(
      task,
      taskStartTimeMs,
      AutomationResultType.FAILURE,
      stepCount,
      actionHistory,
      aiResponses,
      errors,
    )
    historyManager.saveTaskHistory(task, AutomationResultType.FAILURE, stepCount, executionLogId)
    stateManager.complete(AutomationResult.Failure(reason))
  }

  private fun mapActionToAIActionType(action: Action): ActionType {
    return when (action) {
      is Action.Tap -> ActionType.TAP
      is Action.Swipe -> ActionType.SWIPE
      is Action.Input -> ActionType.INPUT
      is Action.Wait -> ActionType.WAIT
      is Action.LongPress -> ActionType.LONG_PRESS
      is Action.LaunchApp -> ActionType.LAUNCH_APP
    }
  }

  private fun mapActionToParams(action: Action): me.fleey.futon.data.ai.models.ActionParameters? {
    return when (action) {
      is Action.Tap -> me.fleey.futon.data.ai.models.ActionParameters(x = action.x, y = action.y)
      is Action.Swipe -> me.fleey.futon.data.ai.models.ActionParameters(
        x1 = action.startX, y1 = action.startY,
        x2 = action.endX, y2 = action.endY,
        duration = action.durationMs.toInt(),
      )

      is Action.Input -> me.fleey.futon.data.ai.models.ActionParameters(text = action.text)
      is Action.Wait -> me.fleey.futon.data.ai.models.ActionParameters(duration = action.durationMs.toInt())
      is Action.LongPress -> me.fleey.futon.data.ai.models.ActionParameters(
        x = action.x, y = action.y, duration = action.durationMs.toInt(),
      )

      is Action.LaunchApp -> me.fleey.futon.data.ai.models.ActionParameters(text = action.packageName)
    }
  }

  private fun mapAIResponseToAction(response: AIResponse): Action {
    val params = response.parameters
    return when (response.action) {
      ActionType.TAP, ActionType.TAP_COORDINATE -> Action.Tap(params?.x ?: 0, params?.y ?: 0)
      ActionType.LONG_PRESS -> Action.LongPress(
        x = params?.x ?: 0,
        y = params?.y ?: 0,
        durationMs = params?.duration?.toLong() ?: 500L,
      )

      ActionType.DOUBLE_TAP -> Action.Tap(params?.x ?: 0, params?.y ?: 0)
      ActionType.SWIPE -> Action.Swipe(
        startX = params?.x1 ?: 0,
        startY = params?.y1 ?: 0,
        endX = params?.x2 ?: 0,
        endY = params?.y2 ?: 0,
        durationMs = params?.duration?.toLong() ?: 300L,
      )

      ActionType.SCROLL -> {
        val direction = params?.direction ?: "down"
        val distance = params?.distance ?: 500
        val x = params?.x ?: 540
        val y = params?.y ?: 1200
        val (endX, endY) = when (direction.lowercase()) {
          "up" -> x to (y - distance)
          "down" -> x to (y + distance)
          "left" -> (x - distance) to y
          "right" -> (x + distance) to y
          else -> x to (y + distance)
        }
        Action.Swipe(x, y, endX, endY, 300L)
      }

      ActionType.PINCH -> Action.Tap(params?.x ?: 0, params?.y ?: 0)
      ActionType.INPUT -> Action.Input(params?.text ?: "")
      ActionType.WAIT -> Action.Wait(params?.duration?.toLong() ?: 1000L)
      ActionType.LAUNCH_APP -> Action.LaunchApp(params?.text ?: params?.packageName ?: "")
      ActionType.LAUNCH_ACTIVITY -> Action.LaunchApp(params?.packageName ?: "")
      ActionType.BACK,
      ActionType.HOME,
      ActionType.RECENTS,
      ActionType.NOTIFICATIONS,
      ActionType.QUICK_SETTINGS,
      ActionType.SCREENSHOT,
      ActionType.INTERVENE,
      ActionType.CALL,
      ActionType.COMPLETE,
      ActionType.ERROR,
        -> Action.Wait(0)
    }
  }

  /**
   * Resolves element_id from SOM response to actual screen coordinates.
   * When AI returns {"action":"tap","element_id":6}, we need to look up
   * element 6 in the SomAnnotation and get its center coordinates.
   */
  private fun resolveElementIdToCoordinates(
    response: AIResponse,
    somAnnotation: me.fleey.futon.domain.som.models.SomAnnotation?,
  ): AIResponse {
    val elementId = response.elementId ?: return response

    if (somAnnotation == null) {
      Log.w(TAG, "element_id=$elementId but no SomAnnotation available")
      return response
    }

    val element = somAnnotation.getElementById(elementId)
    if (element == null) {
      Log.w(TAG, "element_id=$elementId not found in SomAnnotation (${somAnnotation.elementCount} elements)")
      return response
    }

    Log.i(TAG, "Resolved element_id=$elementId to coordinates (${element.centerX}, ${element.centerY})")

    val resolvedParams = (response.parameters ?: me.fleey.futon.data.ai.models.ActionParameters()).copy(
      x = element.centerX,
      y = element.centerY,
    )

    return response.copy(
      parameters = resolvedParams,
      reasoning = "${response.reasoning ?: ""} [SOM: element[$elementId] at (${element.centerX},${element.centerY})]",
    )
  }

  companion object {
    private const val TAG = "AutomationOrchestrator"
  }
}
