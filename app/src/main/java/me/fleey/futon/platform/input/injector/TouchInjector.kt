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

import me.fleey.futon.platform.input.models.GestureResult
import me.fleey.futon.platform.input.models.InjectionMode
import me.fleey.futon.platform.input.models.TouchPoint
import java.io.Closeable

/**
 * Root-only touch injector supporting Root (uinput) mode.
 */
interface TouchInjector : Closeable {

  suspend fun initialize(mode: InjectionMode): GestureResult

  fun getMode(): InjectionMode?

  suspend fun isModeAvailable(mode: InjectionMode): Boolean

  suspend fun getModeAvailability(): Map<InjectionMode, Boolean>

  suspend fun tap(x: Int, y: Int): GestureResult

  suspend fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
  ): GestureResult

  suspend fun longPress(x: Int, y: Int, durationMs: Long = 500): GestureResult

  suspend fun multiTouch(touches: List<TouchPoint>, durationMs: Long): GestureResult

  /**
   * Performs a pinch gesture (zoom in/out) centered at the specified point.
   *
   * @param centerX Center X coordinate of the pinch
   * @param centerY Center Y coordinate of the pinch
   * @param startDistance Initial distance between the two touch points
   * @param endDistance Final distance between the two touch points
   * @param durationMs Duration of the pinch gesture in milliseconds
   * @return GestureResult indicating success or failure
   */
  suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Long,
  ): GestureResult

  fun getMaxTouchPoints(): Int

  /**
   * Gets the estimated latency for the current mode.
   *
   * @return Estimated latency in milliseconds
   */
  fun getEstimatedLatencyMs(): Int
}
