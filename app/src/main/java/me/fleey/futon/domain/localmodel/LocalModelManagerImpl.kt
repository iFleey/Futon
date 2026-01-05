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
package me.fleey.futon.domain.localmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.fleey.futon.data.localmodel.config.LocalModelConfigRepository
import me.fleey.futon.data.localmodel.download.DownloadError
import me.fleey.futon.data.localmodel.download.DownloadNotificationCoordinator
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.download.DownloadState
import me.fleey.futon.data.localmodel.download.DownloadUrlBuilder
import me.fleey.futon.data.localmodel.download.FileProgress
import me.fleey.futon.data.localmodel.download.ModelDownloader
import me.fleey.futon.data.localmodel.download.ModelFile
import me.fleey.futon.data.localmodel.inference.DeviceCapabilities
import me.fleey.futon.data.localmodel.inference.DeviceCapabilityDetector
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.ModelRegistry
import me.fleey.futon.data.localmodel.storage.ImportProgress
import me.fleey.futon.data.localmodel.storage.ModelImporter
import me.fleey.futon.data.localmodel.storage.StorageCheckResult
import me.fleey.futon.data.localmodel.storage.StorageValidator
import me.fleey.futon.data.localmodel.validation.GgufValidationResult
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [LocalModelManager::class])
class LocalModelManagerImpl(
  private val modelRegistry: ModelRegistry,
  private val modelDownloader: ModelDownloader,
  private val storageValidator: StorageValidator,
  private val modelImporter: ModelImporter,
  private val configRepository: LocalModelConfigRepository,
  private val deviceCapabilityDetector: DeviceCapabilityDetector,
  private val notificationCoordinator: DownloadNotificationCoordinator,
) : LocalModelManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
  private val activeDownloadsState: StateFlow<Map<String, DownloadProgress>> =
    _activeDownloads.asStateFlow()

  private var cachedCapabilities: DeviceCapabilities? = null

  override suspend fun refreshCatalog(): Result<List<ModelInfo>> {
    return modelRegistry.refreshCatalog()
  }

  override fun getDownloadedModels(): Flow<List<DownloadedModel>> {
    return modelRegistry.getDownloadedModels()
  }

  override fun getModelInfo(modelId: String): ModelInfo? {
    return modelRegistry.getModelInfo(modelId)
  }

  override fun downloadModel(modelId: String, quantization: QuantizationType): Flow<DownloadState> =
    flow {
      val modelInfo = modelRegistry.getModelInfo(modelId)
      if (modelInfo == null) {
        emit(DownloadState.Failed(DownloadError.UnknownError("Model not found: $modelId")))
        return@flow
      }

      val quantizationInfo = modelInfo.quantizations.find { it.type == quantization }
      if (quantizationInfo == null) {
        emit(
          DownloadState.Failed(
            DownloadError.UnknownError(
              "Quantization $quantization not available for model $modelId",
            ),
          ),
        )
        return@flow
      }

      val totalSize = quantizationInfo.totalSize
      val storageCheck = storageValidator.checkStorageForDownload(totalSize)
      if (storageCheck is StorageCheckResult.Insufficient) {
        emit(
          DownloadState.Failed(
            DownloadError.StorageError(
              "Insufficient storage: need ${storageCheck.totalRequiredFormatted}, " +
                "available ${storageCheck.availableFormatted}",
            ),
          ),
        )
        return@flow
      }

      val downloadSource = configRepository.getDownloadSource()
      val files = DownloadUrlBuilder.buildModelFiles(modelInfo, quantizationInfo, downloadSource)

      modelRegistry.setModelDownloading(modelId)
      notificationCoordinator.startObserving(modelId)

      try {
        modelDownloader.download(modelId, files, downloadSource).collect { state ->
          updateDownloadProgress(modelId, state, files)
          emit(state)

          if (state is DownloadState.Completed) {
            val storageDir = storageValidator.getModelStorageDirectory()
            val modelDir = File(storageDir, modelId)
            val mainModelPath = File(modelDir, quantizationInfo.mainModelFile).absolutePath
            val mmprojPath = quantizationInfo.mmprojFile?.let {
              File(modelDir, it).absolutePath
            }

            val downloadedModel = DownloadedModel(
              modelId = modelId,
              quantization = quantization,
              mainModelPath = mainModelPath,
              mmprojPath = mmprojPath,
              downloadedAt = System.currentTimeMillis(),
              sizeBytes = totalSize,
            )

            modelRegistry.registerDownloadedModel(downloadedModel)
            removeDownloadProgress(modelId)
          }

          if (state is DownloadState.Failed) {
            modelRegistry.clearModelDownloading(modelId)
            removeDownloadProgress(modelId)
          }
        }
      } catch (e: Exception) {
        modelRegistry.clearModelDownloading(modelId)
        removeDownloadProgress(modelId)
        emit(DownloadState.Failed(DownloadError.UnknownError(e.message ?: "Download failed")))
      }
    }

  override suspend fun pauseDownload(modelId: String): Result<Unit> {
    return modelDownloader.pause(modelId)
  }

  override suspend fun resumeDownload(modelId: String): Result<Unit> {
    return modelDownloader.resume(modelId)
  }

  override suspend fun cancelDownload(modelId: String): Result<Unit> {
    val result = modelDownloader.cancel(modelId)
    if (result.isSuccess) {
      modelRegistry.clearModelDownloading(modelId)
      removeDownloadProgress(modelId)
    }
    return result
  }

  override fun getDownloadProgress(modelId: String): Flow<DownloadProgress?> {
    return activeDownloadsState.map { downloads -> downloads[modelId] }
  }

  override fun getActiveDownloads(): Flow<List<DownloadProgress>> {
    return activeDownloadsState.map { it.values.toList() }
  }

  override suspend fun importModel(
    modelPath: String,
    mmprojPath: String?,
  ): Result<DownloadedModel> {
    return withContext(Dispatchers.IO) {
      try {
        var result: Result<DownloadedModel> = Result.failure(Exception("Import not completed"))

        modelImporter.importModel(modelPath, mmprojPath).collect { progress ->
          when (progress) {
            is ImportProgress.Completed -> {
              result = Result.success(progress.model)
            }

            is ImportProgress.Failed -> {
              result = Result.failure(
                IllegalArgumentException(progress.error.toString()),
              )
            }

            else -> {}
          }
        }

        result
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  override suspend fun validateGgufFile(path: String): GgufValidationResult {
    return modelImporter.validateFile(path)
  }

  override suspend fun isVisionLanguageModel(path: String): Boolean {
    return modelImporter.requiresMmproj(path)
  }

  override suspend fun enableModel(modelId: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
      try {
        val downloadedModels = modelRegistry.getDownloadedModels().first()
        val model = downloadedModels.find { it.modelId == modelId }
          ?: return@withContext Result.failure(
            IllegalArgumentException("Model not found: $modelId"),
          )

        val mainModelFile = File(model.mainModelPath)
        if (!mainModelFile.exists()) {
          return@withContext Result.failure(
            IllegalStateException("Model file not found: ${model.mainModelPath}"),
          )
        }

        if (model.mmprojPath != null) {
          val mmprojFile = File(model.mmprojPath)
          if (!mmprojFile.exists()) {
            return@withContext Result.failure(
              IllegalStateException("mmproj file not found: ${model.mmprojPath}"),
            )
          }
        }

        modelRegistry.setActiveModel(modelId)
        configRepository.setActiveModelId(modelId)

        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  override suspend fun disableModel(): Result<Unit> {
    return withContext(Dispatchers.IO) {
      try {
        modelRegistry.setActiveModel(null)
        configRepository.setActiveModelId(null)
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  override fun getActiveModelId(): String? {
    return modelRegistry.getActiveModelId()
  }

  override fun isLocalModelActive(): Boolean {
    return getActiveModelId() != null
  }

  override suspend fun deleteModel(modelId: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
      try {
        val downloadedModels = modelRegistry.getDownloadedModels().first()
        val model = downloadedModels.find { it.modelId == modelId }
          ?: return@withContext Result.failure(
            IllegalArgumentException("Model not found: $modelId"),
          )

        if (getActiveModelId() == modelId) {
          disableModel()
        }
        val mainModelFile = File(model.mainModelPath)
        if (mainModelFile.exists()) {
          mainModelFile.delete()
        }

        if (model.mmprojPath != null) {
          val mmprojFile = File(model.mmprojPath)
          if (mmprojFile.exists()) {
            mmprojFile.delete()
          }
        }

        modelRegistry.unregisterModel(modelId)

        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  override fun getInferenceConfig(): Flow<InferenceConfig> {
    return configRepository.getConfigFlow().map { it.inferenceConfig }
  }

  override suspend fun updateInferenceConfig(config: InferenceConfig): Result<Unit> {
    return try {
      configRepository.updateInferenceConfig(config)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override suspend fun getDownloadSource(): DownloadSource {
    return configRepository.getDownloadSource()
  }

  override suspend fun setDownloadSource(source: DownloadSource): Result<Unit> {
    return try {
      configRepository.setDownloadSource(source)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override suspend fun getDeviceCapabilities(): DeviceCapabilities {
    return cachedCapabilities ?: deviceCapabilityDetector.detectCapabilities().also {
      cachedCapabilities = it
    }
  }

  override fun getRecommendedQuantization(): QuantizationType {
    val totalRamMb = deviceCapabilityDetector.getTotalRamMb()
    return if (totalRamMb >= 6144) {
      QuantizationType.INT8
    } else {
      QuantizationType.INT4
    }
  }

  override suspend fun getAvailableStorage(): Long {
    return storageValidator.getAvailableStorage()
  }

  override suspend fun hasStorageForDownload(requiredBytes: Long): Boolean {
    return storageValidator.checkStorageForDownload(requiredBytes) is StorageCheckResult.Sufficient
  }

  private fun updateDownloadProgress(
    modelId: String,
    state: DownloadState,
    files: List<ModelFile>,
  ) {
    val fileProgresses = when (state) {
      is DownloadState.Downloading -> {
        files.map { file ->
          FileProgress(
            fileName = file.filename,
            downloadedBytes = if (file.filename == state.currentFile) {
              state.downloadedBytes
            } else {
              if (state.progress > 0.5f) file.sizeBytes else 0L
            },
            totalBytes = file.sizeBytes,
            isComplete = file.filename != state.currentFile && state.progress > 0.5f,
          )
        }
      }

      is DownloadState.Paused -> {
        files.map { file ->
          FileProgress(
            fileName = file.filename,
            downloadedBytes = (file.sizeBytes * (state.downloadedBytes.toFloat() / state.totalBytes)).toLong(),
            totalBytes = file.sizeBytes,
            isComplete = false,
          )
        }
      }

      is DownloadState.Completed -> {
        files.map { file ->
          FileProgress(
            fileName = file.filename,
            downloadedBytes = file.sizeBytes,
            totalBytes = file.sizeBytes,
            isComplete = true,
          )
        }
      }

      else -> emptyList()
    }

    val progress = DownloadProgress(
      modelId = modelId,
      state = state,
      files = fileProgresses,
    )

    _activeDownloads.value += (modelId to progress)
  }

  private fun removeDownloadProgress(modelId: String) {
    _activeDownloads.value -= modelId
  }
}
