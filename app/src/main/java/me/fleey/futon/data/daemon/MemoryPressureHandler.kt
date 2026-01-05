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
package me.fleey.futon.data.daemon

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.annotation.Single
import java.io.Closeable

sealed interface MemoryPressureLevel {
  val updateIntervalMultiplier: Float
  val shouldReleaseScreenshots: Boolean
  val shouldShowUserWarning: Boolean

  data object Normal : MemoryPressureLevel {
    override val updateIntervalMultiplier = 1.0f
    override val shouldReleaseScreenshots = false
    override val shouldShowUserWarning = false
  }

  data object Low : MemoryPressureLevel {
    override val updateIntervalMultiplier = 1.5f
    override val shouldReleaseScreenshots = true
    override val shouldShowUserWarning = false
  }

  data object Medium : MemoryPressureLevel {
    override val updateIntervalMultiplier = 2.0f
    override val shouldReleaseScreenshots = true
    override val shouldShowUserWarning = false
  }

  data object Critical : MemoryPressureLevel {
    override val updateIntervalMultiplier = 3.0f
    override val shouldReleaseScreenshots = true
    override val shouldShowUserWarning = true
  }
}

data class MemoryPressureState(
  val level: MemoryPressureLevel = MemoryPressureLevel.Normal,
  val lastEventTimestamp: Long? = null,
  val consecutiveEvents: Int = 0,
)

interface MemoryPressureHandler {
  val pressureState: StateFlow<MemoryPressureState>
  val userWarning: StateFlow<UserMemoryWarning?>

  fun dismissWarning()
  fun addScreenshotReleaseCallback(callback: ScreenshotReleaseCallback)
  fun removeScreenshotReleaseCallback(callback: ScreenshotReleaseCallback)
  fun addUpdateFrequencyCallback(callback: UpdateFrequencyCallback)
  fun removeUpdateFrequencyCallback(callback: UpdateFrequencyCallback)
}

data class UserMemoryWarning(
  val message: String,
  val timestamp: Long = System.currentTimeMillis(),
)

fun interface ScreenshotReleaseCallback {
  fun onReleaseScreenshots()
}

fun interface UpdateFrequencyCallback {
  fun onUpdateFrequencyChanged(multiplier: Float)
}

@Single(binds = [MemoryPressureHandler::class])
class MemoryPressureHandlerImpl(
  private val callbackBridge: CallbackBridgeProvider,
) : MemoryPressureHandler, Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _pressureState = MutableStateFlow(MemoryPressureState())
  override val pressureState: StateFlow<MemoryPressureState> = _pressureState.asStateFlow()

  private val _userWarning = MutableStateFlow<UserMemoryWarning?>(null)
  override val userWarning: StateFlow<UserMemoryWarning?> = _userWarning.asStateFlow()

  private val screenshotReleaseCallbacks = mutableSetOf<ScreenshotReleaseCallback>()
  private val updateFrequencyCallbacks = mutableSetOf<UpdateFrequencyCallback>()

  private val callbackLock = Any()

  init {
    observeMemoryPressureEvents()
  }

  private fun observeMemoryPressureEvents() {
    callbackBridge.memoryPressureEvents
      .onEach { event ->
        handleMemoryPressureEvent(event)
      }
      .launchIn(scope)
  }

  private fun handleMemoryPressureEvent(event: MemoryPressureEvent) {
    val newLevel = when {
      event.isCritical -> MemoryPressureLevel.Critical
      event.isMedium -> MemoryPressureLevel.Medium
      event.isLow -> MemoryPressureLevel.Low
      else -> MemoryPressureLevel.Normal
    }

    val currentState = _pressureState.value
    val consecutiveEvents = if (newLevel == currentState.level) {
      currentState.consecutiveEvents + 1
    } else {
      1
    }

    val newState = MemoryPressureState(
      level = newLevel,
      lastEventTimestamp = event.timestamp,
      consecutiveEvents = consecutiveEvents,
    )

    _pressureState.value = newState

    Log.d(TAG, "Memory pressure level changed: $newLevel (consecutive: $consecutiveEvents)")

    if (newLevel.shouldReleaseScreenshots) {
      notifyScreenshotRelease()
    }

    notifyUpdateFrequencyChange(newLevel.updateIntervalMultiplier)

    if (newLevel.shouldShowUserWarning && consecutiveEvents >= CONSECUTIVE_EVENTS_FOR_WARNING) {
      showUserWarning()
    }
  }

  private fun notifyScreenshotRelease() {
    val callbacks = synchronized(callbackLock) {
      screenshotReleaseCallbacks.toList()
    }

    callbacks.forEach { callback ->
      try {
        callback.onReleaseScreenshots()
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying screenshot release callback", e)
      }
    }

    Log.d(TAG, "Notified ${callbacks.size} callbacks to release screenshots")
  }

  private fun notifyUpdateFrequencyChange(multiplier: Float) {
    val callbacks = synchronized(callbackLock) {
      updateFrequencyCallbacks.toList()
    }

    callbacks.forEach { callback ->
      try {
        callback.onUpdateFrequencyChanged(multiplier)
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying update frequency callback", e)
      }
    }

    Log.d(TAG, "Notified ${callbacks.size} callbacks of frequency change: ${multiplier}x")
  }

  private fun showUserWarning() {
    val warning = UserMemoryWarning(
      message = "Device is running low on memory. Consider closing other apps for better performance.",
    )
    _userWarning.value = warning
    Log.w(TAG, "Showing user memory warning")
  }

  override fun dismissWarning() {
    _userWarning.value = null
  }

  override fun addScreenshotReleaseCallback(callback: ScreenshotReleaseCallback) {
    synchronized(callbackLock) {
      screenshotReleaseCallbacks.add(callback)
    }
  }

  override fun removeScreenshotReleaseCallback(callback: ScreenshotReleaseCallback) {
    synchronized(callbackLock) {
      screenshotReleaseCallbacks.remove(callback)
    }
  }

  override fun addUpdateFrequencyCallback(callback: UpdateFrequencyCallback) {
    synchronized(callbackLock) {
      updateFrequencyCallbacks.add(callback)
    }
  }

  override fun removeUpdateFrequencyCallback(callback: UpdateFrequencyCallback) {
    synchronized(callbackLock) {
      updateFrequencyCallbacks.remove(callback)
    }
  }

  override fun close() {
    synchronized(callbackLock) {
      screenshotReleaseCallbacks.clear()
      updateFrequencyCallbacks.clear()
    }
    scope.cancel()
    Log.d(TAG, "MemoryPressureHandler closed")
  }

  companion object {
    private const val TAG = "MemoryPressureHandler"
    private const val CONSECUTIVE_EVENTS_FOR_WARNING = 2
  }
}
