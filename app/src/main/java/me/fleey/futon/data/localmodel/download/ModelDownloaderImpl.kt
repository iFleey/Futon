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

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.data.localmodel.storage.StorageValidator
import org.koin.core.annotation.Single
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * ModelDownloader implementation using Ktor HTTP client.
 * Supports resumable downloads, concurrent file downloads, and pause/resume/cancel.
 */
@Single(binds = [ModelDownloader::class])
class ModelDownloaderImpl(
  private val httpClient: HttpClient,
  private val storageValidator: StorageValidator,
) : ModelDownloader {

  companion object {
    /** Buffer size for reading download chunks (64KB) */
    private const val BUFFER_SIZE = 64 * 1024

    /** Suffix for partial download files */
    private const val PARTIAL_SUFFIX = ".partial"
  }

  /** Scope for download coroutines */
  private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /** Active download jobs by model ID */
  private val downloadJobs = ConcurrentHashMap<String, Job>()

  /** Download states by model ID */
  private val downloadStates = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()

  /** File progress by model ID */
  private val fileProgressMap =
    ConcurrentHashMap<String, MutableStateFlow<List<FileDownloadProgress>>>()

  /** Pause flags by model ID */
  private val pauseFlags = ConcurrentHashMap<String, Boolean>()

  /** Mutex for thread-safe state updates */
  private val stateMutex = Mutex()

  /** Combined active downloads flow */
  private val _activeDownloads = MutableStateFlow<List<ActiveDownload>>(emptyList())


  override fun download(
    modelId: String,
    files: List<ModelFile>,
    source: DownloadSource,
  ): Flow<DownloadState> {
    // Initialize state flow for this download
    val stateFlow = MutableStateFlow<DownloadState>(DownloadState.Idle)
    downloadStates[modelId] = stateFlow

    val initialProgress = files.map { file ->
      FileDownloadProgress(
        filename = file.filename,
        downloadedBytes = getExistingFileSize(modelId, file.filename),
        totalBytes = file.sizeBytes,
        isComplete = false,
      )
    }
    fileProgressMap[modelId] = MutableStateFlow(initialProgress)

    // Clear any pause flag
    pauseFlags[modelId] = false

    // Start download job
    val job = downloadScope.launch {
      try {
        executeDownload(modelId, files, source, stateFlow)
      } catch (e: CancellationException) {
        stateFlow.value = DownloadState.Failed(DownloadError.CancelledByUser)
      } catch (e: Exception) {
        stateFlow.value = DownloadState.Failed(
          DownloadError.UnknownError(e.message ?: "Unknown error occurred"),
        )
      } finally {
        updateActiveDownloads()
      }
    }

    downloadJobs[modelId] = job
    updateActiveDownloads()

    return stateFlow.asStateFlow()
  }

  override suspend fun pause(modelId: String): Result<Unit> = runCatching {
    stateMutex.withLock {
      val stateFlow = downloadStates[modelId]
        ?: throw IllegalStateException("No active download for model: $modelId")

      val currentState = stateFlow.value
      if (currentState !is DownloadState.Downloading) {
        throw IllegalStateException("Cannot pause download in state: $currentState")
      }

      // Set pause flag - the download loop will check this
      pauseFlags[modelId] = true

      stateFlow.value = DownloadState.Paused(
        downloadedBytes = currentState.downloadedBytes,
        totalBytes = currentState.totalBytes,
      )

      updateActiveDownloads()
    }
  }

  override suspend fun resume(modelId: String): Result<Unit> = runCatching {
    stateMutex.withLock {
      val stateFlow = downloadStates[modelId]
        ?: throw IllegalStateException("No download state for model: $modelId")

      val currentState = stateFlow.value
      if (currentState !is DownloadState.Paused) {
        throw IllegalStateException("Cannot resume download in state: $currentState")
      }

      // Clear pause flag
      pauseFlags[modelId] = false

      // The download will be resumed by the existing job checking the pause flag
      // If the job was cancelled, we need to restart it
      val existingJob = downloadJobs[modelId]
      if (existingJob == null || !existingJob.isActive) {
        // Need to restart the download - this requires the original files info
        // For now, we'll just update the state and let the caller restart if needed
        throw IllegalStateException("Download job is not active. Please restart the download.")
      }

      updateActiveDownloads()
    }
  }

  override suspend fun cancel(modelId: String): Result<Unit> = runCatching {
    stateMutex.withLock {
      // Cancel the job
      downloadJobs[modelId]?.cancel()
      downloadJobs.remove(modelId)

      downloadStates[modelId]?.value = DownloadState.Failed(DownloadError.CancelledByUser)

      cleanupPartialFiles(modelId)

      // Remove from tracking
      downloadStates.remove(modelId)
      fileProgressMap.remove(modelId)
      pauseFlags.remove(modelId)

      updateActiveDownloads()
    }
  }

  override fun getActiveDownloads(): Flow<List<ActiveDownload>> = _activeDownloads.asStateFlow()

  override fun getDownloadState(modelId: String): Flow<DownloadState> {
    return downloadStates[modelId]?.asStateFlow()
      ?: MutableStateFlow(DownloadState.Idle).asStateFlow()
  }


  /**
   * Execute the actual download of all files.
   */
  private suspend fun executeDownload(
    modelId: String,
    files: List<ModelFile>,
    source: DownloadSource,
    stateFlow: MutableStateFlow<DownloadState>,
  ) {
    val modelDir = getModelDirectory(modelId)
    modelDir.mkdirs()

    val totalBytes = files.sumOf { it.sizeBytes }

    // Update initial downloading state
    stateFlow.value = DownloadState.Downloading(
      progress = 0f,
      downloadedBytes = 0L,
      totalBytes = totalBytes,
      currentFile = files.firstOrNull()?.filename ?: "",
    )

    // Download files concurrently
    val results = files.map { file ->
      downloadScope.async {
        downloadFile(modelId, file, modelDir, stateFlow, totalBytes)
      }
    }.awaitAll()

    // Check if all downloads succeeded
    val failedResult = results.firstOrNull { it.isFailure }
    if (failedResult != null) {
      val error = failedResult.exceptionOrNull()
      stateFlow.value = DownloadState.Failed(
        when (error) {
          is DownloadException -> error.downloadError
          else -> DownloadError.UnknownError(error?.message ?: "Download failed")
        },
      )
      return
    }

    // Rename partial files to final names
    files.forEach { file ->
      val partialFile = File(modelDir, file.filename + PARTIAL_SUFFIX)
      val finalFile = File(modelDir, file.filename)
      if (partialFile.exists()) {
        partialFile.renameTo(finalFile)
      }
    }

    stateFlow.value = DownloadState.Completed
  }

  /**
   * Download a single file with resume support.
   */
  private suspend fun downloadFile(
    modelId: String,
    file: ModelFile,
    modelDir: File,
    stateFlow: MutableStateFlow<DownloadState>,
    totalBytes: Long,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val partialFile = File(modelDir, file.filename + PARTIAL_SUFFIX)
      val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

      if (existingBytes >= file.sizeBytes) {
        updateFileProgress(modelId, file.filename, file.sizeBytes, file.sizeBytes, true)
        return@runCatching
      }

      // Prepare request with Range header for resume
      httpClient.prepareGet(file.url) {
        if (existingBytes > 0) {
          header(HttpHeaders.Range, "bytes=$existingBytes-")
        }
      }.execute { response ->
        handleDownloadResponse(
          response = response,
          modelId = modelId,
          file = file,
          partialFile = partialFile,
          existingBytes = existingBytes,
          stateFlow = stateFlow,
          totalBytes = totalBytes,
        )
      }
    }
  }


  /**
   * Handle the HTTP response and write data to file.
   */
  private suspend fun handleDownloadResponse(
    response: HttpResponse,
    modelId: String,
    file: ModelFile,
    partialFile: File,
    existingBytes: Long,
    stateFlow: MutableStateFlow<DownloadState>,
    totalBytes: Long,
  ) {
    when {
      response.status == HttpStatusCode.PartialContent || response.status.isSuccess() -> {
        // OK - continue with download
      }

      response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
        // File is already complete or server doesn't support range
        // Try downloading from scratch
        partialFile.delete()
        throw DownloadException(
          DownloadError.ServerError(
            message = "Range not satisfiable, restarting download",
            statusCode = response.status.value,
          ),
        )
      }

      else -> {
        throw DownloadException(
          DownloadError.ServerError(
            message = "Server returned ${response.status.value}: ${response.status.description}",
            statusCode = response.status.value,
          ),
        )
      }
    }

    // Get content length
    val contentLength = response.contentLength() ?: (file.sizeBytes - existingBytes)
    val expectedTotal = existingBytes + contentLength

    // Open file for writing (append mode if resuming)
    val randomAccessFile = withContext(Dispatchers.IO) {
      RandomAccessFile(partialFile, "rw")
    }
    withContext(Dispatchers.IO) {
      randomAccessFile.seek(existingBytes)
    }

    try {
      val channel: ByteReadChannel = response.body()
      val buffer = ByteArray(BUFFER_SIZE)
      var downloadedBytes = existingBytes

      while (!channel.isClosedForRead) {
        if (pauseFlags[modelId] == true) {
          // Save progress and exit
          withContext(Dispatchers.IO) {
            randomAccessFile.close()
          }
          return
        }

        val bytesRead = channel.readAvailable(buffer)
        if (bytesRead <= 0) break

        withContext(Dispatchers.IO) {
          randomAccessFile.write(buffer, 0, bytesRead)
        }
        downloadedBytes += bytesRead

        // Update progress
        updateFileProgress(modelId, file.filename, downloadedBytes, file.sizeBytes, false)
        updateOverallProgress(modelId, stateFlow, totalBytes, file.filename)
      }

      updateFileProgress(modelId, file.filename, downloadedBytes, file.sizeBytes, true)

    } finally {
      withContext(Dispatchers.IO) {
        randomAccessFile.close()
      }
    }
  }

  /**
   * Update progress for a specific file.
   */
  private fun updateFileProgress(
    modelId: String,
    filename: String,
    downloadedBytes: Long,
    totalBytes: Long,
    isComplete: Boolean,
  ) {
    val progressFlow = fileProgressMap[modelId] ?: return
    val currentList = progressFlow.value.toMutableList()
    val index = currentList.indexOfFirst { it.filename == filename }

    if (index >= 0) {
      currentList[index] = FileDownloadProgress(
        filename = filename,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        isComplete = isComplete,
      )
      progressFlow.value = currentList
    }
  }

  /**
   * Update overall download progress based on all files.
   */
  private fun updateOverallProgress(
    modelId: String,
    stateFlow: MutableStateFlow<DownloadState>,
    totalBytes: Long,
    currentFile: String,
  ) {
    val fileProgress = fileProgressMap[modelId]?.value ?: return
    val totalDownloaded = fileProgress.sumOf { it.downloadedBytes }
    val progress = if (totalBytes > 0) {
      (totalDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else 0f

    stateFlow.value = DownloadState.Downloading(
      progress = progress,
      downloadedBytes = totalDownloaded,
      totalBytes = totalBytes,
      currentFile = currentFile,
    )

    updateActiveDownloads()
  }


  /**
   * Update the combined active downloads flow.
   */
  private fun updateActiveDownloads() {
    val activeList = downloadStates.mapNotNull { (modelId, stateFlow) ->
      val state = stateFlow.value
      val files = fileProgressMap[modelId]?.value ?: emptyList()

      // Only include if actively downloading or paused
      if (state is DownloadState.Downloading || state is DownloadState.Paused) {
        // Use the ActiveDownload from ModelDownloader.kt (not DownloadProgress.kt)
        ActiveDownload(
          modelId = modelId,
          state = state,
          files = files,
        )
      } else null
    }
    _activeDownloads.value = activeList
  }

  /**
   * Get the directory for a specific model's files.
   */
  private fun getModelDirectory(modelId: String): File {
    return File(storageValidator.getModelStorageDirectory(), modelId)
  }

  /**
   * Get the size of an existing partial file.
   */
  private fun getExistingFileSize(modelId: String, filename: String): Long {
    val modelDir = getModelDirectory(modelId)
    val partialFile = File(modelDir, filename + PARTIAL_SUFFIX)
    val finalFile = File(modelDir, filename)

    return when {
      finalFile.exists() -> finalFile.length()
      partialFile.exists() -> partialFile.length()
      else -> 0L
    }
  }

  /**
   * Clean up partial download files for a model.
   */
  private fun cleanupPartialFiles(modelId: String) {
    val modelDir = getModelDirectory(modelId)
    if (modelDir.exists()) {
      modelDir.listFiles()?.forEach { file ->
        if (file.name.endsWith(PARTIAL_SUFFIX)) {
          file.delete()
        }
      }
    }
  }
}

/**
 * Exception wrapper for download errors.
 */
class DownloadException(val downloadError: DownloadError) : Exception(downloadError.message)
