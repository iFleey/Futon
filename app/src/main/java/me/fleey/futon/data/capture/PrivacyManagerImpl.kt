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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.fleey.futon.data.privacy.SecureWindowDetector
import me.fleey.futon.data.privacy.models.CaptureAuditEntry
import me.fleey.futon.data.privacy.models.CaptureDecision
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.data.privacy.models.SecureWindowStatus

/**
 * Implementation of [PrivacyManager] for managing privacy settings and secure window detection.
 *
 * This implementation:
 * - Persists privacy mode settings using DataStore
 * - Delegates secure window detection to [SecureWindowDetector]
 * - Maintains an audit log of capture attempts on secure windows
 * - Provides a list of known sensitive packages
 *
 * @param dataStore DataStore for persisting settings and audit log
 * @param secureWindowDetector Detector for secure window status
 */
import org.koin.core.annotation.Single

@Single(binds = [PrivacyManager::class])
class PrivacyManagerImpl(
  private val dataStore: DataStore<Preferences>,
  private val secureWindowDetector: SecureWindowDetector,
) : PrivacyManager {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val auditLogMutex = Mutex()
  private var nextAuditId = 1L

  override suspend fun getPrivacyMode(): PrivacyMode {
    return dataStore.data.map { prefs ->
      val modeStr = prefs[PRIVACY_MODE_KEY]
      modeStr?.let {
        try {
          PrivacyMode.valueOf(it)
        } catch (e: Exception) {
          PrivacyMode.STRICT
        }
      } ?: PrivacyMode.STRICT
    }.first()
  }

  override suspend fun setPrivacyMode(mode: PrivacyMode) {
    dataStore.edit { prefs ->
      prefs[PRIVACY_MODE_KEY] = mode.name
    }
  }

  override suspend fun checkSecureWindow(): SecureWindowStatus {
    return secureWindowDetector.detectSecureWindow()
  }

  override suspend fun isCurrentWindowSecure(): Boolean {
    val status = checkSecureWindow()
    return status.isSecure
  }

  override suspend fun shouldAllowCapture(): CaptureDecision {
    val windowStatus = checkSecureWindow()
    val privacyMode = getPrivacyMode()

    if (!windowStatus.isSecure) {
      return CaptureDecision.Allowed
    }

    return when (privacyMode) {
      PrivacyMode.STRICT -> CaptureDecision.Blocked

      PrivacyMode.CONSENT -> {
        val packageName = windowStatus.packageName ?: "unknown"
        CaptureDecision.NeedsConsent(packageName)
      }

      PrivacyMode.TRUSTED -> CaptureDecision.Allowed
    }
  }

  override suspend fun logCaptureAttempt(
    packageName: String,
    wasSecure: Boolean,
    wasAllowed: Boolean,
    reason: String,
  ) {
    // Only log if audit logging is enabled and window was secure
    if (!wasSecure) return

    val privacyMode = getPrivacyMode()
    val entry = CaptureAuditEntry(
      id = generateAuditId(),
      timestamp = System.currentTimeMillis(),
      packageName = packageName,
      wasSecure = wasSecure,
      wasAllowed = wasAllowed,
      privacyMode = privacyMode,
      reason = reason,
    )

    auditLogMutex.withLock {
      val currentLog = getAuditLogInternal()
      val updatedLog = listOf(entry) + currentLog.take(MAX_AUDIT_LOG_SIZE - 1)
      saveAuditLog(updatedLog)
    }
  }

  override suspend fun getAuditLog(limit: Int): List<CaptureAuditEntry> {
    return auditLogMutex.withLock {
      getAuditLogInternal().take(limit)
    }
  }

  override suspend fun clearAuditLog() {
    auditLogMutex.withLock {
      dataStore.edit { prefs ->
        prefs.remove(AUDIT_LOG_KEY)
      }
    }
  }

  override fun getKnownSensitivePackages(): Set<String> {
    return KNOWN_SENSITIVE_PACKAGES
  }

  /**
   * Get the audit log from DataStore.
   */
  private suspend fun getAuditLogInternal(): List<CaptureAuditEntry> {
    return dataStore.data.map { prefs ->
      val logJson = prefs[AUDIT_LOG_KEY]
      if (logJson != null) {
        try {
          json.decodeFromString<List<CaptureAuditEntry>>(logJson)
        } catch (e: Exception) {
          emptyList()
        }
      } else {
        emptyList()
      }
    }.first()
  }

  /**
   * Save the audit log to DataStore.
   */
  private suspend fun saveAuditLog(log: List<CaptureAuditEntry>) {
    dataStore.edit { prefs ->
      prefs[AUDIT_LOG_KEY] = json.encodeToString(log)
    }
  }

  /**
   * Generate a unique audit entry ID.
   */
  private fun generateAuditId(): Long {
    return nextAuditId++
  }

  companion object {
    private val PRIVACY_MODE_KEY = stringPreferencesKey("privacy_mode")
    private val AUDIT_LOG_KEY = stringPreferencesKey("capture_audit_log")

    private const val MAX_AUDIT_LOG_SIZE = 1000

    /**
     * Known sensitive package prefixes for banking apps, password managers, etc.
     */
    val KNOWN_SENSITIVE_PACKAGES = setOf(
      // Banking apps (common prefixes)
      "com.chase",
      "com.bankofamerica",
      "com.wellsfargo",
      "com.citi",
      "com.usbank",
      "com.capitalone",
      "com.ally",
      "com.schwab",
      "com.fidelity",
      "com.vanguard",
      "com.tdameritrade",
      "com.etrade",
      "com.robinhood",
      "com.coinbase",
      "com.binance",
      "com.paypal",
      "com.venmo",
      "com.squareup.cash",
      "com.zellepay",

      // Password managers
      "com.lastpass",
      "com.agilebits.onepassword",
      "com.dashlane",
      "com.bitwarden",
      "com.keepersecurity",
      "com.enpass",
      "org.keepass",
      "keepass2android",

      // Authentication apps
      "com.google.android.apps.authenticator2",
      "com.authy.authy",
      "com.microsoft.msa.authenticator",
      "com.duosecurity.duomobile",
      "org.fedorahosted.freeotp",

      // Secure messaging
      "org.thoughtcrime.securesms", // Signal
      "com.whatsapp",
      "org.telegram.messenger",
      "com.wire",

      // Enterprise/Work apps
      "com.microsoft.intune",
      "com.airwatch",
      "com.mobileiron",

      // DRM/Streaming (often use FLAG_SECURE)
      "com.netflix",
      "com.amazon.avod",
      "com.disney.disneyplus",
      "com.hbo.hbonow",
      "com.hulu.plus",

      // System security
      "com.android.settings",
      "com.samsung.android.settings",
    )
  }
}
