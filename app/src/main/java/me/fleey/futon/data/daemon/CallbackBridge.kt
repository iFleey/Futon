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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.fleey.futon.DaemonStatus
import me.fleey.futon.IBufferReleaseCallback
import me.fleey.futon.IStatusCallback
import me.fleey.futon.data.daemon.models.AutomationEvent
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.perception.models.DetectedElement
import org.koin.core.annotation.Single
import java.io.Closeable

interface CallbackBridgeProvider {
  val status: StateFlow<DaemonStatus?>
  val detectionResults: SharedFlow<List<DetectedElement>>
  val errors: SharedFlow<DaemonError>
  val automationEvents: SharedFlow<AutomationEvent>
  val memoryPressureEvents: SharedFlow<MemoryPressureEvent>
  val bufferReleaseRequests: SharedFlow<BufferReleaseRequest>

  val statusCallback: IStatusCallback
  val bufferReleaseCallback: IBufferReleaseCallback

  fun setAutomationCompleteListener(listener: AutomationCompleteListener?)
  fun setBufferReleaseHandler(handler: BufferReleaseHandler?)

  suspend fun <T> executeAsyncRequest(
    requestId: Long,
    timeoutMs: Long = AsyncRequestHandler.DEFAULT_TIMEOUT_MS,
    transform: (ByteArray) -> T,
  ): Result<T>

  fun generateRequestId(): Long
  fun cancelAllPendingRequests(error: DaemonError)
}

@Single(binds = [CallbackBridge::class, CallbackBridgeProvider::class])
class CallbackBridge : CallbackBridgeProvider, Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val daemonStatusCallback = DaemonStatusCallback()
  private val daemonBufferReleaseCallback = BufferReleaseCallback()
  private val asyncRequestManager = AsyncRequestManager()

  private val _status = MutableStateFlow<DaemonStatus?>(null)
  override val status: StateFlow<DaemonStatus?> = _status.asStateFlow()

  private val _detectionResults = MutableSharedFlow<List<DetectedElement>>(
    replay = 1,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val detectionResults: SharedFlow<List<DetectedElement>> =
    _detectionResults.asSharedFlow()

  private val _errors = MutableSharedFlow<DaemonError>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val errors: SharedFlow<DaemonError> = _errors.asSharedFlow()

  private val _automationEvents = MutableSharedFlow<AutomationEvent>(
    extraBufferCapacity = 32,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val automationEvents: SharedFlow<AutomationEvent> = _automationEvents.asSharedFlow()

  private val _memoryPressureEvents = MutableSharedFlow<MemoryPressureEvent>(
    extraBufferCapacity = 4,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val memoryPressureEvents: SharedFlow<MemoryPressureEvent> =
    _memoryPressureEvents.asSharedFlow()

  private val _bufferReleaseRequests = MutableSharedFlow<BufferReleaseRequest>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val bufferReleaseRequests: SharedFlow<BufferReleaseRequest> =
    _bufferReleaseRequests.asSharedFlow()

  override val statusCallback: IStatusCallback get() = daemonStatusCallback
  override val bufferReleaseCallback: IBufferReleaseCallback get() = daemonBufferReleaseCallback

  init {
    setupFlowBridges()
  }

  private fun setupFlowBridges() {
    daemonStatusCallback.status
      .onEach { status ->
        _status.value = status
      }
      .launchIn(scope)

    daemonStatusCallback.detectionResults
      .conflate()
      .onEach { results ->
        _detectionResults.emit(results)
      }
      .launchIn(scope)

    daemonStatusCallback.errors
      .onEach { error ->
        _errors.emit(error)
      }
      .launchIn(scope)

    daemonStatusCallback.loopDetectedEvents
      .onEach { event ->
        _automationEvents.emit(event)
      }
      .launchIn(scope)

    daemonStatusCallback.memoryPressureEvents
      .onEach { event ->
        _memoryPressureEvents.emit(event)
      }
      .launchIn(scope)

    daemonStatusCallback.asyncResults
      .onEach { asyncResult ->
        asyncRequestManager.resolveRequest(asyncResult.requestId, asyncResult.result)
      }
      .launchIn(scope)

    daemonBufferReleaseCallback.releaseRequests
      .onEach { request ->
        _bufferReleaseRequests.emit(request)
      }
      .launchIn(scope)

    asyncRequestManager.timeoutErrors
      .onEach { timeoutError ->
        _errors.emit(timeoutError.error)
      }
      .launchIn(scope)
  }

  override fun setAutomationCompleteListener(listener: AutomationCompleteListener?) {
    val wrappedListener = if (listener != null) {
      object : AutomationCompleteListener {
        override fun onAutomationComplete(success: Boolean, message: String?) {
          listener.onAutomationComplete(success, message)

          scope.launchSafely {
            val event = if (success) {
              AutomationEvent.Completed(
                success = true,
                message = message,
                totalSteps = 0,
                hotPathHits = 0,
                aiFallbacks = 0,
              )
            } else {
              AutomationEvent.Failed(
                error = DaemonError(
                  code = ErrorCode.AUTOMATION_AI_FALLBACK_FAILED,
                  message = message ?: "Automation failed",
                ),
                stepAtFailure = null,
              )
            }
            _automationEvents.emit(event)
          }
        }

        override fun onHotPathNoMatch(consecutiveFrames: Int) {
          listener.onHotPathNoMatch(consecutiveFrames)
        }
      }
    } else {
      null
    }
    daemonStatusCallback.setAutomationCompleteListener(wrappedListener)
  }

  override fun setBufferReleaseHandler(handler: BufferReleaseHandler?) {
    daemonBufferReleaseCallback.setReleaseHandler(handler)
  }

  override suspend fun <T> executeAsyncRequest(
    requestId: Long,
    timeoutMs: Long,
    transform: (ByteArray) -> T,
  ): Result<T> {
    return asyncRequestManager.executeAsync(requestId, timeoutMs, transform)
  }

  override fun generateRequestId(): Long {
    return asyncRequestManager.generateRequestId()
  }

  override fun cancelAllPendingRequests(error: DaemonError) {
    asyncRequestManager.cancelAllRequests(error)
  }

  override fun close() {
    Log.d(TAG, "Closing CallbackBridge")

    cancelAllPendingRequests(
      DaemonError(
        code = ErrorCode.CONNECTION_FAILED,
        message = "CallbackBridge closed",
      ),
    )

    daemonStatusCallback.close()
    daemonBufferReleaseCallback.close()
    asyncRequestManager.close()

    scope.cancel()
  }

  private fun CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch {
      try {
        block()
      } catch (e: Exception) {
        Log.e(TAG, "Error in coroutine", e)
      }
    }
  }

  companion object {
    private const val TAG = "CallbackBridge"
  }
}
