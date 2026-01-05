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
import me.fleey.futon.data.perception.models.ZeroCopyCaptureResult
import me.fleey.futon.data.perception.models.ZeroCopyCaptureState
import me.fleey.futon.data.perception.models.ZeroCopyCaptureStats
import java.io.Closeable

/**
 * Interface for zero-copy screen capture using AHardwareBuffer.
 */
interface ZeroCopyCapture : Closeable {

  val state: ZeroCopyCaptureState

  val config: ZeroCopyCaptureConfig?

  suspend fun initialize(config: ZeroCopyCaptureConfig): Boolean

  /**
   * Set the MediaProjection for VirtualDisplay capture.
   * Must be called after initialize() and before acquireBuffer().
   *
   * @param projection MediaProjection from user permission grant
   * @return true if VirtualDisplay started successfully
   */
  fun setMediaProjection(projection: MediaProjection): Boolean

  fun hasActiveProjection(): Boolean

  /**
   * Acquire a buffer from the pool for capture.
   * The buffer is marked as in-use until released.
   *
   * @return Capture result with buffer or error
   */
  suspend fun acquireBuffer(): ZeroCopyCaptureResult

  /**
   * Get HardwareBuffer from buffer ID.
   *
   * @param bufferId Buffer ID from acquire
   * @return HardwareBuffer or null if not found
   */
  fun getHardwareBuffer(bufferId: Long): HardwareBuffer?

  /**
   * Get native handle for buffer ID (for zero-copy LiteRT input).
   *
   * @param bufferId Buffer ID
   * @return Native handle or 0 if not found
   */
  fun getNativeHandle(bufferId: Long): Long

  /**
   * Release a buffer back to the pool.
   *
   * @param bufferId Buffer ID to release
   */
  fun releaseBuffer(bufferId: Long)

  fun getStats(): ZeroCopyCaptureStats

  fun pause()

  suspend fun resume(): Boolean

  fun isReady(): Boolean = state == ZeroCopyCaptureState.READY

  fun destroy()
}
