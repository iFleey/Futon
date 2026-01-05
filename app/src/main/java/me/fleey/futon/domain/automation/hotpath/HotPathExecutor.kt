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
package me.fleey.futon.domain.automation.hotpath

import android.util.Log
import me.fleey.futon.config.AutomationConfig
import me.fleey.futon.data.daemon.DaemonInputInjector
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.routing.models.Action
import me.fleey.futon.data.trace.ActionLookupService
import me.fleey.futon.data.trace.TraceRecorder
import me.fleey.futon.data.trace.UIHashComputer
import me.fleey.futon.domain.automation.HotPathRegistry
import me.fleey.futon.domain.automation.execution.ActionExecutor
import org.koin.core.annotation.Single

sealed interface HotPathResult {
  data class Hit(
    val action: Action,
    val confidence: Float,
    val uiHash: String,
    val executionTimeMs: Long,
  ) : HotPathResult

  data object Miss : HotPathResult

  data class ExecutionFailed(
    val action: Action,
    val reason: String,
  ) : HotPathResult
}

interface HotPathExecutor {
  var consecutiveNoMatchFrames: Int
  val shouldTriggerAiFallback: Boolean

  suspend fun configureDaemon(): Boolean
  suspend fun tryExecute(
    detectedElements: List<DetectedElement>,
    imageWidth: Int,
    imageHeight: Int,
  ): HotPathResult

  suspend fun recordSuccess(uiHash: String, action: Action)
  fun recordFailure(action: Action)
  fun reset()
}

@Single(binds = [HotPathExecutor::class])
class HotPathExecutorImpl(
  private val hotPathRegistry: HotPathRegistry,
  private val daemonRepository: DaemonRepository,
  private val daemonInputInjector: DaemonInputInjector,
  private val actionExecutor: ActionExecutor,
  private val actionLookupService: ActionLookupService,
  private val uiHashComputer: UIHashComputer,
  private val traceRecorder: TraceRecorder,
) : HotPathExecutor {

  override var consecutiveNoMatchFrames: Int = 0

  private var aiCallbackThreshold = AutomationConfig.HotPath.DEFAULT_AI_FALLBACK_THRESHOLD

  override val shouldTriggerAiFallback: Boolean
    get() = consecutiveNoMatchFrames >= aiCallbackThreshold

  override suspend fun configureDaemon(): Boolean {
    val rules = hotPathRegistry.serializeToJson()
    if (rules.isEmpty() || rules == "[]") {
      Log.d(TAG, "No hot path rules to configure")
      return false
    }

    val result = daemonRepository.configureHotPath(rules)
    if (result.isFailure) {
      Log.e(TAG, "Failed to configure hot path: ${result.exceptionOrNull()?.message}")
      return false
    }

    Log.i(TAG, "Configured daemon with ${hotPathRegistry.ruleCount} hot path rules")
    return true
  }

  override suspend fun tryExecute(
    detectedElements: List<DetectedElement>,
    imageWidth: Int,
    imageHeight: Int,
  ): HotPathResult {
    if (detectedElements.isEmpty()) {
      consecutiveNoMatchFrames++
      return HotPathResult.Miss
    }

    // Compute UI hash from detected elements
    val perceptionResult = PerceptionResult(
      elements = detectedElements,
      imageWidth = imageWidth,
      imageHeight = imageHeight,
      captureLatencyMs = 0,
      detectionLatencyMs = 0,
      ocrLatencyMs = 0,
      totalLatencyMs = 0,
      activeDelegate = DelegateType.NONE,
      timestamp = System.currentTimeMillis(),
    )
    val uiHash = uiHashComputer.computeHash(perceptionResult)

    // Lookup cached action
    val cachedAction = actionLookupService.lookupActionByHash(uiHash)
    if (cachedAction == null || !cachedAction.isHighConfidence) {
      consecutiveNoMatchFrames++
      return HotPathResult.Miss
    }

    Log.i(
      TAG,
      "Hot path hit! Action: ${cachedAction.action::class.simpleName} " +
        "(confidence: ${cachedAction.confidence})",
    )

    val startTime = System.currentTimeMillis()

    // Execute via daemon if available, otherwise local
    val success = if (daemonInputInjector.isDaemonAvailable()) {
      actionExecutor.executeDaemonAction(cachedAction.action)
    } else {
      actionExecutor.executeHotPathAction(cachedAction.action)
    }

    val executionTimeMs = System.currentTimeMillis() - startTime

    return if (success) {
      consecutiveNoMatchFrames = 0
      HotPathResult.Hit(
        action = cachedAction.action,
        confidence = cachedAction.confidence,
        uiHash = uiHash,
        executionTimeMs = executionTimeMs,
      )
    } else {
      HotPathResult.ExecutionFailed(
        action = cachedAction.action,
        reason = "Execution failed",
      )
    }
  }

  override suspend fun recordSuccess(uiHash: String, action: Action) {
    traceRecorder.recordSuccessfulAction(uiHash, action)
    hotPathRegistry.incrementSuccessCount(action.toString())
  }

  override fun recordFailure(action: Action) {
    hotPathRegistry.incrementFailureCount(action.toString())
    consecutiveNoMatchFrames++
  }

  override fun reset() {
    consecutiveNoMatchFrames = 0
  }

  companion object {
    private const val TAG = "HotPathExecutor"
  }
}
