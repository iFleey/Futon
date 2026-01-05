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
package me.fleey.futon.data.capture

import me.fleey.futon.data.privacy.models.CaptureAuditEntry
import me.fleey.futon.data.privacy.models.CaptureDecision
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.data.privacy.models.SecureWindowStatus

/**
 * Interface for managing privacy settings and secure window detection.
 *
 * The PrivacyManager controls whether screenshots can be captured when
 * secure windows (FLAG_SECURE) are detected, based on user preferences.
 *
 * Privacy modes:
 * - STRICT: Never capture secure windows (default)
 * - CONSENT: Ask user each time
 * - TRUSTED: Always allow capture
 */
interface PrivacyManager {
  /**
   * Get the current privacy mode setting.
   *
   * @return Current [PrivacyMode]
   */
  suspend fun getPrivacyMode(): PrivacyMode

  /**
   * Set the privacy mode.
   *
   * @param mode The new [PrivacyMode] to apply
   */
  suspend fun setPrivacyMode(mode: PrivacyMode)

  /**
   * Check the secure window status of the current foreground app.
   *
   * @return [SecureWindowStatus] with detection results
   */
  suspend fun checkSecureWindow(): SecureWindowStatus

  /**
   * Quick check if the current window is secure.
   *
   * @return true if the current window has FLAG_SECURE
   */
  suspend fun isCurrentWindowSecure(): Boolean

  /**
   * Determine if capture should be allowed based on current window
   * and privacy settings.
   *
   * @return [CaptureDecision] indicating whether capture is allowed
   */
  suspend fun shouldAllowCapture(): CaptureDecision

  /**
   * Log a capture attempt for audit purposes.
   *
   * @param packageName Package name of the app being captured
   * @param wasSecure Whether the window had FLAG_SECURE
   * @param wasAllowed Whether the capture was allowed
   * @param reason Explanation of the capture decision
   */
  suspend fun logCaptureAttempt(
    packageName: String,
    wasSecure: Boolean,
    wasAllowed: Boolean,
    reason: String,
  )

  /**
   * Get the audit log of capture attempts.
   *
   * @param limit Maximum number of entries to return (default 100)
   * @return List of [CaptureAuditEntry] sorted by timestamp descending
   */
  suspend fun getAuditLog(limit: Int = 100): List<CaptureAuditEntry>

  /**
   * Clear all audit log entries.
   */
  suspend fun clearAuditLog()

  /**
   * Get the set of known sensitive package names.
   *
   * These are apps like banking apps and password managers that
   * are treated as secure even if FLAG_SECURE detection fails.
   *
   * @return Set of package names
   */
  fun getKnownSensitivePackages(): Set<String>
}
