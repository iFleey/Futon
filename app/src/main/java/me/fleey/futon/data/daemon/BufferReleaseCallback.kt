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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import me.fleey.futon.IBufferReleaseCallback
import java.io.Closeable

interface BufferReleaseHandler {
  suspend fun onBufferReleaseRequested(bufferId: Int, timeoutMs: Int)
}

interface BufferReleaseCallbackProvider {
  val releaseRequests: SharedFlow<BufferReleaseRequest>
  fun setReleaseHandler(handler: BufferReleaseHandler?)
}

class BufferReleaseCallback : IBufferReleaseCallback.Stub(),
  BufferReleaseCallbackProvider,
  Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _releaseRequests = MutableSharedFlow<BufferReleaseRequest>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val releaseRequests: SharedFlow<BufferReleaseRequest> =
    _releaseRequests.asSharedFlow()

  @Volatile
  private var releaseHandler: BufferReleaseHandler? = null

  override fun setReleaseHandler(handler: BufferReleaseHandler?) {
    releaseHandler = handler
  }

  override fun onBufferReleaseRequested(bufferId: Int, timeoutMs: Int) {
    scope.launch {
      Log.d(TAG, "Buffer release requested: bufferId=$bufferId, timeoutMs=$timeoutMs")

      val request = BufferReleaseRequest(
        bufferId = bufferId,
        timeoutMs = timeoutMs,
      )
      _releaseRequests.emit(request)

      try {
        releaseHandler?.onBufferReleaseRequested(bufferId, timeoutMs)
      } catch (e: Exception) {
        Log.e(TAG, "Error handling buffer release request", e)
      }
    }
  }

  override fun close() {
    releaseHandler = null
  }

  companion object {
    private const val TAG = "BufferReleaseCallback"
  }
}
