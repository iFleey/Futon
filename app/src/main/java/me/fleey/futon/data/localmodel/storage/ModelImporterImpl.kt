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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.ModelRegistry
import me.fleey.futon.data.localmodel.validation.GgufMetadata
import me.fleey.futon.data.localmodel.validation.GgufValidationResult
import me.fleey.futon.data.localmodel.validation.GgufValidator
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Implementation of [ModelImporter] for importing local GGUF model files.
 * */
@Single(binds = [ModelImporter::class])
class ModelImporterImpl(
  private val ggufValidator: GgufValidator,
  private val storageValidator: StorageValidator,
  private val modelRegistry: ModelRegistry,
) : ModelImporter {

  companion object {
    private const val COPY_BUFFER_SIZE = 8 * 1024

    private const val PROGRESS_UPDATE_INTERVAL = 1024 * 1024L
  }

  /**
   * Import a model from local storage.
   */
  override fun importModel(modelPath: String, mmprojPath: String?): Flow<ImportProgress> = flow {
    try {
      emit(ImportProgress.Validating)

      val sourceFile = File(modelPath)
      if (!sourceFile.exists()) {
        emit(ImportProgress.Failed(ImportError.FileNotFound(modelPath)))
        return@flow
      }

      val validationResult = ggufValidator.validate(modelPath)
      if (validationResult is GgufValidationResult.Invalid) {
        emit(
          ImportProgress.Failed(
            ImportError.InvalidGgufFile(modelPath, validationResult.error.toString()),
          ),
        )
        return@flow
      }

      val metadata = (validationResult as GgufValidationResult.Valid).metadata

      if (metadata.isVisionModel && mmprojPath == null) {
        emit(ImportProgress.Failed(ImportError.MmprojRequired(modelPath)))
        return@flow
      }
      var mmprojFile: File? = null
      if (mmprojPath != null) {
        mmprojFile = File(mmprojPath)
        if (!mmprojFile.exists()) {
          emit(ImportProgress.Failed(ImportError.FileNotFound(mmprojPath)))
          return@flow
        }

        val mmprojValidation = ggufValidator.validate(mmprojPath)
        if (mmprojValidation is GgufValidationResult.Invalid) {
          emit(
            ImportProgress.Failed(
              ImportError.InvalidMmprojFile(mmprojPath, mmprojValidation.error.toString()),
            ),
          )
          return@flow
        }
      }

      emit(ImportProgress.CheckingStorage)

      val totalSize = sourceFile.length() + (mmprojFile?.length() ?: 0L)
      val storageCheck = storageValidator.checkStorageForDownload(totalSize)
      if (storageCheck is StorageCheckResult.Insufficient) {
        emit(
          ImportProgress.Failed(
            ImportError.InsufficientStorage(
              requiredBytes = storageCheck.totalRequired,
              availableBytes = storageCheck.availableBytes,
            ),
          ),
        )
        return@flow
      }

      val storageDir = storageValidator.getModelStorageDirectory()
      if (!storageDir.exists()) {
        storageDir.mkdirs()
      }
      val destMainModel = File(storageDir, sourceFile.name)
      copyFileWithProgress(
        source = sourceFile,
        destination = destMainModel,
        totalBytes = totalSize,
        startOffset = 0L,
      ) { copiedBytes ->
        emit(
          ImportProgress.Copying(
            currentFile = sourceFile.name,
            copiedBytes = copiedBytes,
            totalBytes = totalSize,
            progress = copiedBytes.toFloat() / totalSize,
          ),
        )
      }

      var destMmproj: File? = null
      if (mmprojFile != null) {
        destMmproj = File(storageDir, mmprojFile.name)
        val mainModelSize = sourceFile.length()
        copyFileWithProgress(
          source = mmprojFile,
          destination = destMmproj,
          totalBytes = totalSize,
          startOffset = mainModelSize,
        ) { copiedBytes ->
          emit(
            ImportProgress.Copying(
              currentFile = mmprojFile.name,
              copiedBytes = mainModelSize + copiedBytes,
              totalBytes = totalSize,
              progress = (mainModelSize + copiedBytes).toFloat() / totalSize,
            ),
          )
        }
      }

      emit(ImportProgress.Registering)

      val quantization = inferQuantizationType(metadata)
      val modelId = generateImportedModelId(sourceFile.name)

      val downloadedModel = DownloadedModel(
        modelId = modelId,
        quantization = quantization,
        mainModelPath = destMainModel.absolutePath,
        mmprojPath = destMmproj?.absolutePath,
        downloadedAt = System.currentTimeMillis(),
        sizeBytes = totalSize,
      )

      val registerResult = modelRegistry.registerDownloadedModel(downloadedModel)
      if (registerResult.isFailure) {
        destMainModel.delete()
        destMmproj?.delete()

        emit(
          ImportProgress.Failed(
            ImportError.RegistrationError(
              registerResult.exceptionOrNull()?.message ?: "Unknown error",
            ),
          ),
        )
        return@flow
      }

      emit(ImportProgress.Completed(downloadedModel))

    } catch (e: Exception) {
      emit(ImportProgress.Failed(ImportError.UnknownError(e.message ?: "Unknown error")))
    }
  }

  override suspend fun validateFile(path: String): GgufValidationResult {
    return ggufValidator.validate(path)
  }

  override suspend fun requiresMmproj(path: String): Boolean {
    return ggufValidator.isVisionLanguageModel(path)
  }

  override suspend fun getImportSize(modelPath: String, mmprojPath: String?): Long {
    return withContext(Dispatchers.IO) {
      val mainSize = File(modelPath).length()
      val mmprojSize = mmprojPath?.let { File(it).length() } ?: 0L
      mainSize + mmprojSize
    }
  }

  override suspend fun hasStorageForImport(modelPath: String, mmprojPath: String?): Boolean {
    val totalSize = getImportSize(modelPath, mmprojPath)
    return storageValidator.checkStorageForDownload(totalSize) is StorageCheckResult.Sufficient
  }

  /**
   * Copy a file with progress tracking.
   *
   * @param source Source file to copy
   * @param destination Destination file path
   * @param totalBytes Total bytes for overall progress calculation
   * @param startOffset Offset for progress calculation (for multi-file imports)
   * @param onProgress Callback for progress updates
   */
  private suspend fun copyFileWithProgress(
    source: File,
    destination: File,
    totalBytes: Long,
    startOffset: Long,
    onProgress: suspend (Long) -> Unit,
  ) = withContext(Dispatchers.IO) {
    FileInputStream(source).use { input ->
      FileOutputStream(destination).use { output ->
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var bytesCopied = 0L
        var lastProgressUpdate = 0L
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
          output.write(buffer, 0, bytesRead)
          bytesCopied += bytesRead

          if (bytesCopied - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) {
            onProgress(bytesCopied)
            lastProgressUpdate = bytesCopied
          }
        }

        onProgress(bytesCopied)
      }
    }
  }

  /**
   * Infer quantization type from GGUF metadata.
   */
  private fun inferQuantizationType(metadata: GgufMetadata): QuantizationType {
    val quantString = metadata.quantizationType
    return when {
      quantString == null -> QuantizationType.INT4
      quantString.contains("Q4", ignoreCase = true) -> QuantizationType.INT4
      quantString.contains("Q8", ignoreCase = true) -> QuantizationType.INT8
      quantString.contains("F16", ignoreCase = true) -> QuantizationType.FP16
      quantString.contains("INT4", ignoreCase = true) -> QuantizationType.INT4
      quantString.contains("INT8", ignoreCase = true) -> QuantizationType.INT8
      else -> QuantizationType.INT4
    }
  }

  private fun generateImportedModelId(filename: String): String {
    val baseName = filename.substringBeforeLast(".")
      .replace(Regex("[^a-zA-Z0-9-_]"), "-")
      .lowercase()
      .take(32)
    val timestamp = System.currentTimeMillis()
    return "imported-$baseName-$timestamp"
  }
}
