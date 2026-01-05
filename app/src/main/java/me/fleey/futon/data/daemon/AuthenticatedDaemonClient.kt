/*
 * Futon - Authenticated Daemon Client
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
package me.fleey.futon.data.daemon

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.data.daemon.models.AuthState
import me.fleey.futon.data.daemon.models.DaemonConnectionResult
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import org.koin.core.annotation.Single

/**
 * Wraps DaemonBinderClient with automatic authentication.
 * Handles the full authentication flow: connect -> getChallenge -> sign -> authenticate.
 */
interface AuthenticatedDaemonClient {
  val authState: StateFlow<AuthState>
  val binderClient: DaemonBinderClient

  suspend fun connectAndAuthenticate(): Result<Unit>
  suspend fun disconnect()
  suspend fun ensureAuthenticated(): Result<Unit>
  fun isAuthenticated(): Boolean
}

@Single(binds = [AuthenticatedDaemonClient::class])
class AuthenticatedDaemonClientImpl(
  override val binderClient: DaemonBinderClient,
  private val authenticator: DaemonAuthenticator,
) : AuthenticatedDaemonClient {

  private val mutex = Mutex()

  private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
  override val authState: StateFlow<AuthState> = _authState.asStateFlow()

  override suspend fun connectAndAuthenticate(): Result<Unit> = mutex.withLock {
    _authState.value = AuthState.Authenticating

    val connectResult = binderClient.connect()
    when (connectResult) {
      is DaemonConnectionResult.NotRunning -> {
        val error = DaemonError.connection(
          ErrorCode.SERVICE_NOT_FOUND,
          "Daemon is not running",
        )
        _authState.value = AuthState.Failed(error)
        return Result.failure(DaemonAuthException(error))
      }

      is DaemonConnectionResult.Failed -> {
        _authState.value = AuthState.Failed(connectResult.error)
        return Result.failure(DaemonAuthException(connectResult.error))
      }

      is DaemonConnectionResult.Connected -> {
        Log.d(TAG, "Connected to daemon v${connectResult.version}")
      }
    }

    authenticator.ensureKeyPairExists().onFailure { e ->
      val error = when (e) {
        is DaemonAuthException -> e.error
        else -> DaemonError.authentication(
          ErrorCode.AUTH_KEY_NOT_FOUND,
          "Failed to ensure key pair: ${e.message}",
        )
      }
      _authState.value = AuthState.Failed(error)
      return Result.failure(e)
    }

    val challengeResult = binderClient.getChallenge()
    val challenge = challengeResult.getOrElse { e ->
      val error = when (e) {
        is DaemonBinderException -> e.error
        else -> DaemonError.authentication(
          ErrorCode.AUTH_CHALLENGE_FAILED,
          "Failed to get challenge: ${e.message}",
        )
      }
      _authState.value = AuthState.Failed(error)
      return Result.failure(e)
    }

    if (challenge.isEmpty()) {
      Log.d(TAG, "Empty challenge received - authentication may be disabled on daemon")
      _authState.value = AuthState.Authenticated
      return Result.success(Unit)
    }

    Log.d(TAG, "Received challenge: ${challenge.size} bytes")

    val signatureResult = authenticator.signChallenge(challenge)
    val signature = signatureResult.getOrElse { e ->
      val error = when (e) {
        is DaemonAuthException -> e.error
        else -> DaemonError.authentication(
          ErrorCode.AUTH_SIGNATURE_INVALID,
          "Failed to sign challenge: ${e.message}",
        )
      }
      _authState.value = AuthState.Failed(error)
      return Result.failure(e)
    }

    Log.d(TAG, "Signed challenge: ${signature.size} bytes")

    val instanceId = authenticator.getInstanceId()
    Log.d(TAG, "Instance ID: $instanceId")

    val authResult = binderClient.authenticate(signature, instanceId)
    authResult.onFailure { e ->
      val error = when (e) {
        is DaemonBinderException -> e.error
        else -> DaemonError.authentication(
          ErrorCode.AUTH_FAILED,
          "Authentication failed: ${e.message}",
        )
      }
      _authState.value = AuthState.Failed(error)
      return Result.failure(e)
    }

    Log.i(TAG, "Authentication successful")
    _authState.value = AuthState.Authenticated
    return Result.success(Unit)
  }

  override suspend fun disconnect() = mutex.withLock {
    binderClient.disconnect()
    _authState.value = AuthState.NotAuthenticated
  }

  override suspend fun ensureAuthenticated(): Result<Unit> {
    if (isAuthenticated()) {
      val instanceId = authenticator.getInstanceId()
      val sessionResult = binderClient.checkSession(instanceId)

      sessionResult.onSuccess { status ->
        if (status.hasActiveSession && status.isOwnSession) {
          return Result.success(Unit)
        }
      }
    }

    // Re-authenticate
    return connectAndAuthenticate()
  }

  override fun isAuthenticated(): Boolean {
    return _authState.value == AuthState.Authenticated && binderClient.isConnected()
  }

  companion object {
    private const val TAG = "AuthenticatedDaemonClient"
  }
}
