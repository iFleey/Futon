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

/**
 * Handles file operations for model deployment.
 */
interface ModelDeployer {
  suspend fun deployModels(
    manifest: ModelManifest,
    onProgress: (modelName: String, progress: Float) -> Unit,
  ): Result<Unit>

  suspend fun deployModel(metadata: ModelMetadata): Result<Unit>

  suspend fun verifyModel(metadata: ModelMetadata): Boolean

  suspend fun getDeployedModels(): List<String>

  suspend fun deleteModel(name: String): Result<Unit>

  suspend fun notifyDaemonReload(): Result<Unit>
}
