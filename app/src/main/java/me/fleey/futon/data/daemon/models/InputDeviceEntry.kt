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
package me.fleey.futon.data.daemon.models

import kotlinx.serialization.Serializable
import me.fleey.futon.platform.input.models.MTProtocol

/**
 * Information about an input device discovered by the daemon.
 * Used for user selection of touch input device.
 *
 * @property path Device path, e.g., "/dev/input/event3"
 * @property name Device name from kernel, e.g., "fts_ts"
 * @property isTouchscreen Whether this device has touchscreen capabilities
 * @property supportsMultiTouch Whether multi-touch is supported
 * @property mtProtocol Multi-touch protocol type
 * @property maxX Maximum X coordinate
 * @property maxY Maximum Y coordinate
 * @property maxTouchPoints Maximum simultaneous touch points
 * @property touchscreenProbability Probability score (0-100) that this is the primary touchscreen
 * @property probabilityReason Reason for the probability score
 */
@Serializable
data class InputDeviceEntry(
  val path: String,
  val name: String,
  val isTouchscreen: Boolean,
  val supportsMultiTouch: Boolean,
  val mtProtocol: MTProtocol,
  val maxX: Int,
  val maxY: Int,
  val maxTouchPoints: Int,
  val touchscreenProbability: Int,
  val probabilityReason: String,
) {
  /**
   * Display name combining device name and path.
   */
  val displayName: String
    get() = "$name ($path)"

  /**
   * Short display name for compact UI.
   * Includes event number suffix if name alone would be ambiguous.
   */
  val shortDisplayName: String
    get() {
      val baseName = name.ifEmpty { path.substringAfterLast('/') }
      val eventNum = path.substringAfterLast("event", "")
      return if (eventNum.isNotEmpty()) "$baseName (event$eventNum)" else baseName
    }

  /**
   * Resolution string for display.
   */
  val resolutionString: String
    get() = if (maxX > 0 && maxY > 0) "${maxX}x${maxY}" else "Unknown"

  /**
   * Whether this device is recommended (high probability touchscreen).
   */
  val isRecommended: Boolean
    get() = touchscreenProbability >= 70 && isTouchscreen

  companion object {
    /**
     * Auto-detect sentinel value for device path.
     */
    const val AUTO_DETECT_PATH = ""
  }
}

/**
 * Convert AIDL InputDeviceEntry to domain model.
 */
fun me.fleey.futon.InputDeviceEntry.toDomain(): InputDeviceEntry {
  return InputDeviceEntry(
    path = path,
    name = name,
    isTouchscreen = isTouchscreen,
    supportsMultiTouch = supportsMultiTouch,
    mtProtocol = when (mtProtocol) {
      0 -> MTProtocol.SINGLE_TOUCH
      1 -> MTProtocol.PROTOCOL_A
      2 -> MTProtocol.PROTOCOL_B
      else -> MTProtocol.SINGLE_TOUCH
    },
    maxX = maxX,
    maxY = maxY,
    maxTouchPoints = maxTouchPoints,
    touchscreenProbability = touchscreenProbability,
    probabilityReason = probabilityReason,
  )
}
