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
package me.fleey.futon.data.perception.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/**
 * Implementation of [ModelManager] that orchestrates model deployment and state tracking.
 */
@Single(binds = [ModelManager::class])
class ModelManagerImpl(
  private val context: Context,
  private val modelDeployer: ModelDeployer,
) : ModelManager {

  companion object {
    private const val TAG = "ModelManager"
    private const val OCR_DETECTION_MODEL = "ocr_detection"
    private const val OCR_RECOGNITION_MODEL = "ocr_recognition"
    private const val OCR_DICTIONARY_MODEL = "ocr_dictionary"
    private const val OBJECT_DETECTION_MODEL = "object_detection"
  }

  private val _deploymentState = MutableStateFlow(ModelDeploymentState())
  override val deploymentState: StateFlow<ModelDeploymentState> = _deploymentState.asStateFlow()

  private var cachedManifest: ModelManifest? = null

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  init {
    scope.launch {
      initializeAndVerifyModels()
    }
  }

  private suspend fun initializeAndVerifyModels() {
    try {
      val manifest = ModelManifest.loadFromAssets(context).getOrNull() ?: return
      cachedManifest = manifest
      verifyAllModelsInternal(manifest)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize model states", e)
    }
  }

  override suspend fun loadManifest(): Result<ModelManifest> {
    cachedManifest?.let { return Result.success(it) }

    return ModelManifest.loadFromAssets(context).also { result ->
      result.onSuccess { manifest ->
        cachedManifest = manifest
        verifyAllModelsInternal(manifest)
      }
    }
  }


  override suspend fun deployAllModels(): Result<Unit> {
    val manifest = loadManifest().getOrElse { error ->
      updateError("Failed to load manifest: ${error.message}")
      return Result.failure(error)
    }

    _deploymentState.update { it.copy(isDeploying = true, lastError = null) }

    return modelDeployer.deployModels(manifest) { modelName, progress ->
      updateModelProgress(modelName, progress)
      updateOverallProgress(progress)
    }.also { result ->
      result.onSuccess {
        Log.i(TAG, "All models deployed successfully")
        verifyAllModelsInternal(manifest)
      }.onFailure { error ->
        Log.e(TAG, "Model deployment failed", error)
        updateError(error.message ?: "Unknown deployment error")
      }
      _deploymentState.update { it.copy(isDeploying = false) }
    }
  }

  override suspend fun verifyAllModels(): Result<Map<String, Boolean>> {
    val manifest = loadManifest().getOrElse { error ->
      return Result.failure(error)
    }

    return runCatching {
      verifyAllModelsInternal(manifest)
    }
  }

  override suspend fun redeployCorruptedModels(): Result<Unit> {
    val manifest = loadManifest().getOrElse { error ->
      return Result.failure(error)
    }

    val currentState = _deploymentState.value
    val corruptedOrMissing = manifest.models.filter { model ->
      val status = currentState.models[model.name]
      status is ModelDeploymentStatus.Corrupted ||
        status is ModelDeploymentStatus.NotDeployed ||
        status is ModelDeploymentStatus.Failed
    }

    if (corruptedOrMissing.isEmpty()) {
      Log.d(TAG, "No corrupted or missing models to redeploy")
      return Result.success(Unit)
    }

    _deploymentState.update { it.copy(isDeploying = true, lastError = null) }

    return runCatching {
      for (model in corruptedOrMissing) {
        updateModelStatus(model.name, ModelDeploymentStatus.Deploying(0f))
        modelDeployer.deployModel(model).getOrThrow()
        updateModelStatus(model.name, ModelDeploymentStatus.Deployed)
      }
      modelDeployer.notifyDaemonReload().getOrThrow()
      Log.i(TAG, "Redeployed ${corruptedOrMissing.size} models")
      Unit
    }.also { result ->
      result.onFailure { error ->
        updateError(error.message ?: "Redeployment failed")
      }
      _deploymentState.update { it.copy(isDeploying = false) }
    }
  }

  override fun isFeatureAvailable(feature: ModelFeature): Boolean {
    val state = _deploymentState.value
    return when (feature) {
      ModelFeature.OCR_RECOGNITION -> {
        isModelDeployed(state, OCR_DETECTION_MODEL) &&
          isModelDeployed(state, OCR_RECOGNITION_MODEL) &&
          isModelDeployed(state, OCR_DICTIONARY_MODEL)
      }

      ModelFeature.OBJECT_DETECTION -> {
        isModelDeployed(state, OBJECT_DETECTION_MODEL)
      }

      ModelFeature.HOTPATH_AUTOMATION -> {
        isFeatureAvailable(ModelFeature.OCR_RECOGNITION) ||
          isFeatureAvailable(ModelFeature.OBJECT_DETECTION)
      }
    }
  }

  private fun isModelDeployed(state: ModelDeploymentState, modelName: String): Boolean {
    return state.models[modelName] is ModelDeploymentStatus.Deployed
  }

  private suspend fun verifyAllModelsInternal(manifest: ModelManifest): Map<String, Boolean> {
    val results = mutableMapOf<String, Boolean>()

    for (model in manifest.models) {
      val isValid = modelDeployer.verifyModel(model)
      results[model.name] = isValid

      val status = when {
        isValid -> ModelDeploymentStatus.Deployed
        modelDeployer.getDeployedModels().contains(model.targetPath) -> {
          ModelDeploymentStatus.Corrupted
        }

        else -> ModelDeploymentStatus.NotDeployed
      }
      updateModelStatus(model.name, status)
    }

    return results
  }

  private fun updateModelStatus(modelName: String, status: ModelDeploymentStatus) {
    _deploymentState.update { state ->
      state.copy(models = state.models + (modelName to status))
    }
  }

  private fun updateModelProgress(modelName: String, progress: Float) {
    _deploymentState.update { state ->
      val currentStatus = state.models[modelName]
      if (currentStatus !is ModelDeploymentStatus.Deployed) {
        val newStatus = if (progress >= 1f) {
          ModelDeploymentStatus.Deployed
        } else {
          ModelDeploymentStatus.Deploying(progress)
        }
        state.copy(models = state.models + (modelName to newStatus))
      } else {
        state
      }
    }
  }

  private fun updateOverallProgress(progress: Float) {
    _deploymentState.update { it.copy(overallProgress = progress) }
  }

  private fun updateError(message: String) {
    _deploymentState.update { it.copy(lastError = message) }
  }
}
