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
package me.fleey.futon.service.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Callback interface for idle timeout events.
 */
interface IdleTimeoutListener {
  /** Called when idle timeout is about to expire (warning) */
  fun onIdleTimeoutWarning(remainingTime: Duration)

  /** Called when idle timeout has expired */
  fun onIdleTimeoutExpired()
}

/**
 * Manages idle timeout for the LAN HTTP server.
 * Automatically shuts down the server after a period of inactivity.
 */
interface IdleTimeoutManager {
  /** Time remaining until idle timeout (null if disabled) */
  val idleTimeRemaining: StateFlow<Duration?>

  /** Whether idle timeout is enabled */
  val isIdleTimeoutEnabled: StateFlow<Boolean>

  /** Register a listener for timeout events */
  fun addListener(listener: IdleTimeoutListener)

  fun removeListener(listener: IdleTimeoutListener)

  /** Record activity (resets the timer) */
  fun recordActivity()

  fun start()

  fun stop()

  fun reset()
}

@Single(binds = [IdleTimeoutManager::class])
class IdleTimeoutManagerImpl(
  private val gatewayConfig: GatewayConfig,
) : IdleTimeoutManager {

  companion object {
    private const val TAG = "IdleTimeoutManager"
    private val UPDATE_INTERVAL = 1.seconds
    private val WARNING_THRESHOLD = 5.minutes
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val listeners = mutableSetOf<IdleTimeoutListener>()

  private val _idleTimeRemaining = MutableStateFlow<Duration?>(null)
  override val idleTimeRemaining: StateFlow<Duration?> = _idleTimeRemaining.asStateFlow()

  private val _isIdleTimeoutEnabled = MutableStateFlow(false)
  override val isIdleTimeoutEnabled: StateFlow<Boolean> = _isIdleTimeoutEnabled.asStateFlow()

  private var timerJob: Job? = null
  private var lastActivityTime: Long = 0
  private var timeoutDuration: Duration = 4.hours
  private var warningFired = false

  init {
    scope.launch {
      gatewayConfig.config.collect { config ->
        val hours = config.idleTimeoutHours
        _isIdleTimeoutEnabled.value = hours > 0
        timeoutDuration = hours.hours

        if (hours == 0) {
          _idleTimeRemaining.value = null
        }
      }
    }
  }

  override fun addListener(listener: IdleTimeoutListener) {
    listeners.add(listener)
  }

  override fun removeListener(listener: IdleTimeoutListener) {
    listeners.remove(listener)
  }

  override fun recordActivity() {
    lastActivityTime = System.currentTimeMillis()
    warningFired = false
    Log.d(TAG, "Activity recorded, timer reset")
  }

  override fun start() {
    if (!_isIdleTimeoutEnabled.value) {
      Log.d(TAG, "Idle timeout disabled, not starting timer")
      return
    }

    stop()
    lastActivityTime = System.currentTimeMillis()
    warningFired = false

    timerJob = scope.launch {
      Log.i(TAG, "Idle timeout timer started (${timeoutDuration})")

      while (true) {
        delay(UPDATE_INTERVAL)

        if (!_isIdleTimeoutEnabled.value) {
          _idleTimeRemaining.value = null
          continue
        }

        val elapsed = System.currentTimeMillis() - lastActivityTime
        val remaining = timeoutDuration - elapsed.milliseconds

        if (remaining <= Duration.ZERO) {
          Log.i(TAG, "Idle timeout expired")
          _idleTimeRemaining.value = Duration.ZERO
          notifyExpired()
          break
        }

        _idleTimeRemaining.value = remaining

        // Fire warning when approaching timeout
        if (!warningFired && remaining <= WARNING_THRESHOLD) {
          warningFired = true
          notifyWarning(remaining)
        }
      }
    }
  }

  override fun stop() {
    timerJob?.cancel()
    timerJob = null
    _idleTimeRemaining.value = null
    Log.d(TAG, "Idle timeout timer stopped")
  }

  override fun reset() {
    recordActivity()
    if (_isIdleTimeoutEnabled.value) {
      _idleTimeRemaining.value = timeoutDuration
    }
  }

  private fun notifyWarning(remaining: Duration) {
    Log.w(TAG, "Idle timeout warning: $remaining remaining")
    listeners.forEach { listener ->
      try {
        listener.onIdleTimeoutWarning(remaining)
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying idle timeout warning", e)
      }
    }
  }

  private fun notifyExpired() {
    listeners.forEach { listener ->
      try {
        listener.onIdleTimeoutExpired()
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying idle timeout expired", e)
      }
    }
  }

  private val Long.milliseconds: Duration
    get() = Duration.parse("${this}ms")
}
