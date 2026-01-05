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

import kotlinx.coroutines.flow.Flow

/**
 * Handles model file downloads with progress tracking, pause/resume, and mirror support.
 * */
interface ModelDownloader {

  /**
   * Start downloading a model's files.
   *
   *
   * @param modelId Unique identifier of the model being downloaded
   * @param files List of files to download (main model and optionally mmproj)
   * @param source Download source (Hugging Face or HF-Mirror)
   * @return Flow emitting download state updates
   */
  fun download(
    modelId: String,
    files: List<ModelFile>,
    source: DownloadSource,
  ): Flow<DownloadState>

  /**
   * Pause an active download.
   *
   * @param modelId Unique identifier of the model download to pause
   * @return Result indicating success or failure
   */
  suspend fun pause(modelId: String): Result<Unit>

  /**
   * Resume a paused download.
   *
   * @param modelId Unique identifier of the model download to resume
   * @return Result indicating success or failure
   */
  suspend fun resume(modelId: String): Result<Unit>

  /**
   * Cancel an active or paused download.
   *
   * @param modelId Unique identifier of the model download to cancel
   * @return Result indicating success or failure
   */
  suspend fun cancel(modelId: String): Result<Unit>

  /**
   * Get a flow of all currently active downloads.
   *
   * @return Flow emitting list of active downloads with their progress
   */
  fun getActiveDownloads(): Flow<List<ActiveDownload>>

  /**
   * Get the current download state for a specific model.
   *
   * @param modelId Unique identifier of the model
   * @return Flow emitting the current download state
   */
  fun getDownloadState(modelId: String): Flow<DownloadState>
}

/**
 * Represents a file to be downloaded as part of a model.
 *
 * @property filename Name of the file (e.g., "model.gguf")
 * @property url Full download URL
 * @property sizeBytes Expected file size in bytes
 * @property isRequired Whether this file is required (main model) or optional (mmproj)
 */
data class ModelFile(
  val filename: String,
  val url: String,
  val sizeBytes: Long,
  val isRequired: Boolean = true,
)

/**
 * Represents an active download with its current state.
 *
 * @property modelId Unique identifier of the model being downloaded
 * @property state Current download state
 * @property files List of files being downloaded with individual progress
 */
data class ActiveDownload(
  val modelId: String,
  val state: DownloadState,
  val files: List<FileDownloadProgress>,
)

/**
 * Progress information for a single file download.
 *
 * @property filename Name of the file being downloaded
 * @property downloadedBytes Bytes downloaded so far
 * @property totalBytes Total file size in bytes
 * @property isComplete Whether this file has finished downloading
 */
data class FileDownloadProgress(
  val filename: String,
  val downloadedBytes: Long,
  val totalBytes: Long,
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
}
