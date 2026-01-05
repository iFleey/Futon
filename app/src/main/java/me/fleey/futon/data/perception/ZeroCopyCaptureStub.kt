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
package me.fleey.futon.data.perception

import android.hardware.HardwareBuffer
import android.media.projection.MediaProjection
import me.fleey.futon.data.perception.models.ZeroCopyCaptureConfig
import me.fleey.futon.data.perception.models.ZeroCopyCaptureError
import me.fleey.futon.data.perception.models.ZeroCopyCaptureResult
import me.fleey.futon.data.perception.models.ZeroCopyCaptureState
import me.fleey.futon.data.perception.models.ZeroCopyCaptureStats

/**
 * Stub implementation of ZeroCopyCapture for Root-Only architecture.
 * All capture is handled by the native daemon via Binder IPC.
 * This stub returns success for initialization but delegates actual
 * capture operations to the daemon.
 */
class ZeroCopyCaptureStub : ZeroCopyCapture {

  private var _state: ZeroCopyCaptureState = ZeroCopyCaptureState.UNINITIALIZED
  override val state: ZeroCopyCaptureState get() = _state

  private var _config: ZeroCopyCaptureConfig? = null
  override val config: ZeroCopyCaptureConfig? get() = _config

  override suspend fun initialize(config: ZeroCopyCaptureConfig): Boolean {
    _config = config
    _state = ZeroCopyCaptureState.READY
    return true
  }

  override fun setMediaProjection(projection: MediaProjection): Boolean = true

  override fun hasActiveProjection(): Boolean = true

  override suspend fun acquireBuffer(): ZeroCopyCaptureResult {
    return ZeroCopyCaptureResult.Failure(
      error = ZeroCopyCaptureError.NOT_INITIALIZED,
      message = "ZeroCopyCapture is handled by daemon in Root-Only mode",
    )
  }

  override fun getHardwareBuffer(bufferId: Long): HardwareBuffer? = null

  override fun getNativeHandle(bufferId: Long): Long = 0L

  override fun releaseBuffer(bufferId: Long) {}

  override fun getStats(): ZeroCopyCaptureStats {
    return ZeroCopyCaptureStats(
      buffersInUse = 0,
      totalBuffers = 0,
      bufferMemoryBytes = 0L,
      captureCount = 0L,
      averageLatencyMs = 0f,
    )
  }

  override fun pause() {
    _state = ZeroCopyCaptureState.PAUSED
  }

  override suspend fun resume(): Boolean {
    _state = ZeroCopyCaptureState.READY
    return true
  }

  override fun destroy() {
    _state = ZeroCopyCaptureState.DESTROYED
  }

  override fun close() {
    destroy()
  }
}
