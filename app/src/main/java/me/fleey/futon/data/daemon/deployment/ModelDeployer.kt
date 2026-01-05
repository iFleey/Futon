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
package me.fleey.futon.data.daemon.deployment

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

interface ModelDeployer {
  val modelState: StateFlow<ModelDeploymentState>

  suspend fun deployModels(): ModelDeploymentState
  suspend fun checkModels(): ModelCheckResult
  suspend fun deployModel(modelName: String): Boolean
  suspend fun getDeployedModelVersions(): Map<String, Int>
}

data class ModelCheckResult(
  val allPresent: Boolean,
  val missingModels: List<String>,
  val outdatedModels: List<String>,
)

@Single(binds = [ModelDeployer::class])
class ModelDeployerImpl(
  private val context: Context,
  private val dataStore: DataStore<Preferences>,
  private val rootShell: RootShell,
  private val integrityChecker: BinaryIntegrityChecker,
  private val httpClient: HttpClient,
  private val json: Json,
) : ModelDeployer {

  private val _modelState = MutableStateFlow<ModelDeploymentState>(ModelDeploymentState.NotDeployed)
  override val modelState: StateFlow<ModelDeploymentState> = _modelState.asStateFlow()

  private val deployMutex = Mutex()

  private val requiredModels: List<ModelInfo> by lazy {
    buildRequiredModels(context, json)
  }

  private val scope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
  )

  init {
    scope.launch {
      checkAndUpdateModelState()
    }
  }

  private suspend fun checkAndUpdateModelState() {
    try {
      val checkResult = checkModels()
      if (checkResult.allPresent && checkResult.outdatedModels.isEmpty()) {
        val versions = getDeployedModelVersions()
        _modelState.value = ModelDeploymentState.Deployed(versions)
      } else {
        _modelState.value = ModelDeploymentState.NotDeployed
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to check model state on init", e)
      _modelState.value = ModelDeploymentState.NotDeployed
    }
  }

  override suspend fun deployModels(): ModelDeploymentState = withContext(Dispatchers.IO) {
    deployMutex.withLock {
      try {
        val checkResult = checkModels()
        if (checkResult.allPresent && checkResult.outdatedModels.isEmpty()) {
          val versions = getDeployedModelVersions()
          val state = ModelDeploymentState.Deployed(versions)
          _modelState.value = state
          return@withContext state
        }

        val modelsToDeployNames = checkResult.missingModels + checkResult.outdatedModels
        val modelsToDeploy = requiredModels.filter { it.name in modelsToDeployNames }
        val totalModels = modelsToDeploy.size
        var deployedCount = 0

        for (model in modelsToDeploy) {
          val progress = deployedCount.toFloat() / totalModels
          _modelState.value = ModelDeploymentState.Deploying(progress, model.name)

          val success = deploySingleModel(model)
          if (!success) {
            val state = ModelDeploymentState.Error(
              "Failed to deploy model: ${model.name}",
              model.name,
            )
            _modelState.value = state
            return@withContext state
          }
          deployedCount++
        }

        val versions = getDeployedModelVersions()
        val state = ModelDeploymentState.Deployed(versions)
        _modelState.value = state
        state
      } catch (e: Exception) {
        val state = ModelDeploymentState.Error("Deployment failed: ${e.message}")
        _modelState.value = state
        state
      }
    }
  }

  override suspend fun checkModels(): ModelCheckResult = withContext(Dispatchers.IO) {
    val missing = mutableListOf<String>()
    val outdated = mutableListOf<String>()
    val deployedVersions = getDeployedModelVersions()

    for (model in requiredModels) {
      val targetFile = File(model.targetPath)
      if (!targetFile.exists()) {
        missing.add(model.name)
        continue
      }

      val deployedVersion = deployedVersions[model.name]
      if (deployedVersion == null || deployedVersion < model.version) {
        outdated.add(model.name)
        continue
      }

      val savedInfo = getModelInfo(model.name)
      if (savedInfo != null && savedInfo.hash.isNotEmpty()) {
        val currentHash = integrityChecker.computeHash(model.targetPath)
        if (currentHash != null && !currentHash.equals(savedInfo.hash, ignoreCase = true)) {
          outdated.add(model.name)
        }
      }
    }

    ModelCheckResult(
      allPresent = missing.isEmpty(),
      missingModels = missing,
      outdatedModels = outdated,
    )
  }

  override suspend fun deployModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
    val model = requiredModels.find { it.name == modelName } ?: return@withContext false
    deploySingleModel(model)
  }

  override suspend fun getDeployedModelVersions(): Map<String, Int> = withContext(Dispatchers.IO) {
    val versions = mutableMapOf<String, Int>()
    for (model in requiredModels) {
      val info = getModelInfo(model.name)
      if (info != null) {
        versions[model.name] = info.version
      }
    }
    versions
  }

  private suspend fun deploySingleModel(model: ModelInfo): Boolean {
    return try {
      val tempFile = if (model.assetPath.isNotEmpty() && assetExists(model.assetPath)) {
        extractFromAssets(model.assetPath)
      } else if (model.remoteUrl.isNotEmpty()) {
        downloadFromRemote(model.remoteUrl, model.name)
      } else {
        null
      }

      if (tempFile == null) {
        Log.e(TAG, "Failed to obtain model file: ${model.name}")
        return false
      }

      try {
        if (model.expectedHash.isNotEmpty()) {
          val actualHash = computeFileHash(tempFile)
          if (!actualHash.equals(model.expectedHash, ignoreCase = true)) {
            Log.e(
              TAG,
              "Hash mismatch for ${model.name}: expected=${model.expectedHash}, actual=$actualHash",
            )
            return false
          }
        }

        val deployResult = copyModelToTarget(tempFile, model.targetPath)
        if (!deployResult) {
          return false
        }

        val hash = integrityChecker.computeHash(model.targetPath) ?: ""
        saveModelInfo(model.name, model.version, hash)
        true
      } finally {
        tempFile.delete()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to deploy model ${model.name}", e)
      false
    }
  }

  private fun assetExists(path: String): Boolean {
    return try {
      context.assets.open(path).use { true }
    } catch (e: Exception) {
      false
    }
  }

  private suspend fun extractFromAssets(assetPath: String): File? = withContext(Dispatchers.IO) {
    try {
      val tempFile = File(context.cacheDir, "model_temp_${System.currentTimeMillis()}")
      context.assets.open(assetPath).use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output, DaemonConfig.IO.BUFFER_SIZE)
        }
      }
      tempFile
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract from assets: $assetPath", e)
      null
    }
  }

  private suspend fun downloadFromRemote(url: String, modelName: String): File? =
    withContext(Dispatchers.IO) {
      try {
        val tempFile =
          File(context.cacheDir, "model_download_${modelName}_${System.currentTimeMillis()}")
        val response = httpClient.get(url)
        response.bodyAsChannel().toInputStream().use { input ->
          FileOutputStream(tempFile).use { output ->
            input.copyTo(output, DaemonConfig.IO.BUFFER_SIZE)
          }
        }
        tempFile
      } catch (e: Exception) {
        Log.e(TAG, "Failed to download model from $url", e)
        null
      }
    }

  private fun computeFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DaemonConfig.IO.BUFFER_SIZE)
      var bytesRead: Int
      while (input.read(buffer).also { bytesRead = it } != -1) {
        digest.update(buffer, 0, bytesRead)
      }
    }
    return digest.digest().toHexString()
  }

  private suspend fun copyModelToTarget(sourceFile: File, targetPath: String): Boolean {
    val commands = listOf(
      "mkdir -p ${File(targetPath).parent}",
      "cp ${sourceFile.absolutePath} $targetPath",
      "chmod 644 $targetPath",
      "chown root:root $targetPath",
    )
    val result = rootShell.executeMultiple(commands, timeoutMs = 30_000)
    return result.isSuccess()
  }

  private suspend fun saveModelInfo(name: String, version: Int, hash: String) {
    val info =
      StoredModelInfo(version = version, hash = hash, deployedAt = System.currentTimeMillis())
    dataStore.edit { prefs ->
      prefs[modelKey(name)] = json.encodeToString(info)
    }
  }

  private suspend fun getModelInfo(name: String): StoredModelInfo? {
    return dataStore.data.map { prefs ->
      prefs[modelKey(name)]?.let {
        try {
          json.decodeFromString<StoredModelInfo>(it)
        } catch (e: Exception) {
          null
        }
      }
    }.first()
  }

  private fun modelKey(name: String) = stringPreferencesKey("model_$name")

  @Serializable
  private data class StoredModelInfo(
    val version: Int,
    val hash: String,
    val deployedAt: Long,
  )

  data class ModelInfo(
    val name: String,
    val assetPath: String,
    val remoteUrl: String,
    val targetPath: String,
    val expectedHash: String,
    val version: Int,
  )

  @Serializable
  private data class OcrManifest(
    val version: Int,
    val models: List<OcrModelEntry>,
  )

  @Serializable
  private data class OcrModelEntry(
    val name: String,
    val type: String,
    val assetPath: String,
    val targetPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val required: Boolean,
    val description: String = "",
    val source: String = "",
    val inputShape: List<Int>? = null,
    val outputShape: List<Int>? = null,
    val quantization: String? = null,
  )

  companion object {
    private const val TAG = "ModelDeployer"
    private const val OCR_MANIFEST_PATH = "models/ocr/manifest.json"

    private val STATIC_MODELS = listOf(
      ModelInfo(
        name = "detection",
        assetPath = "models/detection.tflite",
        remoteUrl = "",
        targetPath = "${DaemonConfig.MODELS_DIR}/detection.tflite",
        expectedHash = "",
        version = 1,
      ),
    )

    private val ocrModelNameMapping = mapOf(
      "ocr_detection" to "ocr_det",
      "ocr_recognition" to "ocr_rec",
      "ocr_dictionary" to "keys",
    )

    fun buildRequiredModels(context: Context, json: Json): List<ModelInfo> {
      val ocrModels = loadOcrModelsFromManifest(context, json)
      return STATIC_MODELS + ocrModels
    }

    private fun loadOcrModelsFromManifest(context: Context, json: Json): List<ModelInfo> {
      return try {
        val manifestJson = context.assets.open(OCR_MANIFEST_PATH).bufferedReader().use { it.readText() }
        val manifest = json.decodeFromString<OcrManifest>(manifestJson)

        manifest.models.map { entry ->
          val internalName = ocrModelNameMapping[entry.name] ?: entry.name
          ModelInfo(
            name = internalName,
            assetPath = "models/ocr/${entry.assetPath.removePrefix("models/")}",
            remoteUrl = "",
            targetPath = "${DaemonConfig.MODELS_DIR}/${entry.targetPath}",
            expectedHash = entry.sha256,
            version = manifest.version,
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load OCR manifest, using fallback", e)
        emptyList()
      }
    }
  }
}
