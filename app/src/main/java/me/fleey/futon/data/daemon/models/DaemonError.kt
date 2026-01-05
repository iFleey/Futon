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

data class DaemonError(
  val code: ErrorCode,
  val message: String,
  val timestamp: Long = System.currentTimeMillis(),
  val cause: Throwable? = null,
) {
  val recoverable: Boolean get() = code.recoverable

  val category: ErrorCategory
    get() = when {
      ErrorCode.isConnectionError(code.code) -> ErrorCategory.CONNECTION
      ErrorCode.isAuthError(code.code) -> ErrorCategory.AUTHENTICATION
      ErrorCode.isSecurityError(code.code) -> ErrorCategory.SECURITY
      ErrorCode.isDeploymentError(code.code) -> ErrorCategory.DEPLOYMENT
      ErrorCode.isRuntimeError(code.code) -> ErrorCategory.RUNTIME
      ErrorCode.isConfigError(code.code) -> ErrorCategory.CONFIGURATION
      ErrorCode.isAutomationError(code.code) -> ErrorCategory.AUTOMATION
      else -> ErrorCategory.UNKNOWN
    }

  companion object {
    fun connection(code: ErrorCode, message: String, cause: Throwable? = null) =
      DaemonError(code, message, cause = cause)

    fun authentication(code: ErrorCode, message: String) =
      DaemonError(code, message)

    fun security(code: ErrorCode, message: String) =
      DaemonError(code, message)

    fun deployment(code: ErrorCode, message: String) =
      DaemonError(code, message)

    fun runtime(code: ErrorCode, message: String, cause: Throwable? = null) =
      DaemonError(code, message, cause = cause)

    fun config(code: ErrorCode, message: String) =
      DaemonError(code, message)

    fun automation(code: ErrorCode, message: String) =
      DaemonError(code, message)

    fun fromDaemonCallback(code: Int, message: String): DaemonError {
      val errorCode = ErrorCode.fromCode(code)
      return DaemonError(errorCode, message)
    }
  }
}

enum class ErrorCategory {
  CONNECTION,
  AUTHENTICATION,
  SECURITY,
  DEPLOYMENT,
  RUNTIME,
  CONFIGURATION,
  AUTOMATION,
  UNKNOWN
}
