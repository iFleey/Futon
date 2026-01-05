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
package me.fleey.futon.data.perception.models

import android.hardware.HardwareBuffer

/**
 * Configuration for zero-copy screen capture.
 */
data class ZeroCopyCaptureConfig(
  val width: Int,
  val height: Int,
  val downscaleFactor: Int = 1,
  val maxBuffers: Int = 3,
) {
  val effectiveWidth: Int get() = width / downscaleFactor
  val effectiveHeight: Int get() = height / downscaleFactor

  init {
    require(width > 0) { "Width must be positive" }
    require(height > 0) { "Height must be positive" }
    require(downscaleFactor > 0) { "Downscale factor must be positive" }
    require(maxBuffers in 1..5) { "Max buffers must be between 1 and 5" }
  }
}

sealed interface ZeroCopyCaptureResult {
  data class Success(
    val bufferId: Long,
    val hardwareBuffer: HardwareBuffer?,
    val nativeHandle: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val latencyNs: Long,
  ) : ZeroCopyCaptureResult {
    val latencyMs: Float get() = latencyNs / 1_000_000f
  }

  data class Failure(
    val error: ZeroCopyCaptureError,
    val message: String,
  ) : ZeroCopyCaptureResult
}

enum class ZeroCopyCaptureError {
  NOT_INITIALIZED,
  PERMISSION_DENIED,
  BUFFER_EXHAUSTED,
  VIRTUAL_DISPLAY_FAILED,
  IMAGE_READER_FAILED,
  MEDIA_PROJECTION_STOPPED,
  CAPTURE_FAILED,
  TIMEOUT,
  UNKNOWN
}

enum class ZeroCopyCaptureState {
  UNINITIALIZED,
  INITIALIZING,
  READY,
  CAPTURING,
  PAUSED,
  ERROR,
  DESTROYED
}

data class ZeroCopyCaptureStats(
  val buffersInUse: Int,
  val totalBuffers: Int,
  val bufferMemoryBytes: Long,
  val captureCount: Long,
  val averageLatencyMs: Float,
) {
  val bufferMemoryMb: Float get() = bufferMemoryBytes / (1024f * 1024f)
}
