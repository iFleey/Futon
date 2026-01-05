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

sealed interface AuthState {
  data object NotInitialized : AuthState

  data object NotAuthenticated : AuthState

  data object KeyGenerating : AuthState

  data object KeyReady : AuthState

  data object KeyDeploying : AuthState

  data object KeyDeployed : AuthState

  data object ChallengeRequested : AuthState

  data object Signing : AuthState

  data object Authenticating : AuthState

  data object Authenticated : AuthState

  data class AuthenticatedWithSession(
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
  ) : AuthState

  data class Failed(
    val error: DaemonError,
  ) : AuthState

  data object SessionExpired : AuthState
}

sealed interface KeyDeploymentState {
  data object NotDeployed : KeyDeploymentState

  data object Checking : KeyDeploymentState

  data object Deploying : KeyDeploymentState

  data class Deployed(
    val fingerprint: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
  ) : KeyDeploymentState {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Deployed) return false
      return fingerprint.contentEquals(other.fingerprint) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
      var result = fingerprint.contentHashCode()
      result = 31 * result + timestamp.hashCode()
      return result
    }
  }

  data class Mismatch(
    val localFingerprint: ByteArray,
    val deployedFingerprint: ByteArray,
  ) : KeyDeploymentState {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Mismatch) return false
      return localFingerprint.contentEquals(other.localFingerprint) &&
        deployedFingerprint.contentEquals(other.deployedFingerprint)
    }

    override fun hashCode(): Int {
      var result = localFingerprint.contentHashCode()
      result = 31 * result + deployedFingerprint.contentHashCode()
      return result
    }
  }

  data class Failed(
    val error: DaemonError,
  ) : KeyDeploymentState
}
