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
package me.fleey.futon.data.crypto

import android.util.Log

/**
 * Dual-channel crypto manager combining Double Ratchet (control) and Stream Cipher (data).
 *
 * Control Channel: Full Double Ratchet with per-message keys and forward secrecy.
 * Data Channel: AES-256-GCM stream cipher with session key derived from Double Ratchet.
 */
class DualChannelCrypto {
  private val controlChannel = DoubleRatchet()
  private val dataChannel = StreamCipher()

  @Volatile
  private var initialized = false

  @Synchronized
  fun initInitiator(sharedSecret: ByteArray, responderPublic: ByteArray): Boolean {
    if (!controlChannel.initAlice(sharedSecret, responderPublic)) {
      Log.e(TAG, "Failed to initialize control channel as initiator")
      return false
    }
    syncDataChannelKey()
    initialized = true
    Log.d(TAG, "Initialized as initiator")
    return true
  }

  @Synchronized
  fun initResponder(sharedSecret: ByteArray, ourKeyPair: DHKeyPair): Boolean {
    if (!controlChannel.initBob(sharedSecret, ourKeyPair)) {
      Log.e(TAG, "Failed to initialize control channel as responder")
      return false
    }
    initialized = true
    Log.d(TAG, "Initialized as responder")
    return true
  }

  @Synchronized
  fun encryptControl(data: ByteArray): ByteArray? {
    val encrypted = controlChannel.encrypt(data) ?: return null
    syncDataChannelKey()
    return encrypted.serialize()
  }

  @Synchronized
  fun decryptControl(data: ByteArray): ByteArray? {
    val msg = EncryptedMessage.deserialize(data) ?: run {
      Log.e(TAG, "Failed to deserialize control message")
      return null
    }
    val decrypted = controlChannel.decrypt(msg) ?: return null
    syncDataChannelKey()
    return decrypted
  }

  @Synchronized
  fun encryptData(data: ByteArray): ByteArray? {
    if (dataChannel.needsRotation()) {
      Log.d(TAG, "Data channel key rotation triggered")
      controlChannel.forceRatchetStep()
      syncDataChannelKey()
    }
    return dataChannel.encrypt(data)
  }

  @Synchronized
  fun decryptData(data: ByteArray): ByteArray? = dataChannel.decrypt(data)

  @Synchronized
  fun rotateKeys(): Boolean {
    if (!controlChannel.forceRatchetStep()) return false
    syncDataChannelKey()
    return true
  }

  fun dataChannelNeedsRotation(): Boolean = dataChannel.needsRotation()

  fun getPublicKey(): ByteArray? = controlChannel.getPublicKey()

  fun isInitialized(): Boolean = initialized

  data class Stats(
    val controlStats: DoubleRatchet.Stats,
    val dataStats: StreamCipher.Stats,
  )

  fun getStats(): Stats = Stats(controlChannel.getStats(), dataChannel.getStats())

  private fun syncDataChannelKey() {
    val sessionKey = controlChannel.getSessionMasterKey()
    val generation = controlChannel.getSessionKeyGeneration()

    if (generation == 0L) return

    if (dataChannel.getKeyGeneration() < generation) {
      if (dataChannel.getKeyGeneration() == 0L) {
        dataChannel.init(sessionKey, generation)
      } else {
        dataChannel.updateKey(sessionKey, generation)
      }
    }
    sessionKey.fill(0)
  }

  companion object {
    private const val TAG = "DualChannelCrypto"
  }
}
