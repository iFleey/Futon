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
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException

/**
 * Implementation of [ModelDeployer] that uses root shell for file operations.
 */
@Single(binds = [ModelDeployer::class])
class ModelDeployerImpl(
  private val context: Context,
) : ModelDeployer {

  companion object {
    private const val TAG = "ModelDeployer"
    private const val BUFFER_SIZE = 8192
    private const val FILE_PERMISSION = "644"
    private const val DIR_PERMISSION = "755"
  }

  override suspend fun deployModels(
    manifest: ModelManifest,
    onProgress: (modelName: String, progress: Float) -> Unit,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      ensureModelDirectory().getOrThrow()

      val totalSize = manifest.models.sumOf { it.sizeBytes }
      var deployedSize = 0L

      for (model in manifest.models) {
        onProgress(model.name, deployedSize.toFloat() / totalSize.coerceAtLeast(1))

        if (!verifyModel(model)) {
          Log.d(TAG, "Deploying model: ${model.name}")
          deployModel(model).getOrThrow()
        } else {
          Log.d(TAG, "Model already deployed and valid: ${model.name}")
        }

        deployedSize += model.sizeBytes
        onProgress(model.name, deployedSize.toFloat() / totalSize.coerceAtLeast(1))
      }

      notifyDaemonReload().getOrThrow()
    }
  }

  override suspend fun deployModel(metadata: ModelMetadata): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val targetPath = "${DaemonConfig.MODELS_DIR}/${metadata.targetPath}"

        val tempFile = File(context.cacheDir, metadata.targetPath)
        try {
          context.assets.open(metadata.assetPath).use { input ->
            tempFile.outputStream().use { output ->
              input.copyTo(output, BUFFER_SIZE)
            }
          }

          val copyResult = Shell.cmd(
            "cp '${tempFile.absolutePath}' '$targetPath'",
          ).exec()

          if (!copyResult.isSuccess) {
            val errorMsg = copyResult.err.joinToString("\n").ifEmpty { "Unknown copy error" }
            throw IOException("Failed to copy model to target: $errorMsg")
          }

          val chmodResult = Shell.cmd("chmod $FILE_PERMISSION '$targetPath'").exec()
          if (!chmodResult.isSuccess) {
            Log.w(TAG, "Failed to set permissions on $targetPath: ${chmodResult.err}")
          }

          if (!verifyModel(metadata)) {
            Shell.cmd("rm -f '$targetPath'").exec()
            throw IOException("Model verification failed after deployment: checksum mismatch")
          }

          Log.i(TAG, "Successfully deployed model: ${metadata.name}")
          Unit
        } finally {
          tempFile.delete()
        }
      }
    }

  override suspend fun verifyModel(metadata: ModelMetadata): Boolean =
    withContext(Dispatchers.IO) {
      val targetPath = "${DaemonConfig.MODELS_DIR}/${metadata.targetPath}"

      val existsResult = Shell.cmd("test -f '$targetPath' && echo 'exists'").exec()
      if (existsResult.out.firstOrNull() != "exists") {
        return@withContext false
      }

      val sha256Result = Shell.cmd("sha256sum '$targetPath'").exec()
      if (!sha256Result.isSuccess) {
        Log.w(TAG, "Failed to compute checksum for $targetPath")
        return@withContext false
      }

      val actualHash = sha256Result.out.firstOrNull()?.split(" ")?.firstOrNull()
      val matches = actualHash?.equals(metadata.sha256, ignoreCase = true) == true

      if (!matches) {
        Log.d(
          TAG,
          "Checksum mismatch for ${metadata.name}: expected=${metadata.sha256}, actual=$actualHash",
        )
      }

      matches
    }

  override suspend fun getDeployedModels(): List<String> = withContext(Dispatchers.IO) {
    val result = Shell.cmd("ls -1 '${DaemonConfig.MODELS_DIR}' 2>/dev/null").exec()
    if (result.isSuccess) {
      result.out.filter { it.isNotBlank() }
    } else {
      emptyList()
    }
  }

  override suspend fun deleteModel(name: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val targetPath = "${DaemonConfig.MODELS_DIR}/$name"
      val result = Shell.cmd("rm -f '$targetPath'").exec()
      if (!result.isSuccess) {
        val errorMsg = result.err.joinToString("\n").ifEmpty { "Unknown delete error" }
        throw IOException("Failed to delete model: $errorMsg")
      }
      Log.i(TAG, "Deleted model: $name")
      Unit
    }
  }

  override suspend fun notifyDaemonReload(): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val result = Shell.cmd("pkill -HUP -f ${DaemonConfig.PROCESS_NAME}").exec()
      if (!result.isSuccess) {
        Log.w(TAG, "Failed to signal daemon reload (daemon may not be running)")
      }
      Log.d(TAG, "Sent reload signal to daemon")
      Unit
    }
  }

  private suspend fun ensureModelDirectory(): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val result = Shell.cmd(
        "mkdir -p '${DaemonConfig.MODELS_DIR}'",
        "chmod $DIR_PERMISSION '${DaemonConfig.MODELS_DIR}'",
      ).exec()

      if (!result.isSuccess) {
        val errorMsg = result.err.joinToString("\n").ifEmpty { "Unknown error creating directory" }
        throw IOException("Failed to create model directory: $errorMsg")
      }
      Unit
    }
  }
}
