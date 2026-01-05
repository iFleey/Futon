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
package me.fleey.futon.platform.input.util

import me.fleey.futon.platform.input.models.MTProtocol
import me.fleey.futon.platform.input.models.TouchPoint

/**
 * Stub implementation of SendeventCommandGenerator for Root-Only architecture.
 * Sendevent commands are generated and executed by the native daemon which has
 * direct access to /dev/input devices.
 *
 */
class SendeventCommandGenerator {

  fun generateTapCommands(
    devicePath: String,
    x: Int,
    y: Int,
    protocol: MTProtocol = MTProtocol.PROTOCOL_B,
    pressure: Int = 50,
  ): List<String> = emptyList()

  fun generateSwipeCommands(
    devicePath: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long,
    protocol: MTProtocol = MTProtocol.PROTOCOL_B,
    pressure: Int = 50,
  ): List<String> = emptyList()

  fun generateMultiTouchCommands(
    devicePath: String,
    touches: List<TouchPoint>,
    protocol: MTProtocol = MTProtocol.PROTOCOL_B,
  ): List<String> = emptyList()

  fun generateReleaseCommands(
    devicePath: String,
    touchCount: Int = 1,
    protocol: MTProtocol = MTProtocol.PROTOCOL_B,
  ): List<String> = emptyList()

  fun batchCommands(commands: List<String>): String = ""

  fun createScript(commands: List<String>): String = ""
}
