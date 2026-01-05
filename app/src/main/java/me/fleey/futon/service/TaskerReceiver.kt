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
package me.fleey.futon.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.fleey.futon.domain.automation.AutomationEngine
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.service.gateway.TokenManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * BroadcastReceiver for Tasker integration.
 * Allows external apps (like Tasker) to trigger automation tasks.
 *
 * Intent action: me.fleey.futon.ACTION_EXECUTE
 * Required extras:
 *   - auth_token: String - Authentication token
 *   - task_id: String (optional) - Task identifier
 *   - params: String (optional) - JSON parameters
 *
 * Result codes:
 *   - RESULT_OK: Task completed successfully
 *   - RESULT_CANCELED: Task failed or was cancelled
 *   - RESULT_FIRST_USER: Authentication failed
 *   - RESULT_FIRST_USER + 1: Invalid parameters
 */
class TaskerReceiver : BroadcastReceiver(), KoinComponent {

  companion object {
    private const val TAG = "TaskerReceiver"

    const val ACTION_EXECUTE = "me.fleey.futon.ACTION_EXECUTE"
    const val EXTRA_AUTH_TOKEN = "auth_token"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_PARAMS = "params"
    const val EXTRA_TASK_DESCRIPTION = "task_description"

    const val RESULT_AUTH_FAILED = Activity.RESULT_FIRST_USER
    const val RESULT_INVALID_PARAMS = Activity.RESULT_FIRST_USER + 1
    const val RESULT_ENGINE_ERROR = Activity.RESULT_FIRST_USER + 2
  }

  private val tokenManager: TokenManager by inject()
  private val automationEngine: AutomationEngine by inject()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_EXECUTE) {
      Log.w(TAG, "Unknown action: ${intent.action}")
      return
    }

    Log.i(TAG, "Received Tasker execute request")

    val token = intent.getStringExtra(EXTRA_AUTH_TOKEN)
    if (token == null || !tokenManager.validateTaskerToken(token)) {
      Log.w(TAG, "Authentication failed")
      resultCode = RESULT_AUTH_FAILED
      resultData = "Authentication failed"
      abortBroadcast()
      return
    }

    val taskId = intent.getStringExtra(EXTRA_TASK_ID)
    val params = intent.getStringExtra(EXTRA_PARAMS)
    val taskDescription = intent.getStringExtra(EXTRA_TASK_DESCRIPTION)

    // Build task description from available data
    val finalTaskDescription = buildTaskDescription(taskId, params, taskDescription)
    if (finalTaskDescription == null) {
      Log.w(TAG, "No valid task description provided")
      resultCode = RESULT_INVALID_PARAMS
      resultData = "No task description provided"
      return
    }

    Log.i(TAG, "Executing task: $finalTaskDescription")

    // Use goAsync() for long-running operations
    val pendingResult = goAsync()

    scope.launch {
      try {
        val result = automationEngine.startTask(finalTaskDescription)

        when (result) {
          is AutomationResult.Success -> {
            pendingResult.resultCode = Activity.RESULT_OK
            pendingResult.resultData = "Task completed successfully"
          }

          is AutomationResult.Failure -> {
            pendingResult.resultCode = Activity.RESULT_CANCELED
            pendingResult.resultData = result.reason
          }

          is AutomationResult.Timeout -> {
            pendingResult.resultCode = Activity.RESULT_CANCELED
            pendingResult.resultData = "Task timed out"
          }

          is AutomationResult.Cancelled -> {
            pendingResult.resultCode = Activity.RESULT_CANCELED
            pendingResult.resultData = "Task was cancelled"
          }
        }

        Log.i(TAG, "Task result: ${pendingResult.resultData}")
      } catch (e: Exception) {
        Log.e(TAG, "Task execution error", e)
        pendingResult.resultCode = RESULT_ENGINE_ERROR
        pendingResult.resultData = e.message ?: "Unknown error"
      } finally {
        pendingResult.finish()
      }
    }
  }

  private fun buildTaskDescription(
    taskId: String?,
    params: String?,
    taskDescription: String?,
  ): String? {
    // Priority: explicit task description > params JSON > task ID
    return when {
      !taskDescription.isNullOrBlank() -> taskDescription
      !params.isNullOrBlank() -> params
      !taskId.isNullOrBlank() -> "Execute task: $taskId"
      else -> null
    }
  }
}
