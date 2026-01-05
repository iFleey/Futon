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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface AsyncRequestHandler {
  suspend fun <T> executeAsync(
    requestId: Long,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    transform: (ByteArray) -> T,
  ): Result<T>

  fun resolveRequest(requestId: Long, result: ByteArray)
  fun cancelRequest(requestId: Long, error: DaemonError)
  fun cancelAllRequests(error: DaemonError)

  val timeoutErrors: SharedFlow<AsyncTimeoutError>

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
  }
}

data class AsyncTimeoutError(
  val requestId: Long,
  val error: DaemonError,
  val timestamp: Long = System.currentTimeMillis(),
)

class AsyncRequestManager : AsyncRequestHandler, Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
  private val requestIdGenerator = AtomicLong(0)

  private val _timeoutErrors = MutableSharedFlow<AsyncTimeoutError>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val timeoutErrors: SharedFlow<AsyncTimeoutError> = _timeoutErrors.asSharedFlow()

  private var cleanupJob = scope.launch {
    while (isActive) {
      delay(CLEANUP_INTERVAL_MS)
      cleanupExpiredRequests()
    }
  }

  fun generateRequestId(): Long = requestIdGenerator.incrementAndGet()

  override suspend fun <T> executeAsync(
    requestId: Long,
    timeoutMs: Long,
    transform: (ByteArray) -> T,
  ): Result<T> {
    val deferred = CompletableDeferred<ByteArray>()
    val pendingRequest = PendingRequest(
      requestId = requestId,
      deferred = deferred,
      createdAt = System.currentTimeMillis(),
      timeoutMs = timeoutMs,
    )

    mutex.withLock {
      pendingRequests[requestId] = pendingRequest
    }

    return try {
      val result = withTimeout(timeoutMs) {
        deferred.await()
      }
      Result.success(transform(result))
    } catch (e: TimeoutCancellationException) {
      handleTimeout(requestId)
      Result.failure(
        DaemonBinderException(
          DaemonError(
            code = ErrorCode.CONNECTION_TIMEOUT,
            message = "Async request $requestId timed out after ${timeoutMs}ms",
          ),
        ),
      )
    } catch (e: Exception) {
      mutex.withLock {
        pendingRequests.remove(requestId)
      }
      Result.failure(e)
    }
  }

  override fun resolveRequest(requestId: Long, result: ByteArray) {
    val pending = pendingRequests.remove(requestId)
    if (pending != null) {
      pending.deferred.complete(result)
      Log.d(TAG, "Resolved async request: $requestId")
    } else {
      Log.w(TAG, "No pending request found for requestId: $requestId")
    }
  }

  override fun cancelRequest(requestId: Long, error: DaemonError) {
    val pending = pendingRequests.remove(requestId)
    if (pending != null) {
      pending.deferred.completeExceptionally(DaemonBinderException(error))
      Log.d(TAG, "Cancelled async request: $requestId")
    }
  }

  override fun cancelAllRequests(error: DaemonError) {
    val exception = DaemonBinderException(error)
    val requests = pendingRequests.keys.toList()
    for (requestId in requests) {
      val pending = pendingRequests.remove(requestId)
      pending?.deferred?.completeExceptionally(exception)
    }
    Log.d(TAG, "Cancelled ${requests.size} pending requests")
  }

  private suspend fun handleTimeout(requestId: Long) {
    mutex.withLock {
      pendingRequests.remove(requestId)
    }

    val error = DaemonError(
      code = ErrorCode.CONNECTION_TIMEOUT,
      message = "Async request $requestId timed out",
    )

    _timeoutErrors.emit(
      AsyncTimeoutError(
        requestId = requestId,
        error = error,
      ),
    )

    Log.w(TAG, "Async request timed out: $requestId")
  }

  private suspend fun cleanupExpiredRequests() {
    val now = System.currentTimeMillis()
    val expiredIds = mutableListOf<Long>()

    for ((requestId, pending) in pendingRequests) {
      val elapsed = now - pending.createdAt
      if (elapsed > pending.timeoutMs + CLEANUP_GRACE_PERIOD_MS) {
        expiredIds.add(requestId)
      }
    }

    for (requestId in expiredIds) {
      val pending = pendingRequests.remove(requestId)
      if (pending != null) {
        val error = DaemonError(
          code = ErrorCode.CONNECTION_TIMEOUT,
          message = "Async request $requestId expired during cleanup",
        )
        pending.deferred.completeExceptionally(DaemonBinderException(error))
        _timeoutErrors.emit(AsyncTimeoutError(requestId, error))
        Log.w(TAG, "Cleaned up expired request: $requestId")
      }
    }
  }

  override fun close() {
    cleanupJob.cancel()
    cancelAllRequests(
      DaemonError(
        code = ErrorCode.CONNECTION_FAILED,
        message = "AsyncRequestManager closed",
      ),
    )
  }

  private data class PendingRequest(
    val requestId: Long,
    val deferred: CompletableDeferred<ByteArray>,
    val createdAt: Long,
    val timeoutMs: Long,
  )

  companion object {
    private const val TAG = "AsyncRequestManager"
    private const val CLEANUP_INTERVAL_MS = 10_000L
    private const val CLEANUP_GRACE_PERIOD_MS = 5_000L
  }
}
