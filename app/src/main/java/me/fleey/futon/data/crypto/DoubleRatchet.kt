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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MessageHeader(
  val dhPublic: ByteArray,
  val prevChainLen: Int,
  val messageNum: Int,
) {
  fun serialize(): ByteArray {
    val buffer = ByteBuffer.allocate(DH_PUBLIC_KEY_SIZE + 8).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(dhPublic)
    buffer.putInt(prevChainLen)
    buffer.putInt(messageNum)
    return buffer.array()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MessageHeader) return false
    return dhPublic.contentEquals(other.dhPublic) &&
      prevChainLen == other.prevChainLen &&
      messageNum == other.messageNum
  }

  override fun hashCode(): Int = dhPublic.contentHashCode() * 31 + prevChainLen * 17 + messageNum

  companion object {
    fun deserialize(data: ByteArray): MessageHeader? {
      if (data.size < DH_PUBLIC_KEY_SIZE + 8) return null
      val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val dhPublic = ByteArray(DH_PUBLIC_KEY_SIZE)
      buffer.get(dhPublic)
      return MessageHeader(dhPublic, buffer.int, buffer.int)
    }
  }
}


data class EncryptedMessage(
  val header: MessageHeader,
  val ciphertext: ByteArray,
) {
  fun serialize(): ByteArray {
    val headerData = header.serialize()
    val buffer = ByteBuffer.allocate(4 + headerData.size + ciphertext.size)
      .order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(headerData.size)
    buffer.put(headerData)
    buffer.put(ciphertext)
    return buffer.array()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EncryptedMessage) return false
    return header == other.header && ciphertext.contentEquals(other.ciphertext)
  }

  override fun hashCode(): Int = header.hashCode() * 31 + ciphertext.contentHashCode()

  companion object {
    fun deserialize(data: ByteArray): EncryptedMessage? {
      if (data.size < 4) return null
      val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val headerLen = buffer.int
      if (data.size < 4 + headerLen) return null

      val headerData = ByteArray(headerLen)
      buffer.get(headerData)
      val header = MessageHeader.deserialize(headerData) ?: return null

      val ciphertext = ByteArray(data.size - 4 - headerLen)
      buffer.get(ciphertext)
      return EncryptedMessage(header, ciphertext)
    }
  }
}

private data class SkippedKey(
  val dhPublic: ByteArray,
  val messageNum: Int,
  val messageKey: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SkippedKey) return false
    return dhPublic.contentEquals(other.dhPublic) && messageNum == other.messageNum
  }

  override fun hashCode(): Int = dhPublic.contentHashCode() * 31 + messageNum
}

data class DHKeyPair(
  val publicKey: ByteArray,
  val privateKey: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DHKeyPair) return false
    return publicKey.contentEquals(other.publicKey)
  }

  override fun hashCode(): Int = publicKey.contentHashCode()

  companion object {
    fun generate(): DHKeyPair {
      val kpg = KeyPairGenerator.getInstance("X25519")
      val kp = kpg.generateKeyPair()
      return DHKeyPair(
        publicKey = extractRawPublicKey(kp.public.encoded),
        privateKey = extractRawPrivateKey(kp.private.encoded),
      )
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
      val kf = KeyFactory.getInstance("X25519")
      val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(wrapPrivateKey(privateKey)))
      val pubKey = kf.generatePublic(X509EncodedKeySpec(wrapPublicKey(publicKey)))

      val ka = KeyAgreement.getInstance("X25519")
      ka.init(privKey)
      ka.doPhase(pubKey, true)
      return ka.generateSecret()
    }

    private fun extractRawPublicKey(encoded: ByteArray): ByteArray =
      encoded.copyOfRange(encoded.size - 32, encoded.size)

    private fun extractRawPrivateKey(encoded: ByteArray): ByteArray =
      encoded.copyOfRange(encoded.size - 32, encoded.size)

    private fun wrapPublicKey(raw: ByteArray): ByteArray =
      byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00) + raw

    private fun wrapPrivateKey(raw: ByteArray): ByteArray =
      byteArrayOf(
        0x30,
        0x2e,
        0x02,
        0x01,
        0x00,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x6e,
        0x04,
        0x22,
        0x04,
        0x20,
      ) + raw
  }
}


