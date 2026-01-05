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
import me.fleey.futon.data.perception.models.OCRConfig
import me.fleey.futon.data.perception.models.OCROperationResult
import me.fleey.futon.data.perception.models.TextScript
import me.fleey.futon.domain.perception.models.UIBounds
import java.io.Closeable

/**
 * OCR engine interface for text recognition using Google ML Kit.
 */
interface OCREngine : Closeable {

  /**
   * Initialize the OCR engine with specified scripts.
   *
   * @param scripts Set of text scripts to support
   * @return Result indicating success or failure
   */
  suspend fun initialize(scripts: Set<TextScript> = TextScript.DEFAULT): Result<Unit>

  /**
   * Recognize text in a bitmap image.
   *
   * @param bitmap The image to process
   * @param region Optional region to crop before recognition
   * @return OCR result with recognized text and structure
   */
  suspend fun recognize(
    bitmap: Bitmap,
    region: UIBounds? = null,
  ): OCROperationResult

  /**
   * Recognize text in the full image without cropping.
   *
   * @param bitmap The image to process
   * @return OCR result with recognized text and structure
   */
  suspend fun recognizeFullImage(bitmap: Bitmap): OCROperationResult

  /**
   * Check if a specific script is supported and initialized.
   *
   * @param script The script to check
   * @return true if the script is supported
   */
  fun isScriptSupported(script: TextScript): Boolean

  /**
   * Get the set of currently supported scripts.
   *
   * @return Set of initialized scripts
   */
  fun getSupportedScripts(): Set<TextScript>

  /**
   * Check if the engine is initialized and ready.
   *
   * @return true if ready for recognition
   */
  fun isInitialized(): Boolean

  /**
   * Get the current configuration.
   *
   * @return Current OCR configuration
   */
  fun getConfig(): OCRConfig
}
