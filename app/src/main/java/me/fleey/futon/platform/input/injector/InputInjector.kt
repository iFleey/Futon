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
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.TouchPoint

/**
 * Interface for input injection across different execution methods.
 * Implementations include native JNI (lowest latency) and shell sendevent.
 */
interface InputInjector {

  val method: InputMethod

  val estimatedLatencyMs: Int

  suspend fun isAvailable(): Boolean

  suspend fun tap(x: Int, y: Int): GestureResult

  suspend fun swipe(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
  ): GestureResult

  suspend fun multiTouch(touches: List<TouchPoint>, durationMs: Long): GestureResult

  suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Long,
  ): GestureResult
}