class DoubleRatchet {
  private val random = SecureRandom()

  private var dhSelf: DHKeyPair? = null
  private var dhRemote: ByteArray? = null
  private var rootKey: ByteArray? = null
  private var chainKeySend: ByteArray? = null
  private var chainKeyRecv: ByteArray? = null

  private var sendCount = 0
  private var recvCount = 0
  private var prevSendCount = 0

  private val skippedKeys = mutableListOf<SkippedKey>()

  // Anti-replay: track received message numbers per DH public key
  private val receivedMessages = mutableMapOf<ByteArrayWrapper, MutableSet<Int>>()

  private var sessionMasterKey = ByteArray(KEY_SIZE)
  private var sessionKeyGeneration = 0L

  private var messagesSent = 0L
  private var messagesReceived = 0L
  private var ratchetSteps = 0L

  @Volatile
  private var initialized = false

  // Wrapper for ByteArray to use as Map key
  private class ByteArrayWrapper(val data: ByteArray) {
    override fun equals(other: Any?) = other is ByteArrayWrapper && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
  }

  @Synchronized
  fun initAlice(sharedSecret: ByteArray, bobPublic: ByteArray): Boolean {
    if (sharedSecret.size < KEY_SIZE) {
      Log.e(TAG, "Shared secret too short")
      return false
    }

    clearSensitive()
    dhSelf = DHKeyPair.generate()
    dhRemote = bobPublic.copyOf()
    rootKey = sharedSecret.copyOf(KEY_SIZE)

    val dhOut = DHKeyPair.dh(dhSelf!!.privateKey, bobPublic)
    val (newRk, ck) = kdfRk(rootKey!!, dhOut)
    rootKey = newRk
    chainKeySend = ck
    secureZero(dhOut)

    deriveSessionMasterKey()
    sendCount = 0
    recvCount = 0
    prevSendCount = 0
    initialized = true
    ratchetSteps++

    Log.d(TAG, "Initialized as Alice")
    return true
  }

  @Synchronized
  fun initBob(sharedSecret: ByteArray, bobKeyPair: DHKeyPair): Boolean {
    if (sharedSecret.size < KEY_SIZE) {
      Log.e(TAG, "Shared secret too short")
      return false
    }

    clearSensitive()
    dhSelf = bobKeyPair
    dhRemote = null
    rootKey = sharedSecret.copyOf(KEY_SIZE)

    sendCount = 0
    recvCount = 0
    prevSendCount = 0
    initialized = true

    Log.d(TAG, "Initialized as Bob")
    return true
  }

  @Synchronized
  fun encrypt(plaintext: ByteArray): EncryptedMessage? {
    if (!initialized || chainKeySend == null) {
      Log.e(TAG, "Not initialized for sending")
      return null
    }

    val (newCk, mk) = kdfCk(chainKeySend!!)
    chainKeySend = newCk

    val header = MessageHeader(dhSelf!!.publicKey, prevSendCount, sendCount)
    val ad = header.serialize()
    val ciphertext = aeadEncrypt(mk, plaintext, ad)
    secureZero(mk)

    if (ciphertext == null) {
      Log.e(TAG, "AEAD encryption failed")
      return null
    }

    sendCount++
    messagesSent++
    return EncryptedMessage(header, ciphertext)
  }

