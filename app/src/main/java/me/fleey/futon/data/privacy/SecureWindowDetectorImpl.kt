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
package me.fleey.futon.data.privacy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.data.privacy.models.DetectionMethod
import me.fleey.futon.data.privacy.models.SecureWindowStatus
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult

/**
 * Implementation of [SecureWindowDetector] using root shell commands.
 *
 * This implementation:
 * - Parses `dumpsys window` output to detect FLAG_SECURE
 * - Detects the foreground app package name
 * - Caches results per foreground app for performance
 * - Falls back to known sensitive package matching when dumpsys fails
 *
 * @param rootShell Root shell for executing privileged commands
 * @param knownSensitivePackages Set of package names known to be sensitive
 */
import org.koin.core.annotation.Single

@Single(binds = [SecureWindowDetector::class])
class SecureWindowDetectorImpl(
  private val rootShell: RootShell,
  private val knownSensitivePackages: Set<String> = DEFAULT_SENSITIVE_PACKAGES,
) : SecureWindowDetector {

  private val cacheMutex = Mutex()
  private var cachedStatus: SecureWindowStatus? = null
  private var cachedPackage: String? = null
  private var cacheTimestamp: Long = 0L

  override suspend fun detectSecureWindow(): SecureWindowStatus {
    if (!rootShell.isRootAvailable()) {
      return SecureWindowStatus(
        packageName = null,
        isSecure = true,
        detectionMethod = DetectionMethod.DETECTION_FAILED,
        confidence = 0.0f,
      )
    }

    val currentPackage = getForegroundPackage()

    cacheMutex.withLock {
      val cached = cachedStatus
      if (cached != null &&
        cachedPackage == currentPackage &&
        System.currentTimeMillis() - cacheTimestamp < CACHE_VALIDITY_MS
      ) {
        return cached
      }
    }

    // Try dumpsys window detection first
    val dumpsysResult = detectViaDumpsys(currentPackage)
    if (dumpsysResult != null) {
      updateCache(dumpsysResult, currentPackage)
      return dumpsysResult
    }

    // Fallback to known package matching
    val knownPackageResult = detectViaKnownPackages(currentPackage)
    updateCache(knownPackageResult, currentPackage)
    return knownPackageResult
  }

  override suspend fun getForegroundPackage(): String? {
    val result = rootShell.execute(CMD_GET_FOREGROUND_PACKAGE)

    return when (result) {
      is ShellResult.Success -> {
        parseForegroundPackage(result.output)
      }

      else -> null
    }
  }

  override fun invalidateCache() {
    cachedStatus = null
    cachedPackage = null
    cacheTimestamp = 0L
  }

  override suspend fun isAvailable(): Boolean {
    return rootShell.isRootAvailable()
  }

  /**
   * Detect secure window status by parsing dumpsys window output.
   */
  private suspend fun detectViaDumpsys(packageName: String?): SecureWindowStatus? {
    return when (val result = rootShell.execute(CMD_DUMPSYS_WINDOW)) {
      is ShellResult.Success -> {
        val isSecure = parseSecureFlag(result.output)
        SecureWindowStatus(
          packageName = packageName,
          isSecure = isSecure,
          detectionMethod = DetectionMethod.DUMPSYS_WINDOW,
          confidence = 1.0f,
        )
      }

      else -> null
    }
  }

  private fun detectViaKnownPackages(packageName: String?): SecureWindowStatus {
    if (packageName == null) {
      return SecureWindowStatus(
        packageName = null,
        isSecure = true, // Assume secure when unknown
        detectionMethod = DetectionMethod.DETECTION_FAILED,
        confidence = 0.0f,
      )
    }

    val isKnownSensitive = knownSensitivePackages.any { pattern ->
      packageName.startsWith(pattern) || packageName == pattern
    }

    return SecureWindowStatus(
      packageName = packageName,
      isSecure = isKnownSensitive,
      detectionMethod = DetectionMethod.KNOWN_PACKAGE,
      confidence = if (isKnownSensitive) 0.8f else 0.5f,
    )
  }

  /**
   * Parse FLAG_SECURE from dumpsys window output.
   *
   * Looks for patterns like:
   * - "mFlags=FLAG_SECURE" or "flags=...FLAG_SECURE..."
   * - "mSystemUiVisibility=...FLAG_SECURE..."
   * - Hex flag value 0x2000 (FLAG_SECURE = 8192)
   */
  private fun parseSecureFlag(output: String): Boolean {
    val focusedWindowSection = extractFocusedWindowSection(output)

    if (focusedWindowSection.contains("FLAG_SECURE", ignoreCase = true)) {
      return true
    }

    // Check for hex flag value (FLAG_SECURE = 0x2000 = 8192)
    val flagsMatch = PATTERN_FLAGS.find(focusedWindowSection)
    if (flagsMatch != null) {
      val flagsValue = flagsMatch.groupValues[1]
      try {
        val flags = if (flagsValue.startsWith("0x", ignoreCase = true)) {
          flagsValue.substring(2).toLong(16)
        } else {
          flagsValue.toLong()
        }
        // FLAG_SECURE = 0x2000 = 8192
        if (flags and FLAG_SECURE_VALUE != 0L) {
          return true
        }
      } catch (e: NumberFormatException) {
        // Ignore parsing errors
      }
    }

    return false
  }

  private fun extractFocusedWindowSection(output: String): String {
    val lines = output.lines()
    val focusedIndex = lines.indexOfFirst {
      it.contains("mCurrentFocus") || it.contains("mFocusedWindow")
    }

    if (focusedIndex == -1) {
      // If no focused window marker, search the entire output
      return output
    }

    val startIndex = (focusedIndex - 5).coerceAtLeast(0)
    val endIndex = (focusedIndex + 20).coerceAtMost(lines.size)

    return lines.subList(startIndex, endIndex).joinToString("\n")
  }

  private fun parseForegroundPackage(output: String): String? {
    // Try to match "mCurrentFocus=Window{...packageName/...}"
    val currentFocusMatch = PATTERN_CURRENT_FOCUS.find(output)
    if (currentFocusMatch != null) {
      return currentFocusMatch.groupValues[1]
    }

    // Try to match "mFocusedApp=...packageName/..."
    val focusedAppMatch = PATTERN_FOCUSED_APP.find(output)
    if (focusedAppMatch != null) {
      return focusedAppMatch.groupValues[1]
    }

    val activityMatch = PATTERN_RESUMED_ACTIVITY.find(output)
    if (activityMatch != null) {
      return activityMatch.groupValues[1]
    }

    return null
  }

  private suspend fun updateCache(status: SecureWindowStatus, packageName: String?) {
    cacheMutex.withLock {
      cachedStatus = status
      cachedPackage = packageName
      cacheTimestamp = System.currentTimeMillis()
    }
  }

  companion object {
    private const val CACHE_VALIDITY_MS = 1000L // 1 second cache

    // Shell commands
    private const val CMD_DUMPSYS_WINDOW = "dumpsys window windows"
    private const val CMD_GET_FOREGROUND_PACKAGE =
      "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'"

    private const val FLAG_SECURE_VALUE = 0x2000L

    private val PATTERN_FLAGS = Regex("""mFlags=(?:0x)?([0-9a-fA-F]+)""")
    private val PATTERN_CURRENT_FOCUS = Regex("""mCurrentFocus=Window\{[^}]*\s+([a-zA-Z0-9_.]+)/""")
    private val PATTERN_FOCUSED_APP = Regex("""mFocusedApp=.*?([a-zA-Z0-9_.]+)/""")
    private val PATTERN_RESUMED_ACTIVITY = Regex("""mResumedActivity.*?([a-zA-Z0-9_.]+)/""")

    val DEFAULT_SENSITIVE_PACKAGES = setOf(
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
