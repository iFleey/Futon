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

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.data.crypto.DHKeyPair
import me.fleey.futon.data.crypto.DualChannelCrypto
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface SecureChannelState {
  data object Disconnected : SecureChannelState
  data object Handshaking : SecureChannelState
  data class Connected(val sessionId: String, val keyGeneration: Long) : SecureChannelState
  data class Error(val error: DaemonError) : SecureChannelState
}

class SecureChannel(private val binderClient: DaemonBinderClient) {
  private val mutex = Mutex()
  private val crypto = DualChannelCrypto()

  @Volatile
  private var state: SecureChannelState = SecureChannelState.Disconnected

  @Volatile
  private var sessionId: String? = null

  fun getState(): SecureChannelState = state

  suspend fun establish(sharedSecret: ByteArray): Result<SecureChannelState.Connected> =
    mutex.withLock {
      try {
        state = SecureChannelState.Handshaking

        val ourKeyPair = DHKeyPair.generate()
        val handshake = binderClient.initCryptoChannel(ourKeyPair.publicKey).getOrElse { e ->
          state = SecureChannelState.Error(
            DaemonError.security(
              ErrorCode.CRYPTO_HANDSHAKE_FAILED,
              "Handshake failed: ${e.message}",
            ),
          )
          return Result.failure(e)
        }

        if (handshake.errorCode != 0) {
          val error = DaemonError.security(
            ErrorCode.CRYPTO_HANDSHAKE_FAILED,
            handshake.errorMessage ?: "Unknown handshake error",
          )
          state = SecureChannelState.Error(error)
          return Result.failure(SecureChannelException(error))
        }

        val derivedSecret =
          deriveChannelSecret(sharedSecret, ourKeyPair.publicKey, handshake.dhPublicKey)
        if (!crypto.initInitiator(derivedSecret, handshake.dhPublicKey)) {
          derivedSecret.fill(0)
          val error =
            DaemonError.security(ErrorCode.CRYPTO_INIT_FAILED, "Failed to initialize crypto")
          state = SecureChannelState.Error(error)
          return Result.failure(SecureChannelException(error))
        }
        derivedSecret.fill(0)

        sessionId = handshake.sessionId
        val connectedState =
          SecureChannelState.Connected(handshake.sessionId, handshake.keyGeneration)
        state = connectedState

        Log.d(TAG, "Secure channel established, session: ${handshake.sessionId}")
        Result.success(connectedState)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to establish secure channel", e)
        val error =
          DaemonError.security(ErrorCode.CRYPTO_HANDSHAKE_FAILED, "Exception: ${e.message}")
        state = SecureChannelState.Error(error)
        Result.failure(SecureChannelException(error, e))
      }
    }

  suspend fun sendControl(plaintext: ByteArray): Result<ByteArray> = mutex.withLock {
    if (state !is SecureChannelState.Connected) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_NOT_INITIALIZED, "Channel not connected"),
        ),
      )
    }

    val encrypted = crypto.encryptControl(plaintext)
      ?: return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_ENCRYPT_FAILED, "Control encryption failed"),
        ),
      )

    binderClient.sendControlMessage(encrypted).mapCatching { response ->
      crypto.decryptControl(response)
        ?: throw SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_DECRYPT_FAILED, "Control decryption failed"),
        )
    }
  }

  suspend fun sendData(plaintext: ByteArray): Result<ByteArray> = mutex.withLock {
    if (state !is SecureChannelState.Connected) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_NOT_INITIALIZED, "Channel not connected"),
        ),
      )
    }

    if (crypto.dataChannelNeedsRotation()) {
      rotateKeysInternal()
    }

    val encrypted = crypto.encryptData(plaintext)
      ?: return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_ENCRYPT_FAILED, "Data encryption failed"),
        ),
      )

    binderClient.sendDataMessage(encrypted).mapCatching { response ->
      crypto.decryptData(response)
        ?: throw SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_DECRYPT_FAILED, "Data decryption failed"),
        )
    }
  }

  suspend fun decryptControl(ciphertext: ByteArray): Result<ByteArray> = mutex.withLock {
    if (state !is SecureChannelState.Connected) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_NOT_INITIALIZED, "Channel not connected"),
        ),
      )
    }

    crypto.decryptControl(ciphertext)?.let { Result.success(it) }
      ?: Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_DECRYPT_FAILED, "Control decryption failed"),
        ),
      )
  }

  suspend fun decryptData(ciphertext: ByteArray): Result<ByteArray> = mutex.withLock {
    if (state !is SecureChannelState.Connected) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_NOT_INITIALIZED, "Channel not connected"),
        ),
      )
    }

    crypto.decryptData(ciphertext)?.let { Result.success(it) }
      ?: Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_DECRYPT_FAILED, "Data decryption failed"),
        ),
      )
  }

  suspend fun rotateKeys(): Result<Unit> = mutex.withLock {
    rotateKeysInternal()
  }

  private suspend fun rotateKeysInternal(): Result<Unit> {
    val handshake = binderClient.rotateChannelKeys().getOrElse { e ->
      return Result.failure(
        SecureChannelException(
          DaemonError.security(
            ErrorCode.CRYPTO_KEY_ROTATION_FAILED,
            "Key rotation failed: ${e.message}",
          ),
          e,
        ),
      )
    }

    if (handshake.errorCode != 0) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(
            ErrorCode.CRYPTO_KEY_ROTATION_FAILED,
            handshake.errorMessage ?: "Unknown error",
          ),
        ),
      )
    }

    if (!crypto.rotateKeys()) {
      return Result.failure(
        SecureChannelException(
          DaemonError.security(ErrorCode.CRYPTO_KEY_ROTATION_FAILED, "Local key rotation failed"),
        ),
      )
    }

    val currentState = state
    if (currentState is SecureChannelState.Connected) {
      state = currentState.copy(keyGeneration = handshake.keyGeneration)
    }

    Log.d(TAG, "Keys rotated, generation: ${handshake.keyGeneration}")
    return Result.success(Unit)
  }

  suspend fun close() = mutex.withLock {
    state = SecureChannelState.Disconnected
    sessionId = null
    Log.d(TAG, "Secure channel closed")
  }

  fun getStats(): DualChannelCrypto.Stats = crypto.getStats()

  companion object {
    private const val TAG = "SecureChannel"
    private const val KEY_SIZE = 32

    fun deriveChannelSecret(
      sharedSecret: ByteArray,
      clientPublic: ByteArray,
      serverPublic: ByteArray,
    ): ByteArray {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
      mac.update(clientPublic)
      mac.update(serverPublic)
      mac.update("FutonChannelSecret".toByteArray())
      return mac.doFinal().copyOf(KEY_SIZE)
    }
  }
}

class SecureChannelException(
  val error: DaemonError,
  cause: Throwable? = null,
) : Exception(error.message, cause)
