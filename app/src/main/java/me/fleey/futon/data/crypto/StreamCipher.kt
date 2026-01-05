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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StreamCipherConfig(
  val rotationBytes: Long = 10 * 1024 * 1024,
  val rotationSeconds: Int = 300,
  val chunkSize: Int = 64 * 1024,
)

data class ChunkHeader(
  val keyGeneration: Long,
  val chunkIndex: Int,
  val chunkSize: Int,
  val flags: Int = 0,
) {
  fun serialize(): ByteArray {
    val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(keyGeneration)
    buffer.putInt(chunkIndex)
    buffer.putInt(chunkSize)
    buffer.putInt(flags)
    return buffer.array()
  }

  companion object {
    const val SIZE = 20

    fun deserialize(data: ByteArray, offset: Int = 0): ChunkHeader? {
      if (data.size - offset < SIZE) return null
      val buffer = ByteBuffer.wrap(data, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN)
      return ChunkHeader(buffer.long, buffer.int, buffer.int, buffer.int)
    }
  }
}

private class StreamKey(
  val key: ByteArray,
  val generation: Long,
  val createdAt: Long = System.nanoTime(),
) {
  @Volatile
  var bytesEncrypted: Long = 0
}


class StreamCipher(private val config: StreamCipherConfig = StreamCipherConfig()) {
  private val random = SecureRandom()

  private var currentKey: StreamKey? = null
  private var previousKey: StreamKey? = null

  @Volatile
  private var totalEncrypted = 0L

  @Volatile
  private var totalDecrypted = 0L

  @Volatile
  private var rotations = 0L

  private var sendChunkIndex = 0

  private var rotationCallback: ((Long) -> Unit)? = null

  @Synchronized
  fun init(sessionMasterKey: ByteArray, generation: Long): Boolean {
    currentKey = StreamKey(deriveStreamKey(sessionMasterKey, generation), generation)
    sendChunkIndex = 0
    Log.d(TAG, "Initialized, generation: $generation")
    return true
  }

  @Synchronized
  fun updateKey(newSessionMasterKey: ByteArray, generation: Long): Boolean {
    currentKey?.let {
      previousKey?.key?.fill(0)
      previousKey = it
    }
    currentKey = StreamKey(deriveStreamKey(newSessionMasterKey, generation), generation)
    sendChunkIndex = 0
    rotations++
    rotationCallback?.invoke(generation)
    Log.d(TAG, "Key updated, generation: $generation")
    return true
  }

  @Synchronized
  fun encrypt(data: ByteArray): ByteArray? {
    val key = currentKey ?: run {
      Log.e(TAG, "Not initialized")
      return null
    }

    val output = ByteArray(calculateEncryptedSize(data.size))
    var outOffset = 0
    var inOffset = 0

    while (inOffset < data.size) {
      val chunkSize = minOf(config.chunkSize, data.size - inOffset)
      val chunk = encryptChunk(key, data, inOffset, chunkSize, sendChunkIndex++) ?: return null
      System.arraycopy(chunk, 0, output, outOffset, chunk.size)
      outOffset += chunk.size
      inOffset += chunkSize
    }

    return output.copyOf(outOffset)
  }

  @Synchronized
  fun decrypt(data: ByteArray): ByteArray? {
    if (currentKey == null) {
      Log.e(TAG, "Not initialized")
      return null
    }

    val output = mutableListOf<Byte>()
    var offset = 0

    while (offset < data.size) {
      val header = ChunkHeader.deserialize(data, offset) ?: run {
        Log.e(TAG, "Invalid chunk header at offset $offset")
        return null
      }
      offset += ChunkHeader.SIZE

      val encryptedChunkSize = NONCE_SIZE + header.chunkSize + TAG_SIZE
      if (offset + encryptedChunkSize > data.size) {
        Log.e(TAG, "Incomplete encrypted chunk")
        return null
      }

      val chunk = decryptChunk(header, data, offset, encryptedChunkSize) ?: return null
      output.addAll(chunk.toList())
      offset += encryptedChunkSize
    }

    return output.toByteArray()
  }

  fun needsRotation(): Boolean {
    val key = currentKey ?: return false
    if (key.bytesEncrypted >= config.rotationBytes) return true
    val elapsedSeconds = (System.nanoTime() - key.createdAt) / 1_000_000_000
    return elapsedSeconds >= config.rotationSeconds
  }

  fun getKeyGeneration(): Long = currentKey?.generation ?: 0
  fun getBytesEncrypted(): Long = currentKey?.bytesEncrypted ?: 0

  data class Stats(
    val totalBytesEncrypted: Long,
    val totalBytesDecrypted: Long,
    val keyRotations: Long,
    val currentGeneration: Long,
  )

  fun getStats(): Stats =
    Stats(totalEncrypted, totalDecrypted, rotations, currentKey?.generation ?: 0)


  private fun calculateEncryptedSize(plainSize: Int): Int {
    val numChunks = (plainSize + config.chunkSize - 1) / config.chunkSize
    return numChunks * (ChunkHeader.SIZE + NONCE_SIZE + TAG_SIZE) + plainSize
  }

  private fun encryptChunk(
    key: StreamKey,
    data: ByteArray,
    offset: Int,
    size: Int,
    index: Int,
  ): ByteArray? {
    return try {
      val header = ChunkHeader(key.generation, index, size)
      val headerBytes = header.serialize()

      val nonce = ByteArray(NONCE_SIZE)
      random.nextBytes(nonce)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key.key, "AES"),
        GCMParameterSpec(TAG_SIZE * 8, nonce),
      )
      cipher.updateAAD(headerBytes)

      val ciphertext = cipher.doFinal(data, offset, size)
      key.bytesEncrypted += size
      totalEncrypted += size

      headerBytes + nonce + ciphertext
    } catch (e: Exception) {
      Log.e(TAG, "Failed to encrypt chunk", e)
      null
    }
  }

  private fun decryptChunk(
    header: ChunkHeader,
    data: ByteArray,
    offset: Int,
    size: Int,
  ): ByteArray? {
    val key = when {
      currentKey?.generation == header.keyGeneration -> currentKey
      previousKey?.generation == header.keyGeneration -> previousKey
      else -> {
        Log.e(TAG, "No key for generation ${header.keyGeneration}")
        return null
      }
    } ?: return null

    if (size < NONCE_SIZE + TAG_SIZE) return null

    return try {
      val nonce = data.copyOfRange(offset, offset + NONCE_SIZE)
      val ciphertext = data.copyOfRange(offset + NONCE_SIZE, offset + size)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key.key, "AES"),
        GCMParameterSpec(TAG_SIZE * 8, nonce),
      )
      cipher.updateAAD(header.serialize())

      val plaintext = cipher.doFinal(ciphertext)
      totalDecrypted += plaintext.size
      plaintext
    } catch (e: Exception) {
      Log.e(TAG, "Stream cipher authentication failed", e)
      null
    }
  }

  companion object {
    private const val TAG = "StreamCipher"
    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    private fun deriveStreamKey(masterKey: ByteArray, generation: Long): ByteArray {
      val salt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(generation).array()
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt, "HmacSHA256"))
      mac.update(masterKey)
      mac.update("FutonStreamKey".toByteArray())
      return mac.doFinal().copyOf(KEY_SIZE)
    }
  }
}
