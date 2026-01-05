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
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.platform.root.RootChecker
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream

interface DaemonDeployer {
  val deploymentState: StateFlow<DeploymentState>

  suspend fun deploy(forceRedeploy: Boolean = false): DeploymentState
  suspend fun getDeployedVersion(): Int?
  suspend fun needsDeployment(expectedVersion: Int): Boolean
  suspend fun cleanup()
}

@Single(binds = [DaemonDeployer::class])
class DaemonDeployerImpl(
  private val context: Context,
  private val dataStore: DataStore<Preferences>,
  private val rootShell: RootShell,
  private val rootChecker: RootChecker,
  private val integrityChecker: BinaryIntegrityChecker,
) : DaemonDeployer {

  private val _deploymentState = MutableStateFlow<DeploymentState>(DeploymentState.NotDeployed)
  override val deploymentState: StateFlow<DeploymentState> = _deploymentState.asStateFlow()

  private val deployMutex = Mutex()

  override suspend fun deploy(forceRedeploy: Boolean): DeploymentState =
    withContext(Dispatchers.IO) {
      deployMutex.withLock {
        // Skip deployment if already deployed and not forcing
        if (!forceRedeploy) {
          val currentState = _deploymentState.value
          if (currentState is DeploymentState.Deployed) {
            return@withContext currentState
          }
        }

        _deploymentState.value = DeploymentState.Deploying

        try {
          val rootState = rootChecker.checkRoot()
          if (rootState !is RootState.Available) {
            val reason = when (rootState) {
              is RootState.Unavailable -> rootState.reason
              is RootState.SELinuxBlocked -> "SELinux blocked: ${rootState.details}"
              else -> "Root not available"
            }
            val state = DeploymentState.Failed(reason)
            _deploymentState.value = state
            return@withContext state
          }

          val arch = getSupportedArch()
          if (arch == null) {
            val state =
              DeploymentState.Failed("Unsupported architecture: ${Build.SUPPORTED_ABIS.joinToString()}")
            _deploymentState.value = state
            return@withContext state
          }

          val assetPath = DaemonConfig.getAssetBinaryPath(arch)
          if (!assetExists(assetPath)) {
            val state = DeploymentState.Failed("Daemon binary not found in assets: $assetPath")
            _deploymentState.value = state
            return@withContext state
          }

          val tempFile = extractToTemp(assetPath)
          if (tempFile == null) {
            val state = DeploymentState.Failed("Failed to extract daemon binary")
            _deploymentState.value = state
            return@withContext state
          }

          try {
            val deployResult = deployBinary(tempFile)
            if (!deployResult.isSuccess()) {
              val errorMsg = deployResult.getErrorMessage() ?: "Unknown deployment error"
              val state = DeploymentState.Failed(errorMsg)
              _deploymentState.value = state
              return@withContext state
            }

            val selinuxResult = setSELinuxContext()
            if (!selinuxResult.isSuccess()) {
              Log.w(
                TAG,
                "Failed to set SELinux context: ${selinuxResult.getErrorMessage()}",
              )
            }

            val hash = integrityChecker.computeHash(DaemonConfig.BINARY_PATH)
            saveDeploymentInfo(
              version = DaemonConfig.PROTOCOL_VERSION,
              hash = hash ?: "",
              arch = arch,
            )

            val state = DeploymentState.Deployed(DaemonConfig.PROTOCOL_VERSION)
            _deploymentState.value = state
            state
          } finally {
            tempFile.delete()
          }
        } catch (e: Exception) {
          val state = DeploymentState.Failed("Deployment failed: ${e.message}", e)
          _deploymentState.value = state
          state
        }
      }
    }

  override suspend fun getDeployedVersion(): Int? = withContext(Dispatchers.IO) {
    dataStore.data.map { prefs ->
      prefs[KEY_DEPLOYED_VERSION]
    }.first()
  }

  override suspend fun needsDeployment(expectedVersion: Int): Boolean =
    withContext(Dispatchers.IO) {
      val deployedVersion = getDeployedVersion()
      if (deployedVersion == null || deployedVersion < expectedVersion) {
        return@withContext true
      }

      val binaryExists = File(DaemonConfig.BINARY_PATH).exists()
      if (!binaryExists) {
        return@withContext true
      }

      val savedHash = dataStore.data.map { it[KEY_DEPLOYED_HASH] }.first()
      if (savedHash.isNullOrEmpty()) {
        return@withContext true
      }

      val integrityState = integrityChecker.checkIntegrity(savedHash)
      integrityState != IntegrityState.Verified
    }

  override suspend fun cleanup() = withContext(Dispatchers.IO) {
    deployMutex.withLock {
      rootShell.execute("rm -rf ${DaemonConfig.BASE_DIR}")
      dataStore.edit { prefs ->
        prefs.remove(KEY_DEPLOYED_VERSION)
        prefs.remove(KEY_DEPLOYED_AT)
        prefs.remove(KEY_DEPLOYED_HASH)
        prefs.remove(KEY_DEPLOYED_ARCH)
      }
      _deploymentState.value = DeploymentState.NotDeployed
    }
  }

  private fun getSupportedArch(): String? {
    for (abi in Build.SUPPORTED_ABIS) {
      when (abi) {
        "arm64-v8a" -> return "arm64-v8a"
        "armeabi-v7a" -> return "armeabi-v7a"
        "x86_64" -> return "x86_64"
        "x86" -> return "x86"
      }
    }
    return null
  }

  private fun assetExists(path: String): Boolean {
    return try {
      context.assets.open(path).use { true }
    } catch (e: Exception) {
      false
    }
  }

  private suspend fun extractToTemp(assetPath: String): File? = withContext(Dispatchers.IO) {
    try {
      val tempFile = File(context.cacheDir, DaemonConfig.TEMP_BINARY_NAME)
      context.assets.open(assetPath).use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output, DaemonConfig.IO.BUFFER_SIZE)
        }
      }
      tempFile
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract daemon binary", e)
      null
    }
  }

  private suspend fun deployBinary(tempFile: File): ShellResult {
    val commands = listOf(
      "mkdir -p ${DaemonConfig.MODELS_DIR} ${DaemonConfig.CONFIG_DIR}",
      "cp ${tempFile.absolutePath} ${DaemonConfig.BINARY_PATH}",
      "chmod 755 ${DaemonConfig.BINARY_PATH}",
      "chown root:root ${DaemonConfig.BINARY_PATH}",
    )
    return rootShell.executeMultiple(commands, timeoutMs = 10_000)
  }

  private suspend fun setSELinuxContext(): ShellResult {
    return rootShell.execute(
      "chcon u:object_r:system_file:s0 ${DaemonConfig.BINARY_PATH}",
      timeoutMs = 5_000,
    )
  }

  private suspend fun saveDeploymentInfo(version: Int, hash: String, arch: String) {
    dataStore.edit { prefs ->
      prefs[KEY_DEPLOYED_VERSION] = version
      prefs[KEY_DEPLOYED_AT] = System.currentTimeMillis()
      prefs[KEY_DEPLOYED_HASH] = hash
      prefs[KEY_DEPLOYED_ARCH] = arch
    }
  }

  private fun ShellResult.getErrorMessage(): String? = when (this) {
    is ShellResult.Success -> if (!isSuccessful) "Exit code: $exitCode" else null
    is ShellResult.Error -> message
    is ShellResult.Timeout -> "Timed out after ${timeoutMs}ms"
    is ShellResult.RootDenied -> reason
  }

  companion object {
    private const val TAG = "DaemonDeployer"

    private val KEY_DEPLOYED_VERSION = intPreferencesKey("deployed_daemon_version")
    private val KEY_DEPLOYED_AT = longPreferencesKey("deployed_daemon_at")
    private val KEY_DEPLOYED_HASH = stringPreferencesKey("deployed_daemon_hash")
    private val KEY_DEPLOYED_ARCH = stringPreferencesKey("deployed_daemon_arch")
  }
}
