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
package me.fleey.futon.data.daemon.deployment.contract

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.fleey.futon.BuildConfig
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import org.koin.core.annotation.Single

/**
 * Verifies deployment contracts and manages deployment state.
 */
interface ContractVerifier {
  /**
   * Verify if current deployment matches the expected contract.
   *
   * @return Verification result indicating if deployment is needed
   */
  suspend fun verify(): ContractVerificationResult

  /**
   * Get the expected module contract from assets.
   */
  suspend fun getExpectedContract(): Result<ModuleContract>

  /**
   * Get the currently deployed contract info (if any).
   */
  suspend fun getDeployedContract(): DeployedContractInfo?

  /**
   * Record a successful deployment.
   *
   * @param contract The contract that was deployed
   */
  suspend fun recordDeployment(contract: ModuleContract)

  /**
   * Clear deployment records (for testing or reset).
   */
  suspend fun clearDeploymentRecords()
}

/**
 * Information about the currently deployed module.
 */
data class DeployedContractInfo(
  val daemonHash: String,
  val moduleVersionCode: Int,
  val deployedAt: Long,
)

@Single(binds = [ContractVerifier::class])
class ContractVerifierImpl(
  private val context: Context,
  private val dataStore: DataStore<Preferences>,
  private val rootShell: RootShell,
) : ContractVerifier {

  override suspend fun verify(): ContractVerificationResult = withContext(Dispatchers.IO) {
    val contractResult = getExpectedContract()
    if (contractResult.isFailure) {
      val error = contractResult.exceptionOrNull()
      return@withContext ContractVerificationResult.Failed(
        ContractError.ParseError(error?.message ?: "Unknown error"),
      )
    }

    val expectedContract = contractResult.getOrThrow()

    val appVersionCode = BuildConfig.VERSION_CODE
    if (!expectedContract.isCompatibleWith(appVersionCode)) {
      return@withContext ContractVerificationResult.Failed(
        ContractError.IncompatibleVersion(
          appVersion = appVersionCode,
          moduleVersionFrom = expectedContract.appVersionFrom,
          moduleVersionTo = expectedContract.appVersionTo,
        ),
      )
    }

    val deployedInfo = getDeployedContract()

    when {
      deployedInfo == null -> {
        ContractVerificationResult.NeedsDeployment(
          reason = DeploymentReason.FIRST_INSTALL,
          expectedContract = expectedContract,
        )
      }

      deployedInfo.daemonHash != expectedContract.daemonHash -> {
        // Hash mismatch - update available
        ContractVerificationResult.NeedsDeployment(
          reason = DeploymentReason.HASH_MISMATCH,
          expectedContract = expectedContract,
        )
      }

      deployedInfo.moduleVersionCode < expectedContract.versionCode -> {
        // Module version update
        ContractVerificationResult.NeedsDeployment(
          reason = DeploymentReason.VERSION_UPDATE,
          expectedContract = expectedContract,
        )
      }

      !checkModuleFilesExist() -> {
        // Files missing
        ContractVerificationResult.NeedsDeployment(
          reason = DeploymentReason.FILES_MISSING,
          expectedContract = expectedContract,
        )
      }

      else -> {
        // Everything is up-to-date
        ContractVerificationResult.UpToDate
      }
    }
  }

  override suspend fun getExpectedContract(): Result<ModuleContract> {
    return ModuleContract.fromAssets(context)
  }

  override suspend fun getDeployedContract(): DeployedContractInfo? = withContext(Dispatchers.IO) {
    val prefs = dataStore.data.first()
    val hash = prefs[KEY_DEPLOYED_DAEMON_HASH] ?: return@withContext null
    val versionCode = prefs[KEY_DEPLOYED_MODULE_VERSION] ?: return@withContext null
    val deployedAt = prefs[KEY_DEPLOYED_AT] ?: 0L

    DeployedContractInfo(
      daemonHash = hash,
      moduleVersionCode = versionCode,
      deployedAt = deployedAt,
    )
  }

  override suspend fun recordDeployment(contract: ModuleContract) {
    dataStore.edit { prefs ->
      prefs[KEY_DEPLOYED_DAEMON_HASH] = contract.daemonHash
      prefs[KEY_DEPLOYED_MODULE_VERSION] = contract.versionCode
      prefs[KEY_DEPLOYED_AT] = System.currentTimeMillis()
      prefs[KEY_DEPLOYED_ARCH] = contract.daemonArch
    }
  }

  override suspend fun clearDeploymentRecords() {
    dataStore.edit { prefs ->
      prefs.remove(KEY_DEPLOYED_DAEMON_HASH)
      prefs.remove(KEY_DEPLOYED_MODULE_VERSION)
      prefs.remove(KEY_DEPLOYED_AT)
      prefs.remove(KEY_DEPLOYED_ARCH)
    }
  }

  /**
   * Check if module files exist on the device.
   */
  private suspend fun checkModuleFilesExist(): Boolean {
    val result = rootShell.execute(
      "test -f $MODULE_INSTALL_PATH/module.prop && " +
        "test -d $MODULE_INSTALL_PATH/bin && " +
        "echo exists",
      timeoutMs = 5000,
    )
    return result is ShellResult.Success && result.output.contains("exists")
  }

  companion object {
    private const val MODULE_INSTALL_PATH = "/data/adb/modules/futon_daemon"

    private val KEY_DEPLOYED_DAEMON_HASH = stringPreferencesKey("deployed_daemon_hash")
    private val KEY_DEPLOYED_MODULE_VERSION = intPreferencesKey("deployed_module_version")
    private val KEY_DEPLOYED_AT = longPreferencesKey("deployed_at")
    private val KEY_DEPLOYED_ARCH = stringPreferencesKey("deployed_arch")
  }
}