  @Synchronized
  fun decrypt(message: EncryptedMessage): ByteArray? {
    if (!initialized) {
      Log.e(TAG, "Not initialized")
      return null
    }

    // Anti-replay check: reject if we've seen this exact (dhPublic, messageNum) before
    val dhKey = ByteArrayWrapper(message.header.dhPublic)
    val seenMessages = receivedMessages.getOrPut(dhKey) { mutableSetOf() }
    if (message.header.messageNum in seenMessages) {
      Log.w(TAG, "Replay attack detected: message ${message.header.messageNum} already received")
      return null
    }

    // Try skipped keys first (for out-of-order messages)
    trySkippedKeys(message.header, message.ciphertext)?.let {
      seenMessages.add(message.header.messageNum)
      messagesReceived++
      return it
    }

    if (dhRemote == null || !message.header.dhPublic.contentEquals(dhRemote)) {
      if (chainKeyRecv != null && dhRemote != null) {
        skipMessageKeys(message.header.prevChainLen)
      }
      dhRatchet(message.header.dhPublic)
      // Clear old received messages for previous DH key (they're now invalid)
      receivedMessages.entries.removeIf { !it.key.data.contentEquals(message.header.dhPublic) }
    }

    skipMessageKeys(message.header.messageNum)

    if (chainKeyRecv == null) {
      Log.e(TAG, "No receiving chain key")
      return null
    }

    val (newCk, mk) = kdfCk(chainKeyRecv!!)
    chainKeyRecv = newCk

    val ad = message.header.serialize()
    val plaintext = aeadDecrypt(mk, message.ciphertext, ad)
    secureZero(mk)

    if (plaintext == null) {
      Log.e(TAG, "AEAD decryption failed - message tampered or wrong key")
      return null
    }

    // Mark message as received (anti-replay)
    seenMessages.add(message.header.messageNum)
    recvCount++
    messagesReceived++
    return plaintext
  }

  @Synchronized
  fun getSessionMasterKey(): ByteArray = sessionMasterKey.copyOf()

  @Synchronized
  fun getSessionKeyGeneration(): Long = sessionKeyGeneration

  @Synchronized
  fun forceRatchetStep(): Boolean {
    if (!initialized || dhRemote == null) return false

    dhSelf = DHKeyPair.generate()
    val dhOut = DHKeyPair.dh(dhSelf!!.privateKey, dhRemote!!)
    val (newRk, ck) = kdfRk(rootKey!!, dhOut)
    rootKey = newRk
    chainKeySend = ck
    secureZero(dhOut)

    prevSendCount = sendCount
    sendCount = 0
    deriveSessionMasterKey()
    ratchetSteps++

    Log.d(TAG, "Forced ratchet step, generation: $sessionKeyGeneration")
    return true
  }

  @Synchronized
  fun getPublicKey(): ByteArray? = dhSelf?.publicKey?.copyOf()

  fun isInitialized(): Boolean = initialized

  data class Stats(
    val messagesSent: Long,
    val messagesReceived: Long,
    val ratchetSteps: Long,
    val skippedKeysCount: Int,
  )

  @Synchronized
  fun getStats(): Stats = Stats(messagesSent, messagesReceived, ratchetSteps, skippedKeys.size)


  private fun dhRatchet(remotePublic: ByteArray) {
    prevSendCount = sendCount
    sendCount = 0
    recvCount = 0
    dhRemote = remotePublic.copyOf()

    var dhOut = DHKeyPair.dh(dhSelf!!.privateKey, remotePublic)
    var (newRk, ck) = kdfRk(rootKey!!, dhOut)
    rootKey = newRk
    chainKeyRecv = ck
    secureZero(dhOut)

    dhSelf = DHKeyPair.generate()
    dhOut = DHKeyPair.dh(dhSelf!!.privateKey, remotePublic)
    val (newRk2, ck2) = kdfRk(rootKey!!, dhOut)
    rootKey = newRk2
    chainKeySend = ck2
    secureZero(dhOut)

    deriveSessionMasterKey()
    ratchetSteps++
  }

