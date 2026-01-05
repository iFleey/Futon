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
package me.fleey.futon.platform.input.strategy

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.platform.input.injector.InputInjector
import me.fleey.futon.platform.input.models.GestureResult
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.MTProtocol

interface InputStrategyFactory {

  suspend fun detectCapabilities(): InputCapabilities

  suspend fun refreshCapabilities()

  suspend fun getAvailableMethods(): List<InputMethod>

  suspend fun selectBestMethod(): InputInjector

  suspend fun getInjector(method: InputMethod): InputInjector?

  /**
   * Sets the user's preferred input method.
   * If set, this method will be used when available, overriding auto-selection.
   *
   *
   * @param method The preferred method, or null for auto-selection
   */
  fun setPreferredMethod(method: InputMethod?)

  fun getPreferredMethod(): InputMethod?

  fun getCurrentMethod(): InputMethod?

  /**
   * Executes a gesture with automatic fallback on failure.
   * If the primary method fails and fallback is enabled, tries the next available method.
   *
   * @param action The gesture action to execute
   * @return GestureResult from the successful method or the last failure
   */
  suspend fun executeWithFallback(action: suspend (InputInjector) -> GestureResult): GestureResult

  fun setFallbackEnabled(enabled: Boolean)

  fun isFallbackEnabled(): Boolean

  fun observeCurrentMethod(): Flow<InputMethod?>

  fun observeCapabilities(): Flow<InputCapabilities>
}

/**
 * Describes the input injection capabilities detected on the device.
 * Root-Only architecture: Only root-based input methods are supported.
 *
 * @property nativeAvailable Whether native JNI input injection is available
 * @property nativeError Error message if native is unavailable
 * @property androidInputAvailable Whether Android input command is available
 * @property androidInputError Error message if Android input is unavailable
 * @property shellAvailable Whether shell-based sendevent is available
 * @property shellError Error message if shell is unavailable
 * @property detectedDevicePath Path to the discovered touchscreen device
 * @property mtProtocol Detected multi-touch protocol
 * @property maxTouchPoints Maximum simultaneous touch points supported
 * @property isDaemonBased Whether capabilities are sourced from daemon
 * @property timestamp When these capabilities were detected
 */
data class InputCapabilities(
  val nativeAvailable: Boolean = false,
  val nativeError: String? = null,
  val androidInputAvailable: Boolean = false,
  val androidInputError: String? = null,
  val shellAvailable: Boolean = false,
  val shellError: String? = null,
  val detectedDevicePath: String? = null,
  val mtProtocol: MTProtocol? = null,
  val maxTouchPoints: Int = 1,
  val isDaemonBased: Boolean = false,
  val timestamp: Long = System.currentTimeMillis(),
) {

  fun hasAnyMethod(): Boolean =
    nativeAvailable || androidInputAvailable || shellAvailable

  fun getAvailableMethods(): List<InputMethod> = buildList {
    if (nativeAvailable) add(InputMethod.NATIVE_IOCTL)
    if (androidInputAvailable) add(InputMethod.ANDROID_INPUT)
    if (shellAvailable) add(InputMethod.SHELL_SENDEVENT)
  }

  /**
   * Gets the best available method based on priority.
   * Priority: NATIVE_IOCTL > ANDROID_INPUT > SHELL_SENDEVENT
   */
  fun getBestMethod(): InputMethod? = when {
    nativeAvailable -> InputMethod.NATIVE_IOCTL
    androidInputAvailable -> InputMethod.ANDROID_INPUT
    shellAvailable -> InputMethod.SHELL_SENDEVENT
    else -> null
  }

  fun isValid(maxAgeMs: Long = CACHE_VALIDITY_MS): Boolean {
    return System.currentTimeMillis() - timestamp < maxAgeMs
  }

  companion object {

    const val CACHE_VALIDITY_MS = 5000L

    val EMPTY = InputCapabilities()
  }
}
