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
package me.fleey.futon.util

import java.io.InputStream
import java.security.MessageDigest

private const val BUFFER_SIZE = 8192
private const val HEX_RADIX = 16

/**
 * Computes the SHA-256 hash of the data from an input stream.
 *
 * @param inputStream The input stream to read data from. The stream is NOT closed by this function.
 * @return The SHA-256 hash as a lowercase hexadecimal string.
 */
fun computeSha256(inputStream: InputStream): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(BUFFER_SIZE)
  var bytesRead: Int

  while (inputStream.read(buffer).also { bytesRead = it } != -1) {
    digest.update(buffer, 0, bytesRead)
  }

  return digest.digest().toHexString()
}

/**
 * Verifies that the SHA-256 hash of the data from an input stream matches the expected hash.
 *
 * @param inputStream The input stream to read data from. The stream is NOT closed by this function.
 * @param expectedHash The expected SHA-256 hash as a hexadecimal string (case-insensitive).
 * @return True if the computed hash matches the expected hash, false otherwise.
 */
fun verifySha256(inputStream: InputStream, expectedHash: String): Boolean {
  val computedHash = computeSha256(inputStream)
  return computedHash.equals(expectedHash, ignoreCase = true)
}

/**
 * Converts a byte array to a lowercase hexadecimal string.
 */
private fun ByteArray.toHexString(): String =
  joinToString("") { byte ->
    byte.toInt().and(0xFF).toString(HEX_RADIX).padStart(2, '0')
  }
