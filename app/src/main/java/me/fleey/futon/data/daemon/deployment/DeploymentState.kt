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

sealed interface DeploymentState {
  data object NotDeployed : DeploymentState
  data object Deploying : DeploymentState
  data class Deployed(val version: Int) : DeploymentState
  data class Failed(val reason: String, val cause: Throwable? = null) : DeploymentState
}

sealed interface IntegrityState {
  data object Unknown : IntegrityState
  data object Verified : IntegrityState
  data object Tampered : IntegrityState
  data class CheckFailed(val reason: String) : IntegrityState
}

sealed interface RootState {
  data object NotChecked : RootState
  data object Available : RootState
  data class Unavailable(val reason: String) : RootState
  data class SELinuxBlocked(val details: String) : RootState
}

sealed interface ModelDeploymentState {
  data object NotDeployed : ModelDeploymentState
  data class Deploying(val progress: Float, val currentModel: String) : ModelDeploymentState
  data class Deployed(val modelVersions: Map<String, Int>) : ModelDeploymentState
  data class Error(val reason: String, val failedModel: String? = null) : ModelDeploymentState
}

data class ModelInfo(
  val name: String,
  val assetPath: String,
  val targetPath: String,
  val expectedHash: String,
  val version: Int,
)

data class DeployedDaemonInfo(
  val version: Int,
  val deployedAt: Long,
  val binaryHash: String,
  val arch: String,
)

data class DeployedModelInfo(
  val name: String,
  val version: Int,
  val deployedAt: Long,
  val hash: String,
)
