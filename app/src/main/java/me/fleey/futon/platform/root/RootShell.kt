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

/**
 * Interface for executing shell commands with root privileges.
 */
interface RootShell {
  suspend fun isRootAvailable(): Boolean

  suspend fun requestRoot(): Boolean

  suspend fun getRootType(): RootType

  /**
   * Execute a shell command with root privileges.
   *
   * @param command The command to execute
   * @param timeoutMs Maximum time to wait for command completion (default: 5000ms)
   * @return Result of the command execution
   */
  suspend fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult

  suspend fun executeMultiple(
    commands: List<String>,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS * 2,
  ): ShellResult

  companion object {
    const val DEFAULT_TIMEOUT_MS = 5000L

    const val MIN_TIMEOUT_MS = 1000L

    const val MAX_TIMEOUT_MS = 30000L
  }
}

enum class RootType {
  /**
   * KernelSU - Kernel-based root solution.
   */
  KSU,

  /**
   * KernelSU Next - Next generation KernelSU.
   */
  KSU_NEXT,

  /**
   * SukiSU Ultra - Kernel-based root solution forked from KernelSU.
   */
  SUKISU_ULTRA,

  /**
   * Magisk - Popular systemless root solution.
   */
  MAGISK,

  /**
   * SuperSU - Legacy root solution.
   */
  SUPERSU,

  /**
   * APatch - Another kernel-based root solution.
   */
  APATCH,

  /**
   * Other/unknown root solution.
   */
  OTHER,

  /**
   * No root access available.
   */
  NONE
}

sealed interface ShellResult {
  data class Success(
    val output: String,
    val exitCode: Int,
    val executionTimeMs: Long = 0L,
  ) : ShellResult {
    val isSuccessful: Boolean get() = exitCode == 0
  }

  data class Error(
    val message: String,
    val exception: Throwable? = null,
    val stderr: String? = null,
  ) : ShellResult

  data class Timeout(
    val partialOutput: String,
    val timeoutMs: Long,
  ) : ShellResult

  data class RootDenied(
    val reason: String,
  ) : ShellResult
}

fun ShellResult.isSuccess(): Boolean = this is ShellResult.Success && this.isSuccessful

fun ShellResult.getOutputOrNull(): String? = (this as? ShellResult.Success)?.output

fun ShellResult.getErrorMessage(): String? = when (this) {
  is ShellResult.Success -> if (!isSuccessful) "Command failed with exit code $exitCode" else null
  is ShellResult.Error -> message
  is ShellResult.Timeout -> "Command timed out after ${timeoutMs}ms"
  is ShellResult.RootDenied -> reason
}
