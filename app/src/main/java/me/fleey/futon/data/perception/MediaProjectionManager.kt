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
package me.fleey.futon.data.perception

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single
import java.lang.ref.WeakReference

/**
 * Manages MediaProjection lifecycle for zero-copy screen capture.
 */
@Single
class MediaProjectionHolder(private val context: Context) {

  companion object {
    private const val TAG = "MediaProjectionHolder"
    const val REQUEST_CODE_MEDIA_PROJECTION = 1001
  }

  private val systemManager: MediaProjectionManager =
    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

  private var mediaProjection: MediaProjection? = null
  private var pendingActivityRef: WeakReference<Activity>? = null

  private val _state = MutableStateFlow(MediaProjectionState.NOT_STARTED)
  val state: StateFlow<MediaProjectionState> = _state.asStateFlow()

  private val _isActive = MutableStateFlow(false)
  val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

  /**
   * Request MediaProjection permission.
   * Must be called from an Activity.
   *
   * @param activity The activity to launch the permission request from
   */
  fun requestPermission(activity: Activity) {
    Log.i(TAG, "Requesting MediaProjection permission")
    pendingActivityRef = WeakReference(activity)
    _state.value = MediaProjectionState.REQUESTING_PERMISSION

    val intent = systemManager.createScreenCaptureIntent()
    activity.startActivityForResult(intent, REQUEST_CODE_MEDIA_PROJECTION)
  }

  /**
   * Handle the result from the permission request.
   * Must be called from Activity.onActivityResult().
   *
   * @param requestCode The request code from onActivityResult
   * @param resultCode The result code from onActivityResult
   * @param data The intent data from onActivityResult
   * @return true if this was a MediaProjection result and was handled
   */
  fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != REQUEST_CODE_MEDIA_PROJECTION) {
      return false
    }

    pendingActivityRef?.clear()
    pendingActivityRef = null

    if (resultCode != Activity.RESULT_OK || data == null) {
      Log.w(TAG, "MediaProjection permission denied")
      _state.value = MediaProjectionState.PERMISSION_DENIED
      return true
    }

    try {
      mediaProjection = systemManager.getMediaProjection(resultCode, data)

      mediaProjection?.registerCallback(
        object : MediaProjection.Callback() {
          override fun onStop() {
            Log.i(TAG, "MediaProjection stopped")
            _isActive.value = false
            _state.value = MediaProjectionState.STOPPED
            mediaProjection = null
          }
        },
        null,
      )

      _isActive.value = true
      _state.value = MediaProjectionState.ACTIVE
      Log.i(TAG, "MediaProjection started successfully")

    } catch (e: Exception) {
      Log.e(TAG, "Failed to get MediaProjection", e)
      _state.value = MediaProjectionState.ERROR
    }

    return true
  }

  /**
   * Get the current MediaProjection instance.
   *
   * @return MediaProjection or null if not active
   */
  fun getMediaProjection(): MediaProjection? = mediaProjection

  /**
   * Stop the MediaProjection.
   */
  fun stop() {
    Log.i(TAG, "Stopping MediaProjection")
    mediaProjection?.stop()
    mediaProjection = null
    _isActive.value = false
    _state.value = MediaProjectionState.STOPPED
  }

  /**
   * Check if MediaProjection is currently active.
   */
  fun isProjectionActive(): Boolean = mediaProjection != null && _isActive.value
}

/**
 * State of the MediaProjection.
 */
enum class MediaProjectionState {
  NOT_STARTED,
  REQUESTING_PERMISSION,
  PERMISSION_DENIED,
  ACTIVE,
  STOPPED,
  ERROR
}
