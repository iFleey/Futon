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

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import org.koin.core.annotation.Single

/**
 * Input injection result from daemon operations.
 */
sealed interface InputInjectionResult {
  data object Success : InputInjectionResult

  data class Failure(
    val error: DaemonError,
    val retriesExhausted: Boolean = false,
  ) : InputInjectionResult
}

/**
 * Interface for daemon-based input injection.
 * All input operations are delegated to the native daemon via Binder IPC.
 */
interface DaemonInputInjector {
  /**
   * Current daemon connection state.
   */
  val daemonState: StateFlow<DaemonState>

  /**
   * Checks if the daemon is available for input injection.
   */
  fun isDaemonAvailable(): Boolean

  /**
   * Executes a tap gesture at the specified coordinates.
   * Retries up to 3 times with exponential backoff on failure.
   *
   * @param x X coordinate on screen (pixels)
   * @param y Y coordinate on screen (pixels)
   * @return InputInjectionResult indicating success or failure
   */
  suspend fun tap(x: Int, y: Int): InputInjectionResult

  /**
   * Executes a swipe gesture from start to end coordinates.
   * Retries up to 3 times with exponential backoff on failure.
   *
   * @param startX Starting X coordinate
   * @param startY Starting Y coordinate
   * @param endX Ending X coordinate
   * @param endY Ending Y coordinate
   * @param durationMs Duration of the swipe in milliseconds
   * @return InputInjectionResult indicating success or failure
   */
  suspend fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Int,
  ): InputInjectionResult

  /**
   * Executes a multi-touch gesture with multiple simultaneous touch points.
   * Retries up to 3 times with exponential backoff on failure.
   *
   * @param xs Array of X coordinates for each touch point
   * @param ys Array of Y coordinates for each touch point
   * @param actions Array of action codes for each touch point
   * @return InputInjectionResult indicating success or failure
   */
  suspend fun multiTouch(
    xs: IntArray,
    ys: IntArray,
    actions: IntArray,
  ): InputInjectionResult

  /**
   * Inputs text via the daemon's input injection.
   * Retries up to 3 times with exponential backoff on failure.
   *
   * @param text Text to input
   * @return InputInjectionResult indicating success or failure
   */
  suspend fun inputText(text: String): InputInjectionResult

  /**
   * Presses a key via the daemon's input injection.
   * Retries up to 3 times with exponential backoff on failure.
   *
   * @param keyCode Android KeyEvent key code
   * @return InputInjectionResult indicating success or failure
   */
  suspend fun pressKey(keyCode: Int): InputInjectionResult
}

/**
 * Implementation of DaemonInputInjector that delegates all input operations
 * to the native daemon via Binder IPC.
 */
@Single(binds = [DaemonInputInjector::class])
class DaemonInputInjectorImpl(
  private val binderClient: DaemonBinderClient,
) : DaemonInputInjector {

  override val daemonState: StateFlow<DaemonState>
    get() = binderClient.connectionState

  override fun isDaemonAvailable(): Boolean {
    return binderClient.isConnected() &&
      binderClient.connectionState.value is DaemonState.Ready
  }

  override suspend fun tap(x: Int, y: Int): InputInjectionResult {
    return executeWithRetry("tap($x, $y)") {
      binderClient.tap(x, y)
    }
  }

  override suspend fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Int,
  ): InputInjectionResult {
    return executeWithRetry("swipe($startX, $startY, $endX, $endY, $durationMs)") {
      binderClient.swipe(startX, startY, endX, endY, durationMs)
    }
  }

  override suspend fun multiTouch(
    xs: IntArray,
    ys: IntArray,
    actions: IntArray,
  ): InputInjectionResult {
    return executeWithRetry("multiTouch(${xs.size} points)") {
      binderClient.multiTouch(xs, ys, actions)
    }
  }

  override suspend fun inputText(text: String): InputInjectionResult {
    Log.d("DaemonInputInjector", "inputText called: text.length=${text.length}")
    return executeWithRetry("inputText(${text.length} chars)") {
      Log.d("DaemonInputInjector", "inputText: calling binderClient.inputText()")
      binderClient.inputText(text)
    }
  }

  override suspend fun pressKey(keyCode: Int): InputInjectionResult {
    return executeWithRetry("pressKey($keyCode)") {
      binderClient.pressKey(keyCode)
    }
  }

  /**
   * Executes an input operation with retry logic.
   * Retries up to MAX_RETRIES times with exponential backoff.
   *
   * @param operationName Name of the operation for logging
   * @param operation The suspend function to execute
   * @return InputInjectionResult indicating success or failure
   */
  private suspend fun executeWithRetry(
    operationName: String,
    operation: suspend () -> Result<Unit>,
  ): InputInjectionResult {
    val currentState = binderClient.connectionState.value
    val isConnected = binderClient.isConnected()

    if (!isDaemonAvailable()) {
      Log.w(
        "DaemonInputInjector",
        "Daemon not available: isConnected=$isConnected, state=$currentState, operation=$operationName",
      )
      return InputInjectionResult.Failure(
        error = DaemonError.connection(
          ErrorCode.SERVICE_NOT_FOUND,
          "Daemon not available (state=$currentState): $operationName",
        ),
        retriesExhausted = false,
      )
    }

    var lastError: DaemonError? = null
    var attempt = 0

    while (attempt < MAX_RETRIES) {
      val result = operation()

      result.fold(
        onSuccess = {
          return InputInjectionResult.Success
        },
        onFailure = { throwable ->
          lastError = when (throwable) {
            is DaemonBinderException -> throwable.error
            else -> DaemonError.runtime(
              ErrorCode.RUNTIME_INPUT_INJECTION_FAILED,
              "Input injection failed: ${throwable.message}",
              throwable,
            )
          }

          if (!shouldRetry(lastError)) {
            return InputInjectionResult.Failure(
              error = lastError,
              retriesExhausted = false,
            )
          }

          attempt++
          if (attempt < MAX_RETRIES) {
            val delayMs = calculateBackoffDelay(attempt)
            delay(delayMs)
          }
        },
      )
    }

    return InputInjectionResult.Failure(
      error = lastError ?: DaemonError.runtime(
        ErrorCode.RUNTIME_INPUT_INJECTION_FAILED,
        "Input injection failed after $MAX_RETRIES attempts: $operationName",
      ),
      retriesExhausted = true,
    )
  }

  /**
   * Determines if an error is recoverable and should be retried.
   */
  private fun shouldRetry(error: DaemonError?): Boolean {
    if (error == null) return false

    return when (error.code) {
      ErrorCode.BINDER_DIED,
      ErrorCode.CONNECTION_FAILED,
      ErrorCode.CONNECTION_TIMEOUT,
      ErrorCode.RUNTIME_INPUT_INJECTION_FAILED,
        -> true

      ErrorCode.SECURITY_UNAUTHORIZED,
      ErrorCode.SECURITY_UID_MISMATCH,
      ErrorCode.AUTH_SESSION_EXPIRED,
        -> false

      else -> error.recoverable
    }
  }

  /**
   * Calculates exponential backoff delay for retry attempts.
   * Delays: 100ms, 200ms, 400ms
   */
  private fun calculateBackoffDelay(attempt: Int): Long {
    return INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
  }

  companion object {
    private const val MAX_RETRIES = 3
    private const val INITIAL_BACKOFF_MS = 100L
  }
}