  private fun skipMessageKeys(until: Int) {
    if (chainKeyRecv == null) return
    if (recvCount + MAX_SKIP < until) {
      Log.w(TAG, "Too many skipped messages: ${until - recvCount}")
      return
    }

    while (recvCount < until) {
      val (newCk, mk) = kdfCk(chainKeyRecv!!)
      skippedKeys.add(SkippedKey(dhRemote!!.copyOf(), recvCount, mk))
      chainKeyRecv = newCk
      recvCount++

      if (skippedKeys.size > MAX_SKIP) {
        secureZero(skippedKeys.first().messageKey)
        skippedKeys.removeAt(0)
      }
    }
  }

  private fun trySkippedKeys(header: MessageHeader, ciphertext: ByteArray): ByteArray? {
    val iter = skippedKeys.iterator()
    while (iter.hasNext()) {
      val sk = iter.next()
      if (sk.dhPublic.contentEquals(header.dhPublic) && sk.messageNum == header.messageNum) {
        val ad = header.serialize()
        val plaintext = aeadDecrypt(sk.messageKey, ciphertext, ad)
        secureZero(sk.messageKey)
        iter.remove()
        return plaintext
      }
    }
    return null
  }

  private fun deriveSessionMasterKey() {
    if (chainKeySend == null || rootKey == null) return
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(rootKey, "HmacSHA256"))
    mac.update(chainKeySend)
    mac.update(INFO_SMK)
    sessionMasterKey = mac.doFinal().copyOf(KEY_SIZE)
    sessionKeyGeneration++
  }

  private fun clearSensitive() {
    dhSelf?.privateKey?.let { secureZero(it) }
    rootKey?.let { secureZero(it) }
    chainKeySend?.let { secureZero(it) }
    chainKeyRecv?.let { secureZero(it) }
    secureZero(sessionMasterKey)
    skippedKeys.forEach { secureZero(it.messageKey) }
    skippedKeys.clear()
    receivedMessages.clear()
  }

  companion object {
    private const val TAG = "DoubleRatchet"

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(rk, "HmacSHA256"))
      mac.update(dhOut)
      mac.update(INFO_RK)
      val derived = mac.doFinal()

      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(derived)
      digest.update(0x01)
      val key1 = digest.digest()

      digest.reset()
      digest.update(derived)
      digest.update(0x02)
      val key2 = digest.digest()

      secureZero(derived)
      return Pair(key1, key2)
    }

    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(ck, "HmacSHA256"))
      mac.update(INFO_CK)
      mac.update(0x01)
      val newChainKey = mac.doFinal()

      mac.reset()
      mac.init(SecretKeySpec(ck, "HmacSHA256"))
      mac.update(INFO_CK)
      mac.update(0x02)
      val messageKey = mac.doFinal()

      return Pair(newChainKey, messageKey)
    }

    private fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray? {
      return try {
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
          Cipher.ENCRYPT_MODE,
          SecretKeySpec(key, "AES"),
          GCMParameterSpec(TAG_SIZE * 8, nonce),
        )
        cipher.updateAAD(ad)
        nonce + cipher.doFinal(plaintext)
      } catch (e: Exception) {
        Log.e(TAG, "AEAD encrypt failed", e)
        null
      }
    }

    private fun aeadDecrypt(key: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
      if (ciphertext.size < NONCE_SIZE + TAG_SIZE) return null
      return try {
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE)
        val ct = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
          Cipher.DECRYPT_MODE,
          SecretKeySpec(key, "AES"),
          GCMParameterSpec(TAG_SIZE * 8, nonce),
        )
        cipher.updateAAD(ad)
        cipher.doFinal(ct)
      } catch (e: Exception) {
        Log.e(TAG, "AEAD decrypt failed", e)
        null
      }
    }

    private fun secureZero(data: ByteArray) = data.fill(0)
  }
}

private const val KEY_SIZE = 32
private const val NONCE_SIZE = 12
private const val TAG_SIZE = 16
private const val DH_PUBLIC_KEY_SIZE = 32
private const val MAX_SKIP = 1000

private val INFO_RK = "FutonRatchetRK".toByteArray()
private val INFO_CK = "FutonRatchetCK".toByteArray()
private val INFO_SMK = "FutonSessionMK".toByteArray()
