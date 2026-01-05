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

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import me.fleey.futon.data.perception.models.DetectionConfig
import me.fleey.futon.data.perception.models.DetectionError
import me.fleey.futon.data.perception.models.DetectionResult

/**
 * Stub implementation of UIDetector for Root-Only architecture.
 * All detection is handled by the native daemon via Binder IPC.
 * This stub returns success for initialization but delegates actual
 * detection operations to the daemon.
 */
class UIDetectorStub : UIDetector {

  private var _isReady: Boolean = false
  override val isReady: Boolean get() = _isReady

  private var _config: DetectionConfig = DetectionConfig.DEFAULT

  override suspend fun initialize(config: DetectionConfig): Boolean {
    _config = config
    _isReady = true
    return true
  }

  override suspend fun detect(
    bufferId: Long,
    width: Int,
    height: Int,
    minConfidence: Float?,
  ): DetectionResult {
    return DetectionResult.Failure(
      error = DetectionError.NOT_INITIALIZED,
      message = "UIDetector is handled by daemon in Root-Only mode",
    )
  }

  override suspend fun detectFromBuffer(
    buffer: HardwareBuffer,
    width: Int,
    height: Int,
    minConfidence: Float?,
  ): DetectionResult {
    return DetectionResult.Failure(
      error = DetectionError.NOT_INITIALIZED,
      message = "UIDetector is handled by daemon in Root-Only mode",
    )
  }

  override suspend fun detectFromBitmap(
    bitmap: Bitmap,
    minConfidence: Float?,
  ): DetectionResult {
    return DetectionResult.Failure(
      error = DetectionError.NOT_INITIALIZED,
      message = "UIDetector is handled by daemon in Root-Only mode",
    )
  }

  override fun getConfig(): DetectionConfig = _config

  override fun updateConfig(config: DetectionConfig) {
    _config = config
  }

  override fun close() {
    _isReady = false
  }
}
