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
package me.fleey.futon.data.daemon

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import me.fleey.futon.DaemonStatus
import me.fleey.futon.FutonConfig
import me.fleey.futon.SystemStatus
import me.fleey.futon.data.daemon.models.AutomationEvent
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.InputDeviceEntry
import me.fleey.futon.data.daemon.models.ReconciliationState
import me.fleey.futon.data.perception.models.DetectedElement
import java.io.Closeable

interface DaemonRepository : Closeable {
  val daemonState: StateFlow<DaemonState>
  val status: StateFlow<DaemonStatus?>
  val errors: SharedFlow<DaemonError>
  val automationEvents: SharedFlow<AutomationEvent>
  val reconciliationState: StateFlow<ReconciliationState>
  val detectionResults: SharedFlow<List<DetectedElement>>

  suspend fun connect(): Result<Unit>
  suspend fun disconnect()
  fun isConnected(): Boolean

  suspend fun configure(config: FutonConfig): Result<Unit>
  suspend fun configureHotPath(jsonRules: String): Result<Unit>

  suspend fun getSystemStatus(): Result<SystemStatus>

  /**
   * List all input devices with touchscreen probability scores.
   * Devices are sorted by probability (highest first).
   */
  suspend fun listInputDevices(): Result<List<InputDeviceEntry>>

  suspend fun getScreenshot(): Result<ScreenshotData>
  suspend fun releaseScreenshot(bufferId: Int): Result<Unit>
  suspend fun requestPerception(): Result<List<DetectedElement>>

  suspend fun tap(x: Int, y: Int): Result<Unit>
  suspend fun longPress(x: Int, y: Int, durationMs: Int = 500): Result<Unit>
  suspend fun doubleTap(x: Int, y: Int): Result<Unit>
  suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit>
  suspend fun scroll(x: Int, y: Int, direction: ScrollDirection, distance: Int): Result<Unit>
  suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Int,
  ): Result<Unit>

  suspend fun multiTouch(xs: IntArray, ys: IntArray, actions: IntArray): Result<Unit>
  suspend fun inputText(text: String): Result<Unit>
  suspend fun pressKey(keyCode: Int): Result<Unit>

  suspend fun pressBack(): Result<Unit>
  suspend fun pressHome(): Result<Unit>
  suspend fun pressRecents(): Result<Unit>
  suspend fun openNotifications(): Result<Unit>
  suspend fun openQuickSettings(): Result<Unit>
  suspend fun launchApp(packageName: String): Result<Unit>
  suspend fun launchActivity(packageName: String, activityName: String): Result<Unit>

  suspend fun wait(durationMs: Int): Result<Unit>
  suspend fun saveScreenshot(filePath: String): Result<Unit>

  /**
   * Request user intervention when automation cannot proceed.
   * This will notify the user via notification/overlay that manual action is required.
   * @param reason Description of why intervention is needed
   * @param actionHint Suggested action for the user (optional, can be empty)
   */
  suspend fun requestIntervention(reason: String, actionHint: String = ""): Result<Unit>

  /**
   * Execute a built-in command with arguments.
   * Supports extensible command system for automation DSL.
   *
   * @param command Command name
   * @param args Arguments map (will be serialized to JSON)
   * @return CallResult with success status and optional data
   */
  suspend fun call(command: String, args: Map<String, Any> = emptyMap()): Result<CallResult>

  /**
   * Launch an app by package name or app name.
   * If appNameOrPackage is a package name, launches directly.
   * Otherwise, uses AppDiscovery to find the app by name/alias.
   */
  suspend fun launchAppSmart(appNameOrPackage: String): Result<Unit>

  suspend fun startHotPath(): Result<Unit>
  suspend fun stopAutomation(): Result<Unit>
  suspend fun executeTask(taskJson: String): Result<Long>

  /**
   * Reload models from the model directory.
   * Called after app deploys new model files.
   * @return true if models loaded successfully
   */
  suspend fun reloadModels(): Result<Boolean>

  /**
   * Get current model status from daemon.
   * @return JSON string with model loading status
   */
  suspend fun getModelStatus(): Result<String>

  fun setAutomationCompleteListener(listener: AutomationCompleteListener?)
  fun getLastKnownConfig(): FutonConfig?
  fun getLastKnownHotPathRules(): String?
}

data class ScreenshotData(
  val bufferId: Int,
  val buffer: android.hardware.HardwareBuffer,
  val timestampNs: Long,
  val width: Int,
  val height: Int,
)

enum class ScrollDirection(val value: Int) {
  UP(0),
  DOWN(1),
  LEFT(2),
  RIGHT(3)
}

/**
 * Result of a call() command execution.
 */
@Serializable
data class CallResult(
  val success: Boolean,
  val error: String? = null,
  val data: Map<String, String> = emptyMap(),
  val note: String? = null,
) {
  val output: String? get() = data["output"]
  val value: String? get() = data["value"]
  val exitCode: Int? get() = data["exitCode"]?.toIntOrNull()
}
