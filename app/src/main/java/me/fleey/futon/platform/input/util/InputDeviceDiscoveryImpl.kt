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

import me.fleey.futon.platform.input.models.InputDeviceInfo
import me.fleey.futon.platform.input.models.MTProtocol
import org.koin.core.annotation.Single

/**
 * Stub implementation of InputDeviceDiscovery for Root-Only architecture.
 * Device discovery is handled by the native daemon which has direct access
 * to /dev/input devices.
 */
@Single(binds = [InputDeviceDiscovery::class])
class InputDeviceDiscoveryImpl(
  @Suppress("UNUSED_PARAMETER") rootShell: Any? = null,
) : InputDeviceDiscovery {

  override suspend fun discoverTouchDevice(): InputDeviceInfo? = null

  override suspend fun listAllDevices(): List<InputDeviceInfo> = emptyList()

  override suspend fun detectMTProtocol(devicePath: String): MTProtocol = MTProtocol.SINGLE_TOUCH
}
