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

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.download.DownloadState
import me.fleey.futon.data.localmodel.inference.DeviceCapabilities
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.validation.GgufValidationResult

interface LocalModelManager {

  suspend fun refreshCatalog(): Result<List<ModelInfo>>

  fun getDownloadedModels(): Flow<List<DownloadedModel>>

  fun getModelInfo(modelId: String): ModelInfo?

  fun downloadModel(modelId: String, quantization: QuantizationType): Flow<DownloadState>

  suspend fun pauseDownload(modelId: String): Result<Unit>

  suspend fun resumeDownload(modelId: String): Result<Unit>

  suspend fun cancelDownload(modelId: String): Result<Unit>

  fun getDownloadProgress(modelId: String): Flow<DownloadProgress?>

  fun getActiveDownloads(): Flow<List<DownloadProgress>>

  suspend fun importModel(modelPath: String, mmprojPath: String?): Result<DownloadedModel>

  suspend fun validateGgufFile(path: String): GgufValidationResult

  suspend fun isVisionLanguageModel(path: String): Boolean

  suspend fun enableModel(modelId: String): Result<Unit>

  suspend fun disableModel(): Result<Unit>
  fun getActiveModelId(): String?

  fun isLocalModelActive(): Boolean

  suspend fun deleteModel(modelId: String): Result<Unit>

  fun getInferenceConfig(): Flow<InferenceConfig>

  suspend fun updateInferenceConfig(config: InferenceConfig): Result<Unit>

  suspend fun getDownloadSource(): DownloadSource

  suspend fun setDownloadSource(source: DownloadSource): Result<Unit>

  suspend fun getDeviceCapabilities(): DeviceCapabilities

  fun getRecommendedQuantization(): QuantizationType

  suspend fun getAvailableStorage(): Long

  suspend fun hasStorageForDownload(requiredBytes: Long): Boolean
}
