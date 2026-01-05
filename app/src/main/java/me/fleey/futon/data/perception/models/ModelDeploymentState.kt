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
 * Deployment status for an individual model.
 */
sealed interface ModelDeploymentStatus {
  data object NotDeployed : ModelDeploymentStatus

  data class Deploying(val progress: Float) : ModelDeploymentStatus

  data object Deployed : ModelDeploymentStatus

  data object Corrupted : ModelDeploymentStatus

  data class Failed(val reason: String) : ModelDeploymentStatus
}

data class ModelDeploymentState(
  val models: Map<String, ModelDeploymentStatus> = emptyMap(),
  val overallProgress: Float = 0f,
  val isDeploying: Boolean = false,
  val lastError: String? = null,
) {
  val allDeployed: Boolean
    get() = models.isNotEmpty() && models.values.all { it is ModelDeploymentStatus.Deployed }

  val requiredModelsDeployed: Boolean
    get() = models.filterKeys { it in REQUIRED_MODELS }
      .values.all { it is ModelDeploymentStatus.Deployed }

  val hasCorruptedModels: Boolean
    get() = models.values.any { it is ModelDeploymentStatus.Corrupted }

  val hasFailedModels: Boolean
    get() = models.values.any { it is ModelDeploymentStatus.Failed }

  companion object {
    val REQUIRED_MODELS = setOf("ocr_recognition", "ocr_dictionary")
  }
}
