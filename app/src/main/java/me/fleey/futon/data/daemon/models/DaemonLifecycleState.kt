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
package me.fleey.futon.data.daemon.models

sealed interface DaemonLifecycleState {
  data object Stopped : DaemonLifecycleState

  data class Starting(
    val phase: StartupPhase = StartupPhase.Initializing,
    val logs: List<String> = emptyList(),
    val elapsedMs: Long = 0,
  ) : DaemonLifecycleState

  data class Running(
    val pid: Int? = null,
    val startedAt: Long = System.currentTimeMillis(),
  ) : DaemonLifecycleState

  data object Stopping : DaemonLifecycleState

  data class Failed(
    val reason: String,
    val diagnostic: LifecycleDiagnostic? = null,
    val cause: Throwable? = null,
    val logs: List<String> = emptyList(),
  ) : DaemonLifecycleState
}

enum class StartupPhase {
  Initializing,
  CheckingRoot,
  CheckingBinary,
  Deploying,
  ExecutingStart,
  WaitingForBinder,
  VerifyingVersion,
  Connecting,
}

sealed interface LifecycleDiagnostic {
  data object BinaryMissing : LifecycleDiagnostic

  data class SELinuxDenied(val details: String) : LifecycleDiagnostic

  data class PermissionError(val path: String, val requiredPermission: String) : LifecycleDiagnostic

  data class BinderUnavailable(val waitedMs: Long) : LifecycleDiagnostic

  data class VersionMismatch(
    val expected: Int,
    val actual: Int,
  ) : LifecycleDiagnostic

  data class StartupTimeout(val timeoutMs: Long) : LifecycleDiagnostic

  data class ProcessCrashed(val exitCode: Int?, val stderr: String?) : LifecycleDiagnostic

  data class RootUnavailable(val reason: String) : LifecycleDiagnostic
}
