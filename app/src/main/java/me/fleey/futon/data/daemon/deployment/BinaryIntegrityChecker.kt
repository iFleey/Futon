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
package me.fleey.futon.data.daemon.deployment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

interface BinaryIntegrityChecker {
  val binaryIntegrity: StateFlow<IntegrityState>

  suspend fun checkIntegrity(expectedHash: String): IntegrityState
  suspend fun computeHash(filePath: String): String?
  fun reset()
}

@Single(binds = [BinaryIntegrityChecker::class])
class BinaryIntegrityCheckerImpl(
  private val rootShell: RootShell,
) : BinaryIntegrityChecker {

  private val _binaryIntegrity = MutableStateFlow<IntegrityState>(IntegrityState.Unknown)
  override val binaryIntegrity: StateFlow<IntegrityState> = _binaryIntegrity.asStateFlow()

  override suspend fun checkIntegrity(expectedHash: String): IntegrityState =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()

      try {
        val daemonPath = DaemonConfig.BINARY_PATH
        val file = File(daemonPath)

        if (!file.exists()) {
          val state = IntegrityState.CheckFailed("Binary not found at $daemonPath")
          _binaryIntegrity.value = state
          return@withContext state
        }

        val actualHash = computeHashWithMemoryMap(daemonPath)
          ?: computeHashWithRoot(daemonPath)

        if (actualHash == null) {
          val state = IntegrityState.CheckFailed("Failed to compute hash")
          _binaryIntegrity.value = state
          return@withContext state
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > DaemonConfig.Timeouts.INTEGRITY_CHECK_MS) {
          Log.w(
            TAG,
            "Integrity check took ${elapsed}ms (target: ${DaemonConfig.Timeouts.INTEGRITY_CHECK_MS}ms)",
          )
        }

        val state = if (actualHash.equals(expectedHash, ignoreCase = true)) {
          IntegrityState.Verified
        } else {
          IntegrityState.Tampered
        }

        _binaryIntegrity.value = state
        state
      } catch (e: Exception) {
        val state = IntegrityState.CheckFailed("Check failed: ${e.message}")
        _binaryIntegrity.value = state
        state
      }
    }

  override suspend fun computeHash(filePath: String): String? =
    withContext(Dispatchers.IO) {
      computeHashWithMemoryMap(filePath) ?: computeHashWithRoot(filePath)
    }

  override fun reset() {
    _binaryIntegrity.value = IntegrityState.Unknown
  }

  private fun computeHashWithMemoryMap(filePath: String): String? {
    return try {
      val file = File(filePath)
      if (!file.canRead()) return null

      RandomAccessFile(file, "r").use { raf ->
        val channel = raf.channel
        val size = channel.size()

        if (size > Int.MAX_VALUE) {
          return computeHashInChunks(channel, size)
        }

        val buffer: MappedByteBuffer = channel.map(
          FileChannel.MapMode.READ_ONLY,
          0,
          size,
        )

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer)
        bytesToHex(digest.digest())
      }
    } catch (e: Exception) {
      null
    }
  }

  private fun computeHashInChunks(channel: FileChannel, size: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val chunkSize = 64 * 1024 * 1024L // 64MB chunks
    var position = 0L

    while (position < size) {
      val remaining = size - position
      val mapSize = minOf(chunkSize, remaining)
      val buffer = channel.map(FileChannel.MapMode.READ_ONLY, position, mapSize)
      digest.update(buffer)
      position += mapSize
    }

    return bytesToHex(digest.digest())
  }

  private suspend fun computeHashWithRoot(filePath: String): String? {
    val result = rootShell.execute("sha256sum $filePath | cut -d' ' -f1")
    return if (result.isSuccess()) {
      (result as me.fleey.futon.platform.root.ShellResult.Success).output.trim()
        .takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
    } else {
      null
    }
  }

  private fun bytesToHex(bytes: ByteArray): String = bytes.toHexString()

  companion object {
    private const val TAG = "BinaryIntegrityChecker"
  }
}
