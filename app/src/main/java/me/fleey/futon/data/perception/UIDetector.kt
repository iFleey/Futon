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
import me.fleey.futon.data.perception.models.DetectionResult
import java.io.Closeable

/**
 * UI element detector interface.
 */
interface UIDetector : Closeable {

  val isReady: Boolean

  suspend fun initialize(config: DetectionConfig = DetectionConfig.DEFAULT): Boolean

  /**
   * Detect UI elements in a captured frame using zero-copy buffer.
   *
   * @param bufferId Buffer ID from ZeroCopyCapture
   * @param width Image width
   * @param height Image height
   * @param minConfidence Minimum confidence threshold (overrides config)
   * @return DetectionResult with detected elements or error
   */
  suspend fun detect(
    bufferId: Long,
    width: Int,
    height: Int,
    minConfidence: Float? = null,
  ): DetectionResult

  /**
   * Detect UI elements in a HardwareBuffer.
   *
   * @param buffer HardwareBuffer containing the image
   * @param width Image width
   * @param height Image height
   * @param minConfidence Minimum confidence threshold (overrides config)
   * @return DetectionResult with detected elements or error
   */
  suspend fun detectFromBuffer(
    buffer: HardwareBuffer,
    width: Int,
    height: Int,
    minConfidence: Float? = null,
  ): DetectionResult

  /**
   * Detect UI elements in a Bitmap.
   *
   * @param bitmap Input bitmap
   * @param minConfidence Minimum confidence threshold (overrides config)
   * @return DetectionResult with detected elements or error
   */
  suspend fun detectFromBitmap(
    bitmap: Bitmap,
    minConfidence: Float? = null,
  ): DetectionResult

  fun getConfig(): DetectionConfig

  fun updateConfig(config: DetectionConfig)

  override fun close()
}
