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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.deployment.contract.ContractVerificationResult
import me.fleey.futon.data.daemon.deployment.contract.ContractVerifier
import me.fleey.futon.data.daemon.deployment.contract.DeploymentReason
import me.fleey.futon.data.daemon.deployment.contract.ModuleContract
import me.fleey.futon.platform.root.RootChecker
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.RootType
import me.fleey.futon.platform.root.ShellResult
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream

/**
 * Deployment error types for proper i18n handling.
 */
sealed interface DeploymentError {
  data object ContractNotFound : DeploymentError
  data class ContractParseError(val details: String) : DeploymentError
  data class IncompatibleVersion(
    val appVersion: Int,
    val moduleVersionFrom: Int,
    val moduleVersionTo: Int?,
  ) : DeploymentError

  data class UnknownDeployment(val deployedHash: String) : DeploymentError
  data object RootNotAvailable : DeploymentError
  data object ExtractionFailed : DeploymentError
  data object InstallationTimeout : DeploymentError
  data class InstallationError(val details: String) : DeploymentError
  data class RootDenied(val details: String) : DeploymentError
  data object FallbackFailed : DeploymentError
  data object UninstallFailed : DeploymentError
  data class Unknown(val details: String?) : DeploymentError
}

/**
 * State of module deployment.
 */
sealed interface ModuleDeploymentState {
  data object Idle : ModuleDeploymentState
  data object Checking : ModuleDeploymentState
  data object Deploying : ModuleDeploymentState
  data class Deployed(
    val contract: ModuleContract,
    val needsReboot: Boolean,
  ) : ModuleDeploymentState

  data object UpToDate : ModuleDeploymentState
  data object NeedsInstall : ModuleDeploymentState
  data object NeedsUpdate : ModuleDeploymentState
  data class Failed(
    val error: DeploymentError,
    val canRetry: Boolean = true,
  ) : ModuleDeploymentState
}

/**
 * Deploys the Futon module (including daemon binary) to the device.
 */
interface ModuleDeployer {
  val deploymentState: StateFlow<ModuleDeploymentState>

  /**
   * Check deployment status without installing.
   * Returns NeedsInstall, NeedsUpdate, UpToDate, or Failed.
   */
  suspend fun checkDeploymentStatus(): ModuleDeploymentState

  /**
   * Check and deploy module if needed.
   *
   * @return Deployment result
   */
  suspend fun deployIfNeeded(): ModuleDeploymentState

  /**
   * Force redeploy the module.
   */
  suspend fun forceRedeploy(): ModuleDeploymentState

  /**
   * Uninstall the module.
   */
  suspend fun uninstall(): Result<Unit>
}

