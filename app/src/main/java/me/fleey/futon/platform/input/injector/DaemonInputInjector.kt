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
package me.fleey.futon.platform.input.injector

import me.fleey.futon.platform.input.models.GestureErrorCode
import me.fleey.futon.platform.input.models.GestureResult
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.TouchPoint

/**
 * Stub implementation of InputInjector for Root-Only architecture.
 * All input injection is handled by the native daemon via Binder IPC.
 */
class DaemonInputInjector(
  @Suppress("UNUSED_PARAMETER") context: Any? = null,
  @Suppress("UNUSED_PARAMETER") rootShell: Any? = null,
) : InputInjector {

  override val method: InputMethod = InputMethod.NATIVE_IOCTL

  override val estimatedLatencyMs: Int = 10

  override suspend fun isAvailable(): Boolean = false

  override suspend fun tap(x: Int, y: Int): GestureResult {
    return createUnavailableResult()
  }

  override suspend fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
  ): GestureResult {
    return createUnavailableResult()
  }

  override suspend fun multiTouch(touches: List<TouchPoint>, durationMs: Long): GestureResult {
    return createUnavailableResult()
  }

  override suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Long,
  ): GestureResult {
    return createUnavailableResult()
  }

  fun getDevicePath(): String? = null

  suspend fun getDebugInfo(): String = "Stub - use DaemonBinderClient for input injection"

  suspend fun tryDevice(devicePath: String): GestureResult = createUnavailableResult()

  suspend fun release() {
    // No-op for stub
  }

  private fun createUnavailableResult(): GestureResult.Failure {
    return GestureResult.Failure(
      code = GestureErrorCode.SERVICE_NOT_AVAILABLE,
      message = "Input injection is handled by daemon via Binder IPC in Root-Only mode",
      suggestion = "Use DaemonBinderClient for input injection",
    )
  }
}
