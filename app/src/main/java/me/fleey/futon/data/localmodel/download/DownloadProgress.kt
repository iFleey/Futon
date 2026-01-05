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
package me.fleey.futon.data.localmodel.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.data.localmodel.models.QuantizationType

/**
 * Overall download progress for a model (may include multiple files).
 */
@Serializable
data class DownloadProgress(
  @SerialName("model_id")
  val modelId: String,

  val state: DownloadState,

  val files: List<FileProgress>,
) {
  /**
   * Overall progress across all files (0.0 to 1.0).
   */
  val overallProgress: Float
    get() {
      if (files.isEmpty()) return 0f
      val totalBytes = files.sumOf { it.totalBytes }
      if (totalBytes == 0L) return 0f
      val downloadedBytes = files.sumOf { it.downloadedBytes }
      return (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

  /**
   * Overall progress as percentage (0-100).
   */
  val overallProgressPercent: Int
    get() = (overallProgress * 100).toInt()

  val totalDownloadedBytes: Long
    get() = files.sumOf { it.downloadedBytes }

  val totalSizeBytes: Long
    get() = files.sumOf { it.totalBytes }

  val isComplete: Boolean
    get() = files.isNotEmpty() && files.all { it.isComplete }
}

/**
 * Download progress for a single file.
 */
@Serializable
data class FileProgress(
  @SerialName("file_name")
  val fileName: String,

  @SerialName("downloaded_bytes")
  val downloadedBytes: Long,

  @SerialName("total_bytes")
  val totalBytes: Long,

  @SerialName("is_complete")
  val isComplete: Boolean,
) {
  /**
   * Progress as a value between 0.0 and 1.0.
   */
  val progress: Float
    get() = if (totalBytes > 0) {
      (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else 0f

  /**
   * Progress as a percentage (0-100).
   */
  val progressPercent: Int
    get() = (progress * 100).toInt()

  val downloadedFormatted: String
    get() = formatBytes(downloadedBytes)

  val totalFormatted: String
    get() = formatBytes(totalBytes)

  private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "${bytes}B"
  }
}

/**
 * Represents a persisted active download operation for serialization.
 * This is different from the runtime ActiveDownload in ModelDownloader.kt.
 */
@Serializable
data class PersistedActiveDownload(
  @SerialName("model_id")
  val modelId: String,

  /** Quantization type being downloaded */
  val quantization: QuantizationType,

  val progress: DownloadProgress,

  @SerialName("started_at")
  val startedAt: Long,
)
