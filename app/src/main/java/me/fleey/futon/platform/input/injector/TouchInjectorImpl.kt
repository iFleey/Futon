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
import me.fleey.futon.platform.input.models.InjectionMode
import me.fleey.futon.platform.input.models.TouchPoint
import org.koin.core.annotation.Single

/**
 * Stub implementation of TouchInjector for Root-Only architecture.
 * All input injection is handled by the native daemon via Binder IPC.
 */
@Single(binds = [TouchInjector::class])
class TouchInjectorImpl(
  @Suppress("UNUSED_PARAMETER") displayMetrics: Any? = null,
) : TouchInjector {

  override suspend fun initialize(mode: InjectionMode): GestureResult {
    return createUnavailableResult()
  }

  override fun getMode(): InjectionMode? = null

  override suspend fun isModeAvailable(mode: InjectionMode): Boolean = false

  override suspend fun getModeAvailability(): Map<InjectionMode, Boolean> {
    return mapOf(
      InjectionMode.ROOT_UINPUT to false,
    )
  }

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

  override suspend fun longPress(x: Int, y: Int, durationMs: Long): GestureResult {
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

  override fun getMaxTouchPoints(): Int = 1

  override fun getEstimatedLatencyMs(): Int = 0

  override fun close() {
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
