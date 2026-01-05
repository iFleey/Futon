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

/**
 * Represents the current state of a model download.
 */
@Serializable
sealed class DownloadState {

  /**
   * Download has not started yet.
   */
  @Serializable
  @SerialName("idle")
  data object Idle : DownloadState()

  /**
   * Download is currently in progress.
   */
  @Serializable
  @SerialName("downloading")
  data class Downloading(
    val progress: Float,

    @SerialName("downloaded_bytes")
    val downloadedBytes: Long,

    @SerialName("total_bytes")
    val totalBytes: Long,

    @SerialName("current_file")
    val currentFile: String,
  ) : DownloadState() {
    /** Progress as a percentage (0-100) */
    val progressPercent: Int
      get() = (progress * 100).toInt().coerceIn(0, 100)

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
   * Download is paused and can be resumed.
   */
  @Serializable
  @SerialName("paused")
  data class Paused(
    @SerialName("downloaded_bytes")
    val downloadedBytes: Long,

    @SerialName("total_bytes")
    val totalBytes: Long,
  ) : DownloadState() {
    val progressPercent: Int
      get() = if (totalBytes > 0) {
        ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
      } else 0
  }

  @Serializable
  @SerialName("completed")
  data object Completed : DownloadState()

  @Serializable
  @SerialName("failed")
  data class Failed(
    /** Error that caused the failure */
    val error: DownloadError,
  ) : DownloadState()
}
