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
package me.fleey.futon.platform.capability

import me.fleey.futon.platform.capability.models.CapabilityQueryResult
import me.fleey.futon.platform.capability.models.DeviceCapabilities
import me.fleey.futon.platform.capability.models.InputDeviceAccess
import me.fleey.futon.platform.capability.models.RootStatus
import me.fleey.futon.platform.capability.models.SELinuxStatus

/**
 * Interface for detecting device capabilities for the execution layer.
 */
interface CapabilityDetector {

  suspend fun detectAll(): DeviceCapabilities

  suspend fun detectAllWithStatus(): CapabilityQueryResult

  suspend fun refresh()

  fun getCached(): DeviceCapabilities?

  suspend fun checkRootAccess(): RootStatus

  suspend fun checkSELinuxStatus(): SELinuxStatus

  suspend fun checkInputDeviceAccess(): InputDeviceAccess
}
