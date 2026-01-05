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
package me.fleey.futon.data.localmodel.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.fleey.futon.data.localmodel.registry.ModelRegistry
import org.koin.core.annotation.Single

/**
 * Coordinates download notifications by observing download state changes
 * and updating notifications accordingly.
 */
@Single
class DownloadNotificationCoordinator(
  private val modelDownloader: ModelDownloader,
  private val notificationManager: DownloadNotificationManager,
  private val modelRegistry: ModelRegistry,
) {

  /** Scope for observation coroutines */
  private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  /** Active observation jobs by model ID */
  private val observationJobs = mutableMapOf<String, Job>()

  /**
   * Start observing download state for a model and update notifications.
   *
   *
   * @param modelId The model ID to observe
   */
  fun startObserving(modelId: String) {
    observationJobs[modelId]?.cancel()

    val modelName = modelRegistry.getModelInfo(modelId)?.name ?: modelId

    val job = coordinatorScope.launch {
      modelDownloader.getDownloadState(modelId).collectLatest { state ->
        handleStateChange(modelId, modelName, state)
      }
    }

    observationJobs[modelId] = job
  }

  /**
   * Stop observing download state for a model.
   *
   * @param modelId The model ID to stop observing
   */
  private fun stopObserving(modelId: String) {
    observationJobs[modelId]?.cancel()
    observationJobs.remove(modelId)
  }

  fun stopAll() {
    observationJobs.values.forEach { it.cancel() }
    observationJobs.clear()
    notificationManager.cancelAllNotifications()
  }

  /**
   * Handle download state changes and update notifications.
   */
  private fun handleStateChange(
    modelId: String,
    modelName: String,
    state: DownloadState,
  ) {
    when (state) {
      is DownloadState.Idle -> {
        // No notification needed for idle state
      }

      is DownloadState.Downloading -> {
        // Update progress notification (throttled internally)
        notificationManager.showDownloadProgress(modelId, modelName, state)
      }

      is DownloadState.Paused -> {
        notificationManager.showDownloadPaused(modelId, modelName, state)
      }

      is DownloadState.Completed -> {
        notificationManager.showDownloadComplete(modelId, modelName)
        // Stop observing since download is complete
        stopObserving(modelId)
      }

      is DownloadState.Failed -> {
        notificationManager.showDownloadFailed(modelId, modelName, state.error)
        stopObserving(modelId)
      }
    }
  }
}
