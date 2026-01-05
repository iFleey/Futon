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
package me.fleey.futon.data.privacy.models

import kotlinx.serialization.Serializable

/**
 * User-selected privacy protection level for secure window capture.
 * Default is STRICT for maximum privacy protection.
 */
enum class PrivacyMode {
  /** Never capture secure windows - maximum privacy */
  STRICT,

  /** Ask user each time a secure window is detected */
  CONSENT,

  /** Always allow capture - user explicitly enabled */
  TRUSTED
}

/**
 * Audit log entry for capture attempts on secure windows.
 *
 * @property id Unique identifier for the entry
 * @property timestamp Unix timestamp of the capture attempt
 * @property packageName Package name of the app being captured
 * @property wasSecure Whether the window had FLAG_SECURE
 * @property wasAllowed Whether the capture was allowed
 * @property privacyMode Privacy mode at time of capture
 * @property reason Explanation of the capture decision
 */
@Serializable
data class CaptureAuditEntry(
  val id: Long,
  val timestamp: Long,
  val packageName: String,
  val wasSecure: Boolean,
  val wasAllowed: Boolean,
  val privacyMode: PrivacyMode,
  val reason: String,
)

/**
 * Status of secure window detection for the current foreground app.
 *
 * @property packageName Package name of the foreground app (null if unknown)
 * @property isSecure Whether the window has FLAG_SECURE set
 * @property detectionMethod How the secure status was determined
 * @property confidence Confidence level of detection (0.0 to 1.0)
 */
@Serializable
data class SecureWindowStatus(
  val packageName: String?,
  val isSecure: Boolean,
  val detectionMethod: DetectionMethod,
  val confidence: Float,
)

/**
 * Method used to detect secure window status.
 */
enum class DetectionMethod {
  DUMPSYS_WINDOW,

  KNOWN_PACKAGE,

  DETECTION_FAILED,

  NOT_CHECKED
}

sealed interface CaptureDecision {
  data object Allowed : CaptureDecision

  data object Blocked : CaptureDecision

  data class NeedsConsent(val packageName: String) : CaptureDecision
}
