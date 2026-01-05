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

import me.fleey.futon.data.localmodel.models.DownloadedModel

/**
 * Progress state for model import operations.
 */
sealed class ImportProgress {

  /**
   * Validating the GGUF file format.
   */
  data object Validating : ImportProgress()

  data object CheckingStorage : ImportProgress()

  /**
   * Copying files to the model directory.
   *
   * @property currentFile Name of the file being copied
   * @property copiedBytes Bytes copied so far
   * @property totalBytes Total bytes to copy
   * @property progress Progress percentage (0.0 to 1.0)
   */
  data class Copying(
    val currentFile: String,
    val copiedBytes: Long,
    val totalBytes: Long,
    val progress: Float,
  ) : ImportProgress() {
    val progressPercent: Int
      get() = (progress * 100).toInt()
  }

  /**
   * Registering the imported model in the registry.
   */
  data object Registering : ImportProgress()

  data class Completed(val model: DownloadedModel) : ImportProgress()

  data class Failed(val error: ImportError) : ImportProgress()
}

sealed class ImportError {

  data class InvalidGgufFile(
    val path: String,
    val reason: String,
  ) : ImportError() {
    override fun toString(): String = "Invalid GGUF file: $reason"
  }

  data class InvalidMmprojFile(
    val path: String,
    val reason: String,
  ) : ImportError() {
    override fun toString(): String = "Invalid mmproj file: $reason"
  }

  /**
   * A Vision-Language Model was imported without the required mmproj file.
   */
  data class MmprojRequired(
    val modelPath: String,
  ) : ImportError() {
    override fun toString(): String =
      "This is a vision-language model that requires an mmproj file. " +
        "Please provide the mmproj file to complete the import."
  }

  data class FileNotFound(
    val path: String,
  ) : ImportError() {
    override fun toString(): String = "File not found: $path"
  }

  data class InsufficientStorage(
    val requiredBytes: Long,
    val availableBytes: Long,
  ) : ImportError() {
    override fun toString(): String =
      "Insufficient storage: need ${formatBytes(requiredBytes)}, " +
        "available ${formatBytes(availableBytes)}"

    private fun formatBytes(bytes: Long): String = when {
      bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
      bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
      else -> "%.1fKB".format(bytes / 1_000.0)
    }
  }

  data class CopyError(
    val reason: String,
  ) : ImportError() {
    override fun toString(): String = "Failed to copy files: $reason"
  }

  data class RegistrationError(
    val reason: String,
  ) : ImportError() {
    override fun toString(): String = "Failed to register model: $reason"
  }

  data class UnknownError(
    val reason: String,
  ) : ImportError() {
    override fun toString(): String = "Import failed: $reason"
  }
}

sealed class ImportResult {
  data class Success(val model: DownloadedModel) : ImportResult()

  data class Failure(val error: ImportError) : ImportResult()
}
