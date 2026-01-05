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
 * Errors that can occur during model download.
 */
@Serializable
sealed class DownloadError {
  abstract val message: String

  /** Whether this error can be retried */
  abstract val isRetryable: Boolean

  /**
   * Network-related error (connection issues, timeouts, etc.).
   */
  @Serializable
  @SerialName("network_error")
  data class NetworkError(
    override val message: String,
    override val isRetryable: Boolean = true,
  ) : DownloadError()

  /**
   * Storage-related error (insufficient space, write failure, etc.).
   */
  @Serializable
  @SerialName("storage_error")
  data class StorageError(
    override val message: String,
    override val isRetryable: Boolean = false,
  ) : DownloadError()

  /**
   * Invalid file error (corrupted download, wrong format, etc.).
   */
  @Serializable
  @SerialName("invalid_file_error")
  data class InvalidFileError(
    override val message: String,
    override val isRetryable: Boolean = true,
  ) : DownloadError()

  @Serializable
  @SerialName("cancelled_by_user")
  data object CancelledByUser : DownloadError() {
    override val message: String = "Download cancelled"
    override val isRetryable: Boolean = true
  }

  @Serializable
  @SerialName("server_error")
  data class ServerError(
    override val message: String,
    @SerialName("status_code")
    val statusCode: Int,
    override val isRetryable: Boolean = true,
  ) : DownloadError()

  @Serializable
  @SerialName("unknown_error")
  data class UnknownError(
    override val message: String,
    override val isRetryable: Boolean = false,
  ) : DownloadError()
}
