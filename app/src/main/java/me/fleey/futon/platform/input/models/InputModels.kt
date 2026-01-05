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
package me.fleey.futon.platform.input.models

import kotlinx.serialization.Serializable

/**
 * Available input injection methods in priority order.
 */
enum class InputMethod {
  NATIVE_IOCTL,

  ANDROID_INPUT,

  SHELL_SENDEVENT
}

/**
 * Touch injection mode for the dual-mode TouchInjector.
 */
enum class InjectionMode {
  ROOT_UINPUT
}

@Serializable
data class TouchPoint(
  val id: Int,
  val x: Int,
  val y: Int,
  val pressure: Int = 50,
  val size: Int = 10,
)

sealed interface GestureResult {

  data object Success : GestureResult

  data class Failure(
    val code: GestureErrorCode,
    val message: String,
    val suggestion: String? = null,
  ) : GestureResult

  data object Timeout : GestureResult
}

enum class GestureErrorCode {
  DEVICE_NOT_FOUND,

  DEVICE_NOT_ACCESSIBLE,

  /** SELinux policy blocking /dev/input access */
  SELINUX_DENIED,

  ROOT_DENIED,

  /** Coordinates outside screen bounds */
  INVALID_COORDINATES,

  MULTI_TOUCH_NOT_SUPPORTED,

  SERVICE_NOT_AVAILABLE,

  UNKNOWN_ERROR
}

@Serializable
data class InputDeviceInfo(
  val path: String,
  val name: String,
  val isTouchscreen: Boolean,
  val supportsMultiTouch: Boolean,
  val mtProtocol: MTProtocol,
  val maxX: Int,
  val maxY: Int,
  val maxTouchPoints: Int,
)

enum class MTProtocol {

  PROTOCOL_A,

  PROTOCOL_B,

  SINGLE_TOUCH
}
