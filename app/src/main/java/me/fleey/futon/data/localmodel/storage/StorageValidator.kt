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
package me.fleey.futon.data.localmodel.storage

import java.io.File

/**
 * Validates storage space before model operations.
 */
interface StorageValidator {
  suspend fun checkStorageForDownload(requiredBytes: Long): StorageCheckResult

  suspend fun getAvailableStorage(): Long

  fun getModelStorageDirectory(): File
}

sealed class StorageCheckResult {
  data object Sufficient : StorageCheckResult()

  data class Insufficient(
    val requiredBytes: Long,
    val availableBytes: Long,
    val bufferBytes: Long = BUFFER_SIZE_BYTES,
  ) : StorageCheckResult() {

    val totalRequired: Long
      get() = requiredBytes + bufferBytes

    /**
     * How many bytes are missing.
     */
    val shortfall: Long
      get() = totalRequired - availableBytes

    val requiredFormatted: String
      get() = formatBytes(requiredBytes)

    val availableFormatted: String
      get() = formatBytes(availableBytes)

    val totalRequiredFormatted: String
      get() = formatBytes(totalRequired)

    private fun formatBytes(bytes: Long): String = when {
      bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
      bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
      bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
      else -> "${bytes}B"
    }
  }

  companion object {
    /**
     * Default buffer size: 500MB
     */
    const val BUFFER_SIZE_BYTES: Long = 500L * 1024L * 1024L
  }
}
