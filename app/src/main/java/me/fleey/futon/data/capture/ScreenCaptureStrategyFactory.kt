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

/**
 * Factory for selecting and managing screenshot capture strategies.
 * Root-Only architecture: Only root-based capture methods are supported.
 *
 * Priority order (when no user preference):
 * 1. ROOT_SCREENCAP - Primary capture method via root shell
 * 2. MEDIA_PROJECTION - Requires user permission (not yet implemented)
 */
interface ScreenCaptureStrategyFactory {
  /**
   * Detect all available capture capabilities on this device.
   *
   * @return [CaptureCapabilities] with availability status for each method
   */
  suspend fun detectCapabilities(): CaptureCapabilities

  /**
   * Get list of currently available capture methods.
   *
   * @return List of [CaptureMethod] that can be used
   */
  suspend fun getAvailableMethods(): List<CaptureMethod>

  /**
   * Select the best available capture method based on capabilities
   * and user preferences.
   *
   * @return The selected [ScreenCapture] implementation
   * @throws IllegalStateException if no capture method is available
   */
  suspend fun selectBestMethod(): ScreenCapture

  /**
   * Get a specific capture implementation by method.
   *
   * @param method The [CaptureMethod] to get
   * @return The [ScreenCapture] implementation, or null if not available
   */
  suspend fun getCapture(method: CaptureMethod): ScreenCapture?

  /**
   * Set the user's preferred capture method.
   *
   * @param method The preferred [CaptureMethod], or null for automatic selection
   */
  fun setPreferredMethod(method: CaptureMethod?)

  /**
   * Get the currently active capture method.
   *
   * @return The current [CaptureMethod], or null if none selected
   */
  fun getCurrentMethod(): CaptureMethod?
}

/**
 * Detected capabilities for screenshot capture methods.
 * Root-Only architecture: Only root-based methods are supported.
 *
 * @property rootAvailable Whether root screencap is available
 * @property rootError Error message if root is not available
 * @property mediaProjectionAvailable Whether media projection is available (future)
 */
data class CaptureCapabilities(
  val rootAvailable: Boolean,
  val rootError: String?,
  val mediaProjectionAvailable: Boolean = false,
)
