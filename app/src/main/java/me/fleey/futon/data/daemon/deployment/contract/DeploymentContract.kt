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
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.Properties

/**
 * Deployment contract metadata extracted from module.prop.
 *
 * This data is generated at build time and embedded in the module.
 * It serves as the single source of truth for deployment verification.
 */
@Serializable
data class ModuleContract(
  val id: String,

  val name: String,

  /** Module version string (e.g., "1.1.0") */
  val version: String,

  val versionCode: Int,

  /** SHA256 hash of the daemon binary */
  val daemonHash: String,

  /** Target architecture (e.g., "arm64-v8a") */
  val daemonArch: String,

  /** Minimum app versionCode this module is compatible with */
  val appVersionFrom: Int,

  /** Maximum app versionCode (null means open-ended) */
  val appVersionTo: Int?,

  val generatedAt: String,
) {
  companion object {
    private const val ASSET_MODULE_PATH = "futon_module/module.prop"

    fun fromAssets(context: Context): Result<ModuleContract> = runCatching {
      context.assets.open(ASSET_MODULE_PATH).use { stream ->
        parseModuleProp(stream)
      }
    }

    private fun parseModuleProp(inputStream: InputStream): ModuleContract {
      val props = Properties()
      inputStream.bufferedReader().use { reader ->
        // Filter out comment lines before parsing
        val filteredContent = reader.lineSequence()
          .filter { !it.trimStart().startsWith("#") }
          .joinToString("\n")
        props.load(filteredContent.byteInputStream())
      }

      return ModuleContract(
        id = props.getProperty("id") ?: error("Missing 'id' in module.prop"),
        name = props.getProperty("name") ?: error("Missing 'name' in module.prop"),
        version = props.getProperty("version") ?: error("Missing 'version' in module.prop"),
        versionCode = props.getProperty("versionCode")?.toIntOrNull()
          ?: error("Missing or invalid 'versionCode' in module.prop"),
        daemonHash = props.getProperty("daemonHash")
          ?: error("Missing 'daemonHash' in module.prop"),
        daemonArch = props.getProperty("daemonArch")
          ?: error("Missing 'daemonArch' in module.prop"),
        appVersionFrom = props.getProperty("appVersionFrom")?.toIntOrNull()
          ?: error("Missing or invalid 'appVersionFrom' in module.prop"),
        appVersionTo = props.getProperty("appVersionTo")?.let {
          if (it == "open") null else it.toIntOrNull()
        },
        generatedAt = props.getProperty("generatedAt") ?: "unknown",
      )
    }
  }

  /**
   * Check if this module is compatible with the given app versionCode.
   */
  fun isCompatibleWith(appVersionCode: Int): Boolean {
    val maxVersion = appVersionTo ?: Int.MAX_VALUE
    return appVersionCode in appVersionFrom..maxVersion
  }
}

/**
 * Result of deployment contract verification.
 */
sealed interface ContractVerificationResult {
  data object UpToDate : ContractVerificationResult

  data class NeedsDeployment(
    val reason: DeploymentReason,
    val expectedContract: ModuleContract,
  ) : ContractVerificationResult

  data class Failed(
    val error: ContractError,
  ) : ContractVerificationResult
}

enum class DeploymentReason {
  FIRST_INSTALL,

  HASH_MISMATCH,

  FILES_MISSING,

  VERSION_UPDATE
}

sealed interface ContractError {
  data object ContractNotFound : ContractError

  data class ParseError(val message: String) : ContractError

  data class IncompatibleVersion(
    val appVersion: Int,
    val moduleVersionFrom: Int,
    val moduleVersionTo: Int?,
  ) : ContractError

  /** Deployed daemon hash doesn't match any known contract */
  data class UnknownDeployment(val deployedHash: String) : ContractError
}
