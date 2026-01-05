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
package me.fleey.futon.data.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.daemon.models.KeyDeploymentState
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single

interface KeyDeployer {
  val deploymentState: StateFlow<KeyDeploymentState>

  suspend fun checkDeployment(): Result<KeyDeploymentState>
  suspend fun deployPublicKey(publicKey: ByteArray): Result<Unit>
  suspend fun getDeployedFingerprint(): Result<ByteArray?>
  suspend fun getDeployedPublicKey(): Result<ByteArray?>
  suspend fun needsRedeployment(): Result<Boolean>
  suspend fun rotateKey(newPublicKey: ByteArray): Result<Unit>
  suspend fun verifyKeyConsistency(): Result<KeyConsistencyResult>
}

sealed interface KeyConsistencyResult {
  data object Consistent : KeyConsistencyResult
  data object NotDeployed : KeyConsistencyResult
  data class Mismatch(val localFingerprint: ByteArray, val deployedFingerprint: ByteArray) :
    KeyConsistencyResult

  data class Error(val error: DaemonError) : KeyConsistencyResult
}

@Single(binds = [KeyDeployer::class])
class KeyDeployerImpl(
  private val rootShell: RootShell,
  private val authenticator: DaemonAuthenticator,
) : KeyDeployer {

  private val _deploymentState =
    MutableStateFlow<KeyDeploymentState>(KeyDeploymentState.NotDeployed)
  override val deploymentState: StateFlow<KeyDeploymentState> = _deploymentState.asStateFlow()

  private val mutex = Mutex()

  override suspend fun checkDeployment(): Result<KeyDeploymentState> = withContext(Dispatchers.IO) {
    mutex.withLock {
      _deploymentState.value = KeyDeploymentState.Checking

      try {
        val localFingerprintResult = authenticator.getPublicKeyFingerprint()
        if (localFingerprintResult.isFailure) {
          val state = KeyDeploymentState.Failed(
            DaemonError.security(
              ErrorCode.AUTH_KEY_NOT_FOUND,
              "Failed to get local public key fingerprint",
            ),
          )
          _deploymentState.value = state
          return@withContext Result.success(state)
        }
        val localFingerprint = localFingerprintResult.getOrThrow()

        val deployedFingerprintResult = getDeployedFingerprintInternal()
        if (deployedFingerprintResult.isFailure) {
          val state = KeyDeploymentState.NotDeployed
          _deploymentState.value = state
          return@withContext Result.success(state)
        }

        val deployedFingerprint = deployedFingerprintResult.getOrNull()
        if (deployedFingerprint == null) {
          val state = KeyDeploymentState.NotDeployed
          _deploymentState.value = state
          return@withContext Result.success(state)
        }

        val state = if (localFingerprint.contentEquals(deployedFingerprint)) {
          KeyDeploymentState.Deployed(localFingerprint)
        } else {
          KeyDeploymentState.Mismatch(localFingerprint, deployedFingerprint)
        }
        _deploymentState.value = state
        Result.success(state)
      } catch (e: Exception) {
        val state = KeyDeploymentState.Failed(
          DaemonError.security(
            ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
            "Failed to check deployment: ${e.message}",
          ),
        )
        _deploymentState.value = state
        Result.failure(e)
      }
    }
  }

  override suspend fun deployPublicKey(publicKey: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        _deploymentState.value = KeyDeploymentState.Deploying

        try {
          if (!rootShell.isRootAvailable()) {
            val error = DaemonError.deployment(
              ErrorCode.DEPLOY_ROOT_UNAVAILABLE,
              "Root access is required to deploy public key",
            )
            _deploymentState.value = KeyDeploymentState.Failed(error)
            return@withContext Result.failure(KeyDeployException(error))
          }

          val hexKey = publicKey.joinToString("") { "%02x".format(it) }

          // Delete pinned fingerprint so daemon can accept the new key
          // This is necessary when the app regenerates its keypair
          val commands = listOf(
            "mkdir -p ${DaemonConfig.BASE_DIR}",
            "rm -f ${DaemonConfig.PUBKEY_PIN_PATH}",
            "echo '$hexKey' > ${DaemonConfig.AUTH_PUBKEY_PATH}",
            "chmod 600 ${DaemonConfig.AUTH_PUBKEY_PATH}",
            "chown root:root ${DaemonConfig.AUTH_PUBKEY_PATH}",
          )

          val result = rootShell.executeMultiple(commands)
          if (!result.isSuccess()) {
            val errorMsg = when (result) {
              is ShellResult.Error -> result.message
              is ShellResult.RootDenied -> result.reason
              is ShellResult.Timeout -> "Command timed out"
              else -> "Unknown error"
            }
            val error = DaemonError.security(
              ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
              "Failed to deploy public key: $errorMsg",
            )
            _deploymentState.value = KeyDeploymentState.Failed(error)
            return@withContext Result.failure(KeyDeployException(error))
          }

          val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
          _deploymentState.value = KeyDeploymentState.Deployed(fingerprint)
          Result.success(Unit)
        } catch (e: Exception) {
          val error = DaemonError.security(
            ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
            "Failed to deploy public key: ${e.message}",
          )
          _deploymentState.value = KeyDeploymentState.Failed(error)
          Result.failure(KeyDeployException(error, e))
        }
      }
    }

  override suspend fun getDeployedFingerprint(): Result<ByteArray?> = withContext(Dispatchers.IO) {
    mutex.withLock {
      getDeployedFingerprintInternal()
    }
  }

  private suspend fun getDeployedFingerprintInternal(): Result<ByteArray?> {
    return try {
      if (!rootShell.isRootAvailable()) {
        return Result.failure(
          KeyDeployException(
            DaemonError.deployment(
              ErrorCode.DEPLOY_ROOT_UNAVAILABLE,
              "Root access is required to read deployed key",
            ),
          ),
        )
      }

      val checkResult = rootShell.execute("test -f ${DaemonConfig.AUTH_PUBKEY_PATH} && echo exists")
      if (!checkResult.isSuccess()) {
        return Result.success(null)
      }

      val output = (checkResult as? ShellResult.Success)?.output?.trim()
      if (output != "exists") {
        return Result.success(null)
      }

      val readResult = rootShell.execute("cat ${DaemonConfig.AUTH_PUBKEY_PATH}")
      if (!readResult.isSuccess()) {
        return Result.success(null)
      }

      val hexKey = (readResult as? ShellResult.Success)?.output?.trim()
      if (hexKey.isNullOrEmpty()) {
        return Result.success(null)
      }

      val publicKey = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
      val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
      Result.success(fingerprint)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override suspend fun needsRedeployment(): Result<Boolean> = withContext(Dispatchers.IO) {
    checkDeployment().map { state ->
      when (state) {
        is KeyDeploymentState.NotDeployed -> true
        is KeyDeploymentState.Mismatch -> true
        is KeyDeploymentState.Failed -> true
        is KeyDeploymentState.Deployed -> false
        is KeyDeploymentState.Checking -> false
        is KeyDeploymentState.Deploying -> false
      }
    }
  }

  override suspend fun rotateKey(newPublicKey: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          val deployResult = deployPublicKeyInternal(newPublicKey)
          if (deployResult.isFailure) {
            return@withContext deployResult
          }

          val killResult = rootShell.execute("${DaemonConfig.Commands.KILL} 2>/dev/null || true")
          if (killResult is ShellResult.Error) {
            // Ignore errors - daemon might not be running
          }

          Result.success(Unit)
        } catch (e: Exception) {
          val error = DaemonError.security(
            ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
            "Failed to rotate key: ${e.message}",
          )
          _deploymentState.value = KeyDeploymentState.Failed(error)
          Result.failure(KeyDeployException(error, e))
        }
      }
    }

  private suspend fun deployPublicKeyInternal(publicKey: ByteArray): Result<Unit> {
    _deploymentState.value = KeyDeploymentState.Deploying

    if (!rootShell.isRootAvailable()) {
      val error = DaemonError.deployment(
        ErrorCode.DEPLOY_ROOT_UNAVAILABLE,
        "Root access is required to deploy public key",
      )
      _deploymentState.value = KeyDeploymentState.Failed(error)
      return Result.failure(KeyDeployException(error))
    }

    val hexKey = publicKey.joinToString("") { "%02x".format(it) }

    // Delete pinned fingerprint so daemon can accept the new key
    val commands = listOf(
      "mkdir -p ${DaemonConfig.BASE_DIR}",
      "rm -f ${DaemonConfig.PUBKEY_PIN_PATH}",
      "echo '$hexKey' > ${DaemonConfig.AUTH_PUBKEY_PATH}",
      "chmod 600 ${DaemonConfig.AUTH_PUBKEY_PATH}",
      "chown root:root ${DaemonConfig.AUTH_PUBKEY_PATH}",
    )

    val result = rootShell.executeMultiple(commands)
    if (!result.isSuccess()) {
      val errorMsg = when (result) {
        is ShellResult.Error -> result.message
        is ShellResult.RootDenied -> result.reason
        is ShellResult.Timeout -> "Command timed out"
        else -> "Unknown error"
      }
      val error = DaemonError.security(
        ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
        "Failed to deploy public key: $errorMsg",
      )
      _deploymentState.value = KeyDeploymentState.Failed(error)
      return Result.failure(KeyDeployException(error))
    }

    val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
    _deploymentState.value = KeyDeploymentState.Deployed(fingerprint)
    return Result.success(Unit)
  }

  override suspend fun getDeployedPublicKey(): Result<ByteArray?> = withContext(Dispatchers.IO) {
    mutex.withLock {
      try {
        if (!rootShell.isRootAvailable()) {
          return@withContext Result.failure(
            KeyDeployException(
              DaemonError.deployment(
                ErrorCode.DEPLOY_ROOT_UNAVAILABLE,
                "Root access is required to read deployed key",
              ),
            ),
          )
        }

        val checkResult =
          rootShell.execute("test -f ${DaemonConfig.AUTH_PUBKEY_PATH} && echo exists")
        if (!checkResult.isSuccess()) {
          return@withContext Result.success(null)
        }

        val output = (checkResult as? ShellResult.Success)?.output?.trim()
        if (output != "exists") {
          return@withContext Result.success(null)
        }

        val readResult = rootShell.execute("cat ${DaemonConfig.AUTH_PUBKEY_PATH}")
        if (!readResult.isSuccess()) {
          return@withContext Result.success(null)
        }

        val hexKey = (readResult as? ShellResult.Success)?.output?.trim()
        if (hexKey.isNullOrEmpty()) {
          return@withContext Result.success(null)
        }

        val publicKey = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Result.success(publicKey)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  override suspend fun verifyKeyConsistency(): Result<KeyConsistencyResult> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          val localKeyResult = authenticator.getPublicKey()
          if (localKeyResult.isFailure) {
            return@withContext Result.success(
              KeyConsistencyResult.Error(
                DaemonError.security(
                  ErrorCode.AUTH_KEY_NOT_FOUND,
                  "Failed to get local public key",
                ),
              ),
            )
          }
          val localKey = localKeyResult.getOrThrow()
          val localFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(localKey)

          val deployedKeyResult = getDeployedPublicKey()
          if (deployedKeyResult.isFailure) {
            return@withContext Result.success(
              KeyConsistencyResult.Error(
                DaemonError.security(
                  ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
                  "Failed to read deployed key",
                ),
              ),
            )
          }

          val deployedKey =
            deployedKeyResult.getOrNull() ?: return@withContext Result.success(KeyConsistencyResult.NotDeployed)

          val deployedFingerprint =
            java.security.MessageDigest.getInstance("SHA-256").digest(deployedKey)

          if (localFingerprint.contentEquals(deployedFingerprint)) {
            Result.success(KeyConsistencyResult.Consistent)
          } else {
            Result.success(KeyConsistencyResult.Mismatch(localFingerprint, deployedFingerprint))
          }
        } catch (e: Exception) {
          Result.success(
            KeyConsistencyResult.Error(
              DaemonError.security(
                ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
                "Key consistency check failed: ${e.message}",
              ),
            ),
          )
        }
      }
    }

}

class KeyDeployException(
  val error: DaemonError,
  cause: Throwable? = null,
) : Exception(error.message, cause)
