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

import android.hardware.HardwareBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.ScreenshotResult
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

interface HardwareBufferManager : Closeable {
  val bufferState: StateFlow<BufferPoolState>
  val memoryPressureEvents: SharedFlow<BufferMemoryPressureEvent>

  suspend fun acquireBuffer(screenshotResult: ScreenshotResult): ManagedBuffer
  suspend fun releaseBuffer(bufferId: Int)
  suspend fun releaseAllBuffers()
  suspend fun handleMemoryPressure(bufferId: Int, timeoutMs: Int)

  fun getActiveBufferCount(): Int
  fun getReadBuffer(): ManagedBuffer?
  fun getWriteBuffer(): ManagedBuffer?
}

data class ManagedBuffer(
  val bufferId: Int,
  val buffer: HardwareBuffer,
  val timestampNs: Long,
  val width: Int,
  val height: Int,
  val acquiredAt: Long = System.currentTimeMillis(),
) {
  val ageMs: Long get() = System.currentTimeMillis() - acquiredAt
}

data class BufferPoolState(
  val activeBuffers: Int,
  val maxBuffers: Int,
  val readBufferId: Int?,
  val writeBufferId: Int?,
  val lastAcquireTimestamp: Long?,
) {
  val hasAvailableSlots: Boolean get() = activeBuffers < maxBuffers
  val utilizationPercent: Float get() = if (maxBuffers > 0) activeBuffers.toFloat() / maxBuffers else 0f
}

data class BufferMemoryPressureEvent(
  val bufferId: Int,
  val timeoutMs: Int,
  val timestamp: Long = System.currentTimeMillis(),
)

class HardwareBufferManagerImpl(
  private val binderClient: DaemonBinderClient,
  private val maxBuffers: Int = DEFAULT_MAX_BUFFERS,
) : HardwareBufferManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  private val activeBuffers = ConcurrentHashMap<Int, ManagedBuffer>()

  private var readBufferId: Int? = null
  private var writeBufferId: Int? = null

  private val _bufferState = MutableStateFlow(
    BufferPoolState(
      activeBuffers = 0,
      maxBuffers = maxBuffers,
      readBufferId = null,
      writeBufferId = null,
      lastAcquireTimestamp = null,
    ),
  )
  override val bufferState: StateFlow<BufferPoolState> = _bufferState.asStateFlow()

  private val _memoryPressureEvents = MutableSharedFlow<BufferMemoryPressureEvent>()
  override val memoryPressureEvents: SharedFlow<BufferMemoryPressureEvent> =
    _memoryPressureEvents.asSharedFlow()

  init {
    scope.launch {
      binderClient.bufferReleaseRequests.collect { request ->
        handleMemoryPressure(request.bufferId, request.timeoutMs)
      }
    }
  }

  override suspend fun acquireBuffer(screenshotResult: ScreenshotResult): ManagedBuffer =
    mutex.withLock {
      val bufferId = screenshotResult.bufferId
      val buffer = screenshotResult.buffer

      if (activeBuffers.size >= maxBuffers) {
        releaseOldestBuffer()
      }

      val managedBuffer = ManagedBuffer(
        bufferId = bufferId,
        buffer = buffer,
        timestampNs = screenshotResult.timestampNs,
        width = screenshotResult.width,
        height = screenshotResult.height,
      )

      activeBuffers[bufferId] = managedBuffer
      binderClient.trackBuffer(bufferId, buffer)

      updateDoubleBuffering(bufferId)
      updateState()

      managedBuffer
    }

  override suspend fun releaseBuffer(bufferId: Int) = mutex.withLock {
    releaseBufferInternal(bufferId)
  }

  private suspend fun releaseBufferInternal(bufferId: Int) {
    val managedBuffer = activeBuffers.remove(bufferId)
    if (managedBuffer != null) {
      binderClient.releaseTrackedBuffer(bufferId)

      try {
        binderClient.releaseScreenshot(bufferId)
      } catch (_: Exception) {
      }

      if (readBufferId == bufferId) {
        readBufferId = null
      }
      if (writeBufferId == bufferId) {
        writeBufferId = null
      }

      updateState()
    }
  }

  override suspend fun releaseAllBuffers() = mutex.withLock {
    val bufferIds = activeBuffers.keys.toList()
    for (bufferId in bufferIds) {
      releaseBufferInternal(bufferId)
    }

    binderClient.releaseAllTrackedBuffers()

    readBufferId = null
    writeBufferId = null
    updateState()
  }

  override suspend fun handleMemoryPressure(bufferId: Int, timeoutMs: Int) {
    _memoryPressureEvents.emit(BufferMemoryPressureEvent(bufferId, timeoutMs))

    mutex.withLock {
      if (activeBuffers.containsKey(bufferId)) {
        releaseBufferInternal(bufferId)
      }
    }
  }

  override fun getActiveBufferCount(): Int = activeBuffers.size

  override fun getReadBuffer(): ManagedBuffer? {
    return readBufferId?.let { activeBuffers[it] }
  }

  override fun getWriteBuffer(): ManagedBuffer? {
    return writeBufferId?.let { activeBuffers[it] }
  }

  override fun close() {
    scope.launch {
      releaseAllBuffers()
    }
  }

  private fun updateDoubleBuffering(newBufferId: Int) {
    val previousWrite = writeBufferId

    writeBufferId = newBufferId

    if (previousWrite != null && previousWrite != newBufferId) {
      readBufferId = previousWrite
    }
  }

  private suspend fun releaseOldestBuffer() {
    val oldest = activeBuffers.values
      .filter { it.bufferId != writeBufferId }
      .minByOrNull { it.acquiredAt }

    if (oldest != null) {
      releaseBufferInternal(oldest.bufferId)
    }
  }

  private fun updateState() {
    _bufferState.value = BufferPoolState(
      activeBuffers = activeBuffers.size,
      maxBuffers = maxBuffers,
      readBufferId = readBufferId,
      writeBufferId = writeBufferId,
      lastAcquireTimestamp = activeBuffers.values.maxOfOrNull { it.acquiredAt },
    )
  }

  companion object {
    private const val DEFAULT_MAX_BUFFERS = 3
  }
}

class BufferManagerException(
  val error: DaemonError,
  cause: Throwable? = null,
) : Exception(error.message, cause) {
  companion object {
    fun bufferExhausted(): BufferManagerException = BufferManagerException(
      DaemonError.runtime(
        ErrorCode.RUNTIME_BUFFER_EXHAUSTED,
        "Buffer pool exhausted",
      ),
    )

    fun bufferNotFound(bufferId: Int): BufferManagerException = BufferManagerException(
      DaemonError.runtime(
        ErrorCode.RUNTIME_BUFFER_EXHAUSTED,
        "Buffer not found: $bufferId",
      ),
    )
  }
}
