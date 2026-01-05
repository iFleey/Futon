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
package me.fleey.futon.data.capture

import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.capture.models.CaptureResult

/**
 * Interface for capturing screenshots using various methods.
 *
 * Implementations include:
 * - [RootScreenCapture]: Uses root shell screencap command
 * - DaemonScreenCapture: Uses native daemon for zero-copy capture
 *
 * The [ScreenCaptureStrategyFactory] selects the best available implementation
 * based on device capabilities and user preferences.
 */
interface ScreenCapture {
  val method: CaptureMethod

  /**
   * Check if this capture method is currently available.
   *
   * @return true if capture can be performed
   */
  suspend fun isAvailable(): Boolean

  /**
   * Capture the current screen.
   *
   * @param quality JPEG compression quality (0-100, default 80)
   * @return [CaptureResult] containing the captured image or error
   */
  suspend fun capture(quality: Int = 80): CaptureResult

  /**
   * Capture the current screen with privacy check.
   *
   * This method checks for secure windows (FLAG_SECURE) and respects
   * the user's privacy settings before capturing.
   *
   * @param privacyManager The privacy manager to check capture permissions
   * @param quality JPEG compression quality (0-100, default 80)
   * @return [CaptureResult] which may be [CaptureResult.PrivacyBlocked] if
   *         the current window is secure and privacy settings prevent capture
   */
  suspend fun captureWithPrivacyCheck(
    privacyManager: PrivacyManager,
    quality: Int = 80,
  ): CaptureResult
}
