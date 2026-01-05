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
package me.fleey.futon.platform.root

import android.os.Build
import android.util.Log

import org.koin.core.annotation.Single

/**
 * Utility class for fixing SELinux policies that block input device access.
 */
@Single
class SELinuxFixer(
  private val rootShell: RootShell,
) {

  companion object {
    private const val TAG = "SELinuxFixer"

    private val KSUD_PATHS = listOf(
      "/data/adb/ksud",               // Alternative KernelSU path
      "/data/adb/sukisu/bin/ksud",    // SukiSU Ultra specific path
      "/data/adb/ap/bin/apd",         // APatch path
      "ksud",                          // PATH lookup
    )

    // SELinux domains for third-party apps across different Android versions
    private val APP_DOMAINS = listOf(
      "untrusted_app",           // Base domain for all third-party apps
      "untrusted_app_25",        // Android 7.1 (API 25)
      "untrusted_app_27",        // Android 8.1 (API 27)
      "untrusted_app_29",        // Android 10 (API 29)
      "untrusted_app_30",        // Android 11 (API 30)
      "untrusted_app_32",        // Android 12L (API 32)
      "untrusted_app_33",        // Android 13 (API 33)
      "untrusted_app_34",        // Android 14 (API 34)
      "untrusted_app_35",        // Android 15 (API 35)
      "untrusted_app_36",        // Android 16 (API 36)
      "platform_app",            // Platform signed apps
      "priv_app",                // Privileged apps
      "system_app",              // System apps
      "isolated_app",            // Isolated process apps
      "isolated_app_all",         // All isolated apps
    )

    // Permissions needed for input device access
    private const val DIR_PERMS = "ioctl read getattr search open"
    private const val CHR_FILE_PERMS = "ioctl read write getattr lock append open"
  }

  sealed interface FixResult {
    data object Success : FixResult
    data class Failed(val message: String, val suggestion: String? = null) : FixResult
    data object AlreadyFixed : FixResult
    data object NotNeeded : FixResult
  }

  /**
   * Detailed diagnostic information for debugging SELinux issues.
   */
  data class DiagnosticInfo(
    val selinuxMode: String,
    val rootType: RootType,
    val ksudPath: String?,
    val rulesApplied: Int,
    val rulesFailed: Int,
    val lastError: String?,
    val avcDenials: List<String>,
  )

  suspend fun isEnforcing(): Boolean {
    val result = rootShell.execute("getenforce")
    return when (result) {
      is ShellResult.Success -> result.output.trim().equals("Enforcing", ignoreCase = true)
      else -> true
    }
  }

  suspend fun getMode(): String {
    val result = rootShell.execute("getenforce")
    return when (result) {
      is ShellResult.Success -> result.output.trim()
      else -> "Unknown"
    }
  }

  suspend fun fixInputDeviceAccess(): FixResult {
    Log.d(TAG, "Attempting to fix SELinux for input device access")
    Log.d(TAG, "Android SDK: ${Build.VERSION.SDK_INT}, Device: ${Build.MODEL}")

    if (!rootShell.isRootAvailable()) {
      return FixResult.Failed(
        message = "Root access not available",
        suggestion = "Grant root permission to the app",
      )
    }

    val rootType = rootShell.getRootType()
    Log.d(TAG, "Detected root type: $rootType")

    return when (rootType) {
      RootType.KSU, RootType.KSU_NEXT, RootType.SUKISU_ULTRA -> fixWithKernelSU()
      RootType.MAGISK -> fixWithMagisk()
      RootType.SUPERSU, RootType.APATCH, RootType.OTHER -> fixWithSupolicy()
      RootType.NONE -> FixResult.Failed(
        message = "No root solution detected",
        suggestion = "Install a root solution like KernelSU or Magisk",
      )
    }
  }

  private suspend fun fixWithKernelSU(): FixResult {
    Log.d(TAG, "Fixing SELinux with KernelSU/SukiSU method")

    // Find ksud binary
    val ksudPath = findKsudPath()
    if (ksudPath != null) {
      Log.d(TAG, "Found ksud at: $ksudPath")
      val result = applyKsudPolicies(ksudPath)
      if (result is FixResult.Success) {
        return result
      }
      Log.w(TAG, "ksud method failed, trying alternatives")
    }

    // Try magiskpolicy as fallback (some KSU setups have it)
    Log.d(TAG, "Trying magiskpolicy as fallback")
    val magiskResult = fixWithMagisk()
    if (magiskResult is FixResult.Success) {
      return magiskResult
    }

    // Try supolicy as last resort
    Log.d(TAG, "Trying supolicy as last resort")
    return fixWithSupolicy()
  }

  private suspend fun findKsudPath(): String? {
    for (path in KSUD_PATHS) {
      val result = rootShell.execute("test -x $path && echo exists")
      if (result is ShellResult.Success && result.output.contains("exists")) {
        return path
      }
    }
    // Also try which command
    val whichResult = rootShell.execute("which ksud 2>/dev/null")
    if (whichResult is ShellResult.Success && whichResult.output.isNotBlank()) {
      return whichResult.output.trim()
    }
    // Try apd for APatch
    val apdResult = rootShell.execute("which apd 2>/dev/null")
    if (apdResult is ShellResult.Success && apdResult.output.isNotBlank()) {
      return apdResult.output.trim()
    }
    return null
  }

  private suspend fun applyKsudPolicies(ksudPath: String): FixResult {
    val rules = buildInputDeviceRules()
    var successCount = 0
    var lastError: String? = null

    // Determine if this is ksud or apd
    val isApd = ksudPath.contains("apd")

    for (rule in rules) {
      // Try multiple command formats for compatibility
      val commands = if (isApd) {
        listOf(
          "$ksudPath sepolicy patch '$rule'",
          "$ksudPath sepolicy patch \"$rule\"",
        )
      } else {
        listOf(
          "$ksudPath sepolicy patch '$rule'",
          "$ksudPath sepolicy patch \"$rule\"",
          // Some versions use different syntax
          "$ksudPath sepolicy '$rule'",
        )
      }

      var ruleApplied = false
      for (command in commands) {
        Log.d(TAG, "Trying: $command")
        val result = rootShell.execute(command, timeoutMs = 15000L)
        when (result) {
          is ShellResult.Success -> {
            if (result.exitCode == 0) {
              Log.d(TAG, "Rule applied successfully")
              successCount++
              ruleApplied = true
              break
            } else {
              lastError = "Exit code: ${result.exitCode}, output: ${result.output}"
            }
          }

          is ShellResult.Error -> {
            lastError = result.message
          }

          is ShellResult.Timeout -> {
            lastError = "Command timed out"
          }

          is ShellResult.RootDenied -> {
            return FixResult.Failed("Root access denied", "Grant root permission")
          }
        }
      }

      if (!ruleApplied) {
        Log.w(TAG, "Failed to apply rule: $rule, error: $lastError")
      }
    }

    return if (successCount > 0) {
      Log.i(TAG, "KernelSU/SukiSU SELinux fix: $successCount/${rules.size} rules applied")
      FixResult.Success
    } else {
      FixResult.Failed("No rules could be applied via ksud: $lastError")
    }
  }

  private suspend fun fixWithMagisk(): FixResult {
    Log.d(TAG, "Fixing SELinux with Magisk method")

    // Check for magiskpolicy
    val checkResult = rootShell.execute("which magiskpolicy 2>/dev/null")
    if (checkResult !is ShellResult.Success || checkResult.output.isBlank()) {
      Log.d(TAG, "magiskpolicy not found")
      return FixResult.Failed("magiskpolicy not available")
    }

    val rules = buildInputDeviceRules()
    var successCount = 0

    for (rule in rules) {
      // Magisk uses: magiskpolicy --live "rule"
      val command = "magiskpolicy --live \"$rule\""
      Log.d(TAG, "Executing: $command")

      val result = rootShell.execute(command, timeoutMs = 15000L)
      when (result) {
        is ShellResult.Success -> {
          if (result.exitCode == 0) {
            successCount++
            Log.d(TAG, "Rule applied successfully")
          }
        }

        is ShellResult.Error -> Log.w(TAG, "Rule failed: ${result.message}")
        is ShellResult.Timeout -> Log.w(TAG, "Rule timed out")
        is ShellResult.RootDenied -> {
          return FixResult.Failed("Root access denied", "Grant root permission")
        }
      }
    }

    return if (successCount > 0) {
      Log.i(TAG, "Magisk SELinux fix: $successCount/${rules.size} rules applied")
      FixResult.Success
    } else {
      FixResult.Failed("No rules could be applied via magiskpolicy")
    }
  }

  private suspend fun fixWithSupolicy(): FixResult {
    Log.d(TAG, "Fixing SELinux with supolicy method")

    val checkResult = rootShell.execute("which supolicy 2>/dev/null")
    if (checkResult !is ShellResult.Success || checkResult.output.isBlank()) {
      Log.w(TAG, "supolicy not found")
      return FixResult.Failed(
        message = "No SELinux policy tool found",
        suggestion = "Your root solution may not support live SELinux policy patching",
      )
    }

    val rules = buildInputDeviceRules()
    var successCount = 0

    for (rule in rules) {
      // SuperSU uses: supolicy --live "rule"
      val command = "supolicy --live \"$rule\""
      Log.d(TAG, "Executing: $command")

      val result = rootShell.execute(command, timeoutMs = 15000L)
      when (result) {
        is ShellResult.Success -> {
          if (result.exitCode == 0) {
            successCount++
          }
        }

        is ShellResult.Error -> Log.w(TAG, "Rule failed: ${result.message}")
        is ShellResult.Timeout -> Log.w(TAG, "Rule timed out")
        is ShellResult.RootDenied -> {
          return FixResult.Failed("Root access denied", "Grant root permission")
        }
      }
    }

    return if (successCount > 0) {
      Log.i(TAG, "supolicy SELinux fix: $successCount/${rules.size} rules applied")
      FixResult.Success
    } else {
      FixResult.Failed(
        message = "Failed to apply SELinux policies",
        suggestion = "Try rebooting and running the fix again",
      )
    }
  }

  private fun buildInputDeviceRules(): List<String> {
    val rules = mutableListOf<String>()

    for (domain in APP_DOMAINS) {
      // Allow access to input_device directory
      rules.add("allow $domain input_device dir { $DIR_PERMS }")
      // Allow access to input_device character files (the actual event devices)
      rules.add("allow $domain input_device chr_file { $CHR_FILE_PERMS }")
    }

    // Also add rules for shell and su domains for completeness
    rules.add("allow shell input_device dir { $DIR_PERMS }")
    rules.add("allow shell input_device chr_file { $CHR_FILE_PERMS }")
    rules.add("allow su input_device dir { $DIR_PERMS }")
    rules.add("allow su input_device chr_file { $CHR_FILE_PERMS }")

    // Add magisk domain rules
    rules.add("allow magisk input_device dir { $DIR_PERMS }")
    rules.add("allow magisk input_device chr_file { $CHR_FILE_PERMS }")

    return rules
  }

  /**
   * Get diagnostic information for debugging SELinux issues.
   */
  suspend fun getDiagnosticInfo(): DiagnosticInfo {
    val mode = getMode()
    val rootType = rootShell.getRootType()
    val ksudPath = findKsudPath()

    // Check for recent AVC denials related to input_device
    val avcDenials = mutableListOf<String>()
    val dmesgResult = rootShell.execute("dmesg | grep -i 'avc.*input_device' | tail -5 2>/dev/null")
    if (dmesgResult is ShellResult.Success && dmesgResult.output.isNotBlank()) {
      avcDenials.addAll(dmesgResult.output.lines().filter { it.isNotBlank() })
    }

    return DiagnosticInfo(
      selinuxMode = mode,
      rootType = rootType,
      ksudPath = ksudPath,
      rulesApplied = 0,
      rulesFailed = 0,
      lastError = null,
      avcDenials = avcDenials,
    )
  }
}
