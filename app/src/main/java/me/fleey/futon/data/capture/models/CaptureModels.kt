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
package me.fleey.futon.data.capture.models

import kotlinx.serialization.Serializable

/**
 * Available screenshot capture methods.
 * Root-Only architecture: ROOT_SCREENCAP is the primary method.
 */
enum class CaptureMethod {
  /** Root shell screencap command - primary capture method */
  ROOT_SCREENCAP,

  /** MediaProjection API (requires user permission) */
  MEDIA_PROJECTION
}

/**
 * Result of a screenshot capture operation.
 */
sealed interface CaptureResult {
  /**
   * Screenshot captured successfully.
   *
   * @property base64Image Base64-encoded image data
   * @property format Image format (default "jpeg")
   * @property width Image width in pixels
   * @property height Image height in pixels
   * @property wasSecureWindow Whether the captured window had FLAG_SECURE
   */
  @Serializable
  data class Success(
    val base64Image: String,
    val format: String = "jpeg",
    val width: Int,
    val height: Int,
    val wasSecureWindow: Boolean = false,
  ) : CaptureResult

  data class Failure(
    val code: CaptureErrorCode,
    val message: String,
  ) : CaptureResult

  /**
   * Screenshot capture blocked by privacy settings.
   *
   * @property packageName Package name of the secure window
   * @property reason Explanation of why capture was blocked
   */
  data class PrivacyBlocked(
    val packageName: String,
    val reason: String,
  ) : CaptureResult
}

/**
 * Error codes for screenshot capture failures.
 */
enum class CaptureErrorCode {
  /** Root access not available for screencap command */
  ROOT_NOT_AVAILABLE,

  /** Shell command execution failed */
  COMMAND_FAILED,

  /** Capture blocked by privacy settings */
  PRIVACY_BLOCKED,
  
  TIMEOUT,

  UNKNOWN_ERROR
}
