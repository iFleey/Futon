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

/**
 * Manages download notifications for model downloads.
 * */
interface DownloadNotificationManager {

  fun showDownloadProgress(
    modelId: String,
    modelName: String,
    state: DownloadState.Downloading,
  )

  fun showDownloadPaused(
    modelId: String,
    modelName: String,
    state: DownloadState.Paused,
  )

  fun showDownloadComplete(
    modelId: String,
    modelName: String,
  )

  fun showDownloadFailed(
    modelId: String,
    modelName: String,
    error: DownloadError,
  )

  /**
   * Cancel/dismiss a notification for a specific model.
   *
   * @param modelId Unique identifier of the model
   */
  fun cancelNotification(modelId: String)

  /**
   * Cancel all download notifications.
   */
  fun cancelAllNotifications()

  /**
   * Create the notification channel (should be called on app startup).
   */
  fun createNotificationChannel()
}
