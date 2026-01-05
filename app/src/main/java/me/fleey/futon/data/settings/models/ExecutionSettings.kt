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
package me.fleey.futon.data.settings.models

import kotlinx.serialization.Serializable
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.platform.input.models.InputMethod

/**
 * User preferences for execution layer configuration.
 *
 * @property preferredInputMethod Preferred input injection method (null = auto-select)
 * @property preferredCaptureMethod Preferred screenshot capture method (null = auto-select)
 * @property enableFallback Whether to fallback to alternative methods on failure
 * @property privacyMode Privacy protection level for secure window capture
 * @property auditLogEnabled Whether to log capture attempts on secure windows
 * @property showCapabilityWarnings Whether to show warnings for unavailable methods
 */
@Serializable
data class ExecutionSettings(
  val preferredInputMethod: InputMethod? = null,
  val preferredCaptureMethod: CaptureMethod? = null,
  val enableFallback: Boolean = true,
  val privacyMode: PrivacyMode = PrivacyMode.STRICT,
  val auditLogEnabled: Boolean = true,
  val showCapabilityWarnings: Boolean = true,
) {
  companion object {
    /**
     * Default execution settings with auto-selection and strict privacy.
     */
    val DEFAULT = ExecutionSettings()
  }
}
