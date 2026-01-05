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

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Single
import java.io.File

/**
 * Implementation of [RootShell] using libsu library.
g.
 */
@Single(binds = [RootShell::class])
class RootShellImpl : RootShell {

  init {
    Shell.enableVerboseLogging = false
    Shell.setDefaultBuilder(
      Shell.Builder.create()
        .setFlags(Shell.FLAG_REDIRECT_STDERR)
        .setTimeout(RootShell.DEFAULT_TIMEOUT_MS / 1000L),
    )
  }

  override suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
    try {
      // First try libsu's built-in check
      val libsuCheck = Shell.isAppGrantedRoot()
      if (libsuCheck == true) {
        return@withContext true
      }

      // For KernelSU-based solutions (KSU, SukiSU Ultra, etc.),
      val shell = Shell.getShell()
      if (shell.isRoot) {
        return@withContext true
      }

      val result = Shell.cmd("id").exec()
      result.isSuccess && result.out.any { it.contains("uid=0") }
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
    try {
      val shell = Shell.getShell()
      if (shell.isRoot) {
        return@withContext true
      }

      val result = Shell.cmd("su -c id").exec()
      if (result.isSuccess && result.out.any { it.contains("uid=0") }) {
        return@withContext true
      }

      val idResult = Shell.cmd("id").exec()
      idResult.isSuccess && idResult.out.any { it.contains("uid=0") }
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun getRootType(): RootType = withContext(Dispatchers.IO) {
    if (!isRootAvailable()) {
      return@withContext RootType.NONE
    }

    try {
      // Check for SukiSU Ultra first (it's a KernelSU fork, so check before KSU)
      if (checkSukiSUUltra()) {
        return@withContext RootType.SUKISU_ULTRA
      }

      if (checkKernelSU()) {
        return@withContext if (checkKernelSUNext()) RootType.KSU_NEXT else RootType.KSU
      }

      if (checkAPatch()) {
        return@withContext RootType.APATCH
      }

      if (checkMagisk()) {
        return@withContext RootType.MAGISK
      }

      if (checkSuperSU()) {
        return@withContext RootType.SUPERSU
      }

      // Root is available but type is unknown
      RootType.OTHER
    } catch (e: Exception) {
      RootType.OTHER
    }
  }

  override suspend fun execute(command: String, timeoutMs: Long): ShellResult =
    withContext(Dispatchers.IO) {
      val effectiveTimeout = timeoutMs.coerceIn(
        RootShell.MIN_TIMEOUT_MS,
        RootShell.MAX_TIMEOUT_MS,
      )

      if (!isRootAvailable()) {
        return@withContext ShellResult.RootDenied(
          reason = "Root access is not available or was denied",
        )
      }

      val startTime = System.currentTimeMillis()

      try {
        withTimeout(effectiveTimeout) {
          executeCommand(command, startTime)
        }
      } catch (e: TimeoutCancellationException) {
        ShellResult.Timeout(
          partialOutput = "",
          timeoutMs = effectiveTimeout,
        )
      } catch (e: Exception) {
        ShellResult.Error(
          message = "Shell execution failed: ${e.message}",
          exception = e,
        )
      }
    }

  override suspend fun executeMultiple(
    commands: List<String>,
    timeoutMs: Long,
  ): ShellResult = withContext(Dispatchers.IO) {
    if (commands.isEmpty()) {
      return@withContext ShellResult.Success(
        output = "",
        exitCode = 0,
        executionTimeMs = 0,
      )
    }

    val effectiveTimeout = timeoutMs.coerceIn(
      RootShell.MIN_TIMEOUT_MS,
      RootShell.MAX_TIMEOUT_MS,
    )

    if (!isRootAvailable()) {
      return@withContext ShellResult.RootDenied(
        reason = "Root access is not available or was denied",
      )
    }

    val startTime = System.currentTimeMillis()

    try {
      withTimeout(effectiveTimeout) {
        executeCommands(commands, startTime)
      }
    } catch (e: TimeoutCancellationException) {
      ShellResult.Timeout(
        partialOutput = "",
        timeoutMs = effectiveTimeout,
      )
    } catch (e: Exception) {
      ShellResult.Error(
        message = "Shell execution failed: ${e.message}",
        exception = e,
      )
    }
  }

  /**
   * Execute a single command and return the result.
   */
  private fun executeCommand(command: String, startTime: Long): ShellResult {
    val result = Shell.cmd(command).exec()
    val executionTime = System.currentTimeMillis() - startTime

    val stdout = result.out.joinToString("\n")
    val stderr = result.err.joinToString("\n")

    return if (result.isSuccess) {
      ShellResult.Success(
        output = stdout,
        exitCode = result.code,
        executionTimeMs = executionTime,
      )
    } else {
      // Non-zero exit code but command executed
      if (stderr.isNotEmpty()) {
        ShellResult.Error(
          message = "Command failed with exit code ${result.code}",
          stderr = stderr,
        )
      } else {
        ShellResult.Success(
          output = stdout,
          exitCode = result.code,
          executionTimeMs = executionTime,
        )
      }
    }
  }

  private fun executeCommands(commands: List<String>, startTime: Long): ShellResult {
    val result = Shell.cmd(*commands.toTypedArray()).exec()
    val executionTime = System.currentTimeMillis() - startTime

    val stdout = result.out.joinToString("\n")
    val stderr = result.err.joinToString("\n")

    return if (result.isSuccess) {
      ShellResult.Success(
        output = stdout,
        exitCode = result.code,
        executionTimeMs = executionTime,
      )
    } else {
      if (stderr.isNotEmpty()) {
        ShellResult.Error(
          message = "Commands failed with exit code ${result.code}",
          stderr = stderr,
        )
      } else {
        ShellResult.Success(
          output = stdout,
          exitCode = result.code,
          executionTimeMs = executionTime,
        )
      }
    }
  }

  /**
   * Check if SukiSU Ultra is installed.
   *
   * SukiSU Ultra is a KernelSU fork with enhanced features like SUSFS support,
   * KPM modules, and improved root hiding capabilities.
   */
  private fun checkSukiSUUltra(): Boolean {
    val sukisuPackages = listOf(
      "me.sukisu.manager",
      "me.sukisu.ultra",
      "com.sukisu.manager",
      "com.sukisu.ultra",
    )

    for (pkg in sukisuPackages) {
      val pmResult = Shell.cmd("pm path $pkg 2>/dev/null").exec()
      if (pmResult.isSuccess && pmResult.out.isNotEmpty()) {
        return true
      }
    }

    val versionResult = Shell.cmd("ksud --version 2>/dev/null").exec()
    if (versionResult.isSuccess && versionResult.out.isNotEmpty()) {
      val version = versionResult.out.joinToString("").lowercase()
      if (version.contains("sukisu") || version.contains("suki") || version.contains("ultra")) {
        return true
      }
    }

    val susfsResult = Shell.cmd("cat /proc/filesystems 2>/dev/null | grep -i susfs").exec()
    if (susfsResult.isSuccess && susfsResult.out.isNotEmpty()) {
      if (checkKernelSU()) {
        return true
      }
    }

    val configResult = Shell.cmd("test -f /data/adb/ksu/.sukisu && echo yes").exec()
    if (configResult.isSuccess && configResult.out.any { it.contains("yes") }) {
      return true
    }

    return false
  }

  private fun checkKernelSU(): Boolean {
    val ksuDir = File("/data/adb/ksu")
    if (ksuDir.exists()) return true

    // Also check for ksud binary
    val result = Shell.cmd("which ksud").exec()
    return result.isSuccess && result.out.isNotEmpty()
  }

  private fun checkKernelSUNext(): Boolean {
    // KSU Next has specific version markers
    val result = Shell.cmd("ksud --version 2>/dev/null").exec()
    if (result.isSuccess && result.out.isNotEmpty()) {
      val version = result.out.joinToString("")
      return version.contains("next", ignoreCase = true) ||
        version.contains("ksu-next", ignoreCase = true)
    }
    return false
  }

  private fun checkAPatch(): Boolean {
    // APatch creates /data/adb/ap directory
    val apDir = File("/data/adb/ap")
    if (apDir.exists()) return true

    // Check for apd binary
    val result = Shell.cmd("which apd").exec()
    return result.isSuccess && result.out.isNotEmpty()
  }

  private fun checkMagisk(): Boolean {
    val magiskDir = File("/data/adb/magisk")
    if (magiskDir.exists()) return true

    val result = Shell.cmd("which magisk").exec()
    if (result.isSuccess && result.out.isNotEmpty()) return true

    val versionResult = Shell.cmd("magisk -v 2>/dev/null").exec()
    return versionResult.isSuccess && versionResult.out.isNotEmpty()
  }

  private fun checkSuperSU(): Boolean {
    val suPaths = listOf(
      "/system/xbin/su",
      "/system/bin/su",
      "/sbin/su",
    )

    for (path in suPaths) {
      val file = File(path)
      if (file.exists()) {
        val result = Shell.cmd("$path -v 2>/dev/null").exec()
        if (result.isSuccess && result.out.joinToString("")
            .contains("SUPERSU", ignoreCase = true)
        ) {
          return true
        }
      }
    }

    return false
  }

  companion object {
    private const val TAG = "RootShellImpl"
  }
}