@Single(binds = [ModuleDeployer::class])
class ModuleDeployerImpl(
  private val context: Context,
  private val rootShell: RootShell,
  private val rootChecker: RootChecker,
  private val contractVerifier: ContractVerifier,
) : ModuleDeployer {

  private val _deploymentState = MutableStateFlow<ModuleDeploymentState>(ModuleDeploymentState.Idle)
  override val deploymentState: StateFlow<ModuleDeploymentState> = _deploymentState.asStateFlow()

  private val deployMutex = Mutex()

  override suspend fun checkDeploymentStatus(): ModuleDeploymentState =
    withContext(Dispatchers.IO) {
      when (val result = contractVerifier.verify()) {
        is ContractVerificationResult.UpToDate -> {
          ModuleDeploymentState.UpToDate
        }

        is ContractVerificationResult.NeedsDeployment -> {
          when (result.reason) {
            DeploymentReason.FIRST_INSTALL -> ModuleDeploymentState.NeedsInstall
            DeploymentReason.VERSION_UPDATE,
            DeploymentReason.HASH_MISMATCH,
            DeploymentReason.FILES_MISSING,
              -> ModuleDeploymentState.NeedsUpdate
          }
        }

        is ContractVerificationResult.Failed -> {
          val error = when (val e = result.error) {
            is me.fleey.futon.data.daemon.deployment.contract.ContractError.ContractNotFound ->
              DeploymentError.ContractNotFound

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.ParseError ->
              DeploymentError.ContractParseError(e.message)

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.IncompatibleVersion ->
              DeploymentError.IncompatibleVersion(
                e.appVersion,
                e.moduleVersionFrom,
                e.moduleVersionTo,
              )

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.UnknownDeployment ->
              DeploymentError.UnknownDeployment(e.deployedHash)
          }
          ModuleDeploymentState.Failed(error, canRetry = false)
        }
      }
    }

  override suspend fun deployIfNeeded(): ModuleDeploymentState = withContext(Dispatchers.IO) {
    deployMutex.withLock {
      _deploymentState.value = ModuleDeploymentState.Checking

      // Verify contract
      when (val result = contractVerifier.verify()) {
        is ContractVerificationResult.UpToDate -> {
          Log.d(TAG, "Module is up-to-date, skipping deployment")
          _deploymentState.value = ModuleDeploymentState.UpToDate
          return@withContext ModuleDeploymentState.UpToDate
        }

        is ContractVerificationResult.NeedsDeployment -> {
          Log.d(TAG, "Deployment needed: ${result.reason}")
          return@withContext performDeployment(result.expectedContract, result.reason)
        }

        is ContractVerificationResult.Failed -> {
          val error = when (val e = result.error) {
            is me.fleey.futon.data.daemon.deployment.contract.ContractError.ContractNotFound ->
              DeploymentError.ContractNotFound

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.ParseError ->
              DeploymentError.ContractParseError(e.message)

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.IncompatibleVersion ->
              DeploymentError.IncompatibleVersion(
                e.appVersion,
                e.moduleVersionFrom,
                e.moduleVersionTo,
              )

            is me.fleey.futon.data.daemon.deployment.contract.ContractError.UnknownDeployment ->
              DeploymentError.UnknownDeployment(e.deployedHash)
          }
          _deploymentState.value = ModuleDeploymentState.Failed(error, canRetry = false)
          return@withContext _deploymentState.value
        }
      }
    }
  }

  override suspend fun forceRedeploy(): ModuleDeploymentState = withContext(Dispatchers.IO) {
    deployMutex.withLock {
      _deploymentState.value = ModuleDeploymentState.Checking

      val contractResult = contractVerifier.getExpectedContract()
      if (contractResult.isFailure) {
        _deploymentState.value = ModuleDeploymentState.Failed(
          DeploymentError.Unknown(contractResult.exceptionOrNull()?.message),
        )
        return@withContext _deploymentState.value
      }

      performDeployment(contractResult.getOrThrow(), DeploymentReason.FIRST_INSTALL)
    }
  }

  override suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val result = rootShell.execute("rm -rf $MODULE_INSTALL_PATH", timeoutMs = 10_000)
      if (!result.isSuccess()) {
        throw Exception("uninstall_failed")
      }
      contractVerifier.clearDeploymentRecords()
      _deploymentState.value = ModuleDeploymentState.Idle
    }
  }

  private suspend fun performDeployment(
    contract: ModuleContract,
    reason: DeploymentReason,
  ): ModuleDeploymentState {
    _deploymentState.value = ModuleDeploymentState.Deploying

    try {
      val rootState = rootChecker.checkRoot()
      if (rootState !is RootState.Available) {
        _deploymentState.value = ModuleDeploymentState.Failed(DeploymentError.RootNotAvailable)
        return _deploymentState.value
      }

      // Check if root type supports modules
      val rootType = rootShell.getRootType()
      val supportsModules = rootType in listOf(
        RootType.KSU, RootType.KSU_NEXT, RootType.SUKISU_ULTRA,
        RootType.MAGISK, RootType.APATCH,
      )

      if (!supportsModules) {
        Log.w(TAG, "Root type $rootType doesn't support modules, using fallback deployment")
        return performFallbackDeployment(contract)
      }

      // Extract module to temp directory
      val tempDir = extractModuleToTemp()
      if (tempDir == null) {
        _deploymentState.value = ModuleDeploymentState.Failed(DeploymentError.ExtractionFailed)
        return _deploymentState.value
      }

      try {
        val installResult = installModule(tempDir)
        if (!installResult.isSuccess()) {
          val error = when (installResult) {
            is ShellResult.Error -> DeploymentError.InstallationError(installResult.message)
            is ShellResult.Timeout -> DeploymentError.InstallationTimeout
            is ShellResult.RootDenied -> DeploymentError.RootDenied(installResult.reason)
            else -> DeploymentError.Unknown(null)
          }
          _deploymentState.value = ModuleDeploymentState.Failed(error)
          return _deploymentState.value
        }

        // Execute post-fs-data.sh for immediate SELinux policy
        val selinuxResult = executePostFsData()
        if (!selinuxResult.isSuccess()) {
          Log.w(
            TAG,
            "post-fs-data.sh execution failed, SELinux policies may not be active until reboot",
          )
        }

        // Record deployment
        contractVerifier.recordDeployment(contract)

        val state = ModuleDeploymentState.Deployed(
          contract = contract,
          needsReboot = !selinuxResult.isSuccess(),
        )
        _deploymentState.value = state
        Log.i(TAG, "Module deployed successfully: ${contract.daemonHash}")
        return state

      } finally {
        // Cleanup temp directory
        tempDir.deleteRecursively()
      }

    } catch (e: Exception) {
      Log.e(TAG, "Deployment failed", e)
      _deploymentState.value = ModuleDeploymentState.Failed(DeploymentError.Unknown(e.message))
      return _deploymentState.value
    }
  }

  private suspend fun performFallbackDeployment(contract: ModuleContract): ModuleDeploymentState {
    // Fallback: Deploy daemon binary and libraries without module system
    // This is for root solutions that don't support modules (e.g., SuperSU)
    Log.d(TAG, "Performing fallback deployment (binary + libs)")

    try {
      // Extract daemon binary
      val assetPath = "futon_module/bin/${contract.daemonArch}/futon_daemon"
      val tempFile = File(context.cacheDir, "futon_daemon_temp")

      context.assets.open(assetPath).use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output)
        }
      }

      // Extract LiteRT libraries
      val libAssetPath = "futon_module/lib/${contract.daemonArch}"
      val tempLibDir = File(context.cacheDir, "futon_lib_temp")
      tempLibDir.mkdirs()

      val libFiles = mutableListOf<File>()
      try {
        context.assets.list(libAssetPath)?.forEach { libName ->
          if (libName.endsWith(".so")) {
            val libTempFile = File(tempLibDir, libName)
            context.assets.open("$libAssetPath/$libName").use { input ->
              FileOutputStream(libTempFile).use { output ->
                input.copyTo(output)
              }
            }
            libFiles.add(libTempFile)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "No libraries found in assets: $libAssetPath")
      }

      // Deploy binary and libraries
      val commands = mutableListOf(
        "mkdir -p ${DaemonConfig.BASE_DIR}",
        "mkdir -p ${DaemonConfig.LIB_DIR}",
        "cp ${tempFile.absolutePath} ${DaemonConfig.BINARY_PATH}",
        "chmod 755 ${DaemonConfig.BINARY_PATH}",
        "chown root:root ${DaemonConfig.BINARY_PATH}",
      )

      libFiles.forEach { libFile ->
        commands.add("cp ${libFile.absolutePath} ${DaemonConfig.LIB_DIR}/${libFile.name}")
        commands.add("chmod 755 ${DaemonConfig.LIB_DIR}/${libFile.name}")
      }

      val result = rootShell.executeMultiple(commands, timeoutMs = 15_000)
      tempFile.delete()
      tempLibDir.deleteRecursively()

      if (!result.isSuccess()) {
        _deploymentState.value = ModuleDeploymentState.Failed(DeploymentError.FallbackFailed)
        return _deploymentState.value
      }

      contractVerifier.recordDeployment(contract)

      val state = ModuleDeploymentState.Deployed(
        contract = contract,
        needsReboot = false,
      )
      _deploymentState.value = state
      return state

    } catch (e: Exception) {
      _deploymentState.value = ModuleDeploymentState.Failed(DeploymentError.FallbackFailed)
      return _deploymentState.value
    }
  }

  private fun extractModuleToTemp(): File? {
    return try {
      val tempDir = File(context.cacheDir, "futon_module_temp")
      tempDir.deleteRecursively()
      tempDir.mkdirs()

      // List and copy all files from assets/futon_module
      val assetManager = context.assets
      copyAssetDirectory(assetManager, "futon_module", tempDir)

      tempDir
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract module", e)
      null
    }
  }

  private fun copyAssetDirectory(
    assetManager: android.content.res.AssetManager,
    assetPath: String,
    targetDir: File,
  ) {
    val files = assetManager.list(assetPath) ?: return

    if (files.isEmpty()) {
      // It's a file, copy it
      assetManager.open(assetPath).use { input ->
        File(targetDir, File(assetPath).name).outputStream().use { output ->
          input.copyTo(output)
        }
      }
    } else {
      // It's a directory, recurse
      targetDir.mkdirs()
      for (file in files) {
        val subAssetPath = "$assetPath/$file"
        val subTargetDir = if (assetPath == "futon_module") targetDir else File(targetDir, file)

        val subFiles = assetManager.list(subAssetPath)
        if (subFiles.isNullOrEmpty()) {
          assetManager.open(subAssetPath).use { input ->
            File(targetDir, file).outputStream().use { output ->
              input.copyTo(output)
            }
          }
        } else {
          // It's a subdirectory
          val subDir = File(targetDir, file)
          subDir.mkdirs()
          copyAssetDirectory(assetManager, subAssetPath, subDir)
        }
      }
    }
  }

  private suspend fun installModule(tempDir: File): ShellResult {
    val commands = listOf(
      "rm -rf $MODULE_INSTALL_PATH",
      "mkdir -p $MODULE_INSTALL_PATH",
      "cp -r ${tempDir.absolutePath}/* $MODULE_INSTALL_PATH/",
      "chmod 644 $MODULE_INSTALL_PATH/module.prop",
      "chmod 755 $MODULE_INSTALL_PATH/*.sh 2>/dev/null || true",
      "chmod -R 755 $MODULE_INSTALL_PATH/bin",
      "chown -R root:root $MODULE_INSTALL_PATH",
    )

    return rootShell.executeMultiple(commands, timeoutMs = 30_000)
  }

  private suspend fun executePostFsData(): ShellResult {
    return rootShell.execute(
      "sh $MODULE_INSTALL_PATH/post-fs-data.sh",
      timeoutMs = 60_000,  // SELinux policy patching can take time
    )
  }

  companion object {
    private const val TAG = "ModuleDeployer"
    private const val MODULE_INSTALL_PATH = "/data/adb/modules/futon_daemon"
  }
}
