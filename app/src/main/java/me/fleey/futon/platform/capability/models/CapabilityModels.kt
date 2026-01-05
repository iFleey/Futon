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
package me.fleey.futon.platform.capability.models

import kotlinx.serialization.Serializable
import me.fleey.futon.data.daemon.models.CapabilityFlags
import me.fleey.futon.platform.root.RootType

/**
 * Comprehensive device capabilities for execution layer.
 *
 * @property rootStatus Root access status and type
 * @property seLinuxStatus SELinux mode and policy status
 * @property inputDeviceAccess Input device access status
 * @property daemonCapabilities Raw daemon capability flags bitmask
 * @property timestamp Unix timestamp when capabilities were detected
 */
@Serializable
data class DeviceCapabilities(
  val rootStatus: RootStatus,
  val seLinuxStatus: SELinuxStatus,
  val inputDeviceAccess: InputDeviceAccess,
  val daemonCapabilities: Int = 0,
  val timestamp: Long,
) {
  fun hasScreenCapture(): Boolean =
    CapabilityFlags.hasScreenCapture(daemonCapabilities)

  fun hasInputInjection(): Boolean =
    CapabilityFlags.hasInputInjection(daemonCapabilities)

  fun hasObjectDetection(): Boolean =
    CapabilityFlags.hasObjectDetection(daemonCapabilities)

  fun hasOcr(): Boolean =
    CapabilityFlags.hasOcr(daemonCapabilities)

  fun hasHotPath(): Boolean =
    CapabilityFlags.hasHotPath(daemonCapabilities)

  fun hasDebugStream(): Boolean =
    CapabilityFlags.hasDebugStream(daemonCapabilities)
}

/**
 * Root access status information.
 *
 * @property isAvailable Whether root access is available
 * @property rootType Type of root solution (KSU, Magisk, etc.)
 * @property version Version string of the root solution (if available)
 */
@Serializable
data class RootStatus(
  val isAvailable: Boolean,
  val rootType: RootType,
  val version: String?,
)

/**
 * SELinux status and policy information.
 *
 * @property mode Current SELinux mode
 * @property inputAccessAllowed Whether SELinux allows /dev/input access
 * @property suggestedPolicy Supolicy command to fix access if blocked
 */
@Serializable
data class SELinuxStatus(
  val mode: SELinuxMode,
  val inputAccessAllowed: Boolean,
  val suggestedPolicy: String?,
)

/**
 * SELinux enforcement mode.
 */
enum class SELinuxMode {
  /** SELinux is enforcing policies */
  ENFORCING,

  /** SELinux is logging but not enforcing */
  PERMISSIVE,

  /** SELinux is disabled */
  DISABLED,

  /** SELinux status could not be determined */
  UNKNOWN
}

/**
 * Input device access status.
 *
 * @property canAccessDevInput Whether /dev/input/ is accessible
 * @property touchDevicePath Path to touchscreen device (e.g., "/dev/input/event2")
 * @property maxTouchPoints Maximum number of simultaneous touch points supported
 * @property error Error message if access failed
 */
@Serializable
data class InputDeviceAccess(
  val canAccessDevInput: Boolean,
  val touchDevicePath: String?,
  val maxTouchPoints: Int = 1,
  val error: String?,
)
