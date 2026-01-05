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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.fleey.futon.MainActivity
import org.koin.core.annotation.Single

/**
 * Implementation of [DownloadNotificationManager] using Android's NotificationManager.
 * *
 * @param context Application context for creating notifications
 */
@Single(binds = [DownloadNotificationManager::class])
class DownloadNotificationManagerImpl(
  private val context: Context,
) : DownloadNotificationManager {

  companion object {
    const val CHANNEL_ID = "futon_model_download"

    private const val CHANNEL_NAME = "Model Downloads"

    private const val CHANNEL_DESCRIPTION = "Notifications for AI model download progress"

    /** Base notification ID - actual ID is computed from modelId hash */
    private const val NOTIFICATION_ID_BASE = 10000

    const val ACTION_PAUSE = "me.fleey.futon.DOWNLOAD_PAUSE"

    const val ACTION_RESUME = "me.fleey.futon.DOWNLOAD_RESUME"

    const val ACTION_CANCEL = "me.fleey.futon.DOWNLOAD_CANCEL"

    const val ACTION_RETRY = "me.fleey.futon.DOWNLOAD_RETRY"

    /** Extra key for model ID in intents */
    const val EXTRA_MODEL_ID = "model_id"
  }

  private val notificationManager: NotificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  /** Track last update time per model to throttle updates */
  private val lastUpdateTime = mutableMapOf<String, Long>()

  /** Minimum interval between notification updates (5 seconds) */
  private val updateIntervalMs = 5000L


  override fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      CHANNEL_NAME,
      NotificationManager.IMPORTANCE_LOW, // Low importance to avoid sound/vibration
    ).apply {
      description = CHANNEL_DESCRIPTION
      setShowBadge(false)
    }
    notificationManager.createNotificationChannel(channel)
  }

  override fun showDownloadProgress(
    modelId: String,
    modelName: String,
    state: DownloadState.Downloading,
  ) {
    val now = System.currentTimeMillis()
    val lastUpdate = lastUpdateTime[modelId] ?: 0L
    if (now - lastUpdate < updateIntervalMs && state.progress < 1f) {
      return
    }
    lastUpdateTime[modelId] = now

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Downloading $modelName")
      .setContentText("${state.downloadedFormatted} / ${state.totalFormatted}")
      .setProgress(100, state.progressPercent, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(createMainActivityIntent())
      .addAction(createPauseAction(modelId))
      .addAction(createCancelAction(modelId))
      .build()

    notificationManager.notify(getNotificationId(modelId), notification)
  }

  override fun showDownloadPaused(
    modelId: String,
    modelName: String,
    state: DownloadState.Paused,
  ) {
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("Download paused: $modelName")
      .setContentText("${state.progressPercent}% complete - Tap to resume")
      .setProgress(100, state.progressPercent, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(createMainActivityIntent())
      .addAction(createResumeAction(modelId))
      .addAction(createCancelAction(modelId))
      .build()

    notificationManager.notify(getNotificationId(modelId), notification)
  }

  override fun showDownloadComplete(
    modelId: String,
    modelName: String,
  ) {
    // Clear throttle tracking
    lastUpdateTime.remove(modelId)

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("Download complete")
      .setContentText("$modelName is ready to use")
      .setAutoCancel(true)
      .setContentIntent(createMainActivityIntent())
      .build()

    notificationManager.notify(getNotificationId(modelId), notification)
  }

  override fun showDownloadFailed(
    modelId: String,
    modelName: String,
    error: DownloadError,
  ) {
    // Clear throttle tracking
    lastUpdateTime.remove(modelId)

    val errorMessage = when (error) {
      is DownloadError.NetworkError -> "Network error: ${error.message}"
      is DownloadError.StorageError -> "Storage error: ${error.message}"
      is DownloadError.ServerError -> "Server error (${error.statusCode})"
      is DownloadError.InvalidFileError -> "Invalid file: ${error.message}"
      is DownloadError.CancelledByUser -> "Download cancelled"
      is DownloadError.UnknownError -> "Error: ${error.message}"
    }

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_error)
      .setContentTitle("Download failed: $modelName")
      .setContentText(errorMessage)
      .setAutoCancel(true)
      .setContentIntent(createMainActivityIntent())

    if (error.isRetryable) {
      builder.addAction(createRetryAction(modelId))
    }

    notificationManager.notify(getNotificationId(modelId), builder.build())
  }

  override fun cancelNotification(modelId: String) {
    lastUpdateTime.remove(modelId)
    notificationManager.cancel(getNotificationId(modelId))
  }

  override fun cancelAllNotifications() {
    lastUpdateTime.clear()
    // Cancel all notifications in our ID range
    // This is a simple approach - in production might needs track active IDs
    notificationManager.cancelAll()
  }


  /**
   * Get a unique notification ID for a model.
   */
  private fun getNotificationId(modelId: String): Int {
    return NOTIFICATION_ID_BASE + modelId.hashCode().and(0xFFFF)
  }

  /**
   * Create a PendingIntent to open the main activity.
   */
  private fun createMainActivityIntent(): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /**
   * Create a pause action for the notification.

   */
  private fun createPauseAction(modelId: String): NotificationCompat.Action {
    val intent = Intent(ACTION_PAUSE).apply {
      setPackage(context.packageName)
      putExtra(EXTRA_MODEL_ID, modelId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      modelId.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Action.Builder(
      android.R.drawable.ic_media_pause,
      "Pause",
      pendingIntent,
    ).build()
  }

  /**
   * Create a resume action for the notification.
   */
  private fun createResumeAction(modelId: String): NotificationCompat.Action {
    val intent = Intent(ACTION_RESUME).apply {
      setPackage(context.packageName)
      putExtra(EXTRA_MODEL_ID, modelId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      modelId.hashCode() + 1,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Action.Builder(
      android.R.drawable.ic_media_play,
      "Resume",
      pendingIntent,
    ).build()
  }

  /**
   * Create a cancel action for the notification.
   */
  private fun createCancelAction(modelId: String): NotificationCompat.Action {
    val intent = Intent(ACTION_CANCEL).apply {
      setPackage(context.packageName)
      putExtra(EXTRA_MODEL_ID, modelId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      modelId.hashCode() + 2,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Action.Builder(
      android.R.drawable.ic_delete,
      "Cancel",
      pendingIntent,
    ).build()
  }

  /**
   * Create a retry action for the notification.
   */
  private fun createRetryAction(modelId: String): NotificationCompat.Action {
    val intent = Intent(ACTION_RETRY).apply {
      setPackage(context.packageName)
      putExtra(EXTRA_MODEL_ID, modelId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      modelId.hashCode() + 3,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Action.Builder(
      android.R.drawable.ic_popup_sync,
      "Retry",
      pendingIntent,
    ).build()
  }
}
