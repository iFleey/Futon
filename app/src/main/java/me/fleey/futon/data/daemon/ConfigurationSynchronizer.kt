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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.fleey.futon.FutonConfig
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.DspPerceptionSettings
import org.koin.core.annotation.Single
import java.io.Closeable

/**
 * Synchronizes app settings with daemon configuration.
 */
interface ConfigurationSynchronizer : Closeable {
  /**
   * Current synchronization state.
   */
  val syncState: StateFlow<SyncState>

  fun start()

  /**
   * Force immediate sync of current settings.
   */
  suspend fun forceSync(): Result<Unit>

  fun stop()

  /**
   * Enable or disable the debug stream on the daemon.
   * Only available in debug builds.
   *
   * @param enabled Whether to enable the debug stream
   * @param port The port for the debug stream WebSocket server (default: 33212)
   * @return Result indicating success or failure
   */
  suspend fun setDebugStreamEnabled(enabled: Boolean, port: Int = 33212): Result<Unit>
}

/**
 * Synchronization state.
 */
sealed interface SyncState {
  data object Idle : SyncState
  data object Syncing : SyncState
  data class Synced(val timestamp: Long) : SyncState
  data class Error(val message: String, val retryCount: Int) : SyncState
}

@Single(binds = [ConfigurationSynchronizer::class])
@OptIn(FlowPreview::class)
class ConfigurationSynchronizerImpl(
  private val settingsRepository: SettingsRepository,
  private val daemonRepository: DaemonRepository,
) : ConfigurationSynchronizer {

  companion object {
    private const val TAG = "ConfigSync"
    private const val DEBOUNCE_MS = 200L
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L

    private const val DEFAULT_CAPTURE_WIDTH = 1080
    private const val DEFAULT_CAPTURE_HEIGHT = 2400
    private const val DEFAULT_DEBUG_PORT = 33212
    private const val DEFAULT_STATUS_INTERVAL_MS = 100
    private const val DEFAULT_HOT_PATH_NO_MATCH_THRESHOLD = 5
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
  override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

  private var isStarted = false

  override fun start() {
    if (isStarted) return
    isStarted = true

    Log.i(TAG, "Starting configuration synchronizer")

    observeSettingsChanges()
    observeDaemonConnection()
  }

  private fun observeSettingsChanges() {
    settingsRepository.getDspPerceptionSettingsFlow()
      .debounce(DEBOUNCE_MS)
      .distinctUntilChanged()
      .onEach { settings ->
        if (daemonRepository.isConnected()) {
          syncSettings(settings)
        }
      }
      .launchIn(scope)
  }

  private fun observeDaemonConnection() {
    daemonRepository.daemonState
      .onEach { state ->
        if (state is DaemonState.Ready) {
          Log.i(TAG, "Daemon connected, syncing all settings")
          forceSync()
        }
      }
      .launchIn(scope)

    // Also check current state immediately in case daemon is already connected
    scope.launch {
      val currentState = daemonRepository.daemonState.value
      if (currentState is DaemonState.Ready) {
        Log.i(TAG, "Daemon already connected at start, syncing all settings")
        forceSync()
      }
    }
  }

  private suspend fun syncSettings(settings: DspPerceptionSettings) {
    val validationResult = validateSettings(settings)
    if (validationResult != null) {
      Log.w(TAG, "Settings validation failed: $validationResult")
      _syncState.value = SyncState.Error(validationResult, 0)
      return
    }

    val config = mapToFutonConfig(settings)
    sendConfigWithRetry(config)
  }

  override suspend fun forceSync(): Result<Unit> {
    if (!daemonRepository.isConnected()) {
      return Result.failure(IllegalStateException("Daemon not connected"))
    }

    val settings = settingsRepository.getDspPerceptionSettings()
    val validationResult = validateSettings(settings)
    if (validationResult != null) {
      return Result.failure(IllegalArgumentException(validationResult))
    }

    val config = mapToFutonConfig(settings)
    return sendConfigWithRetry(config)
  }

  private suspend fun sendConfigWithRetry(config: FutonConfig): Result<Unit> {
    _syncState.value = SyncState.Syncing

    var lastError: Throwable? = null

    for (attempt in 0 until MAX_RETRIES) {
      try {
        val result = daemonRepository.configure(config)
        if (result.isSuccess) {
          _syncState.value = SyncState.Synced(System.currentTimeMillis())
          Log.i(TAG, "Configuration synced successfully")
          return Result.success(Unit)
        } else {
          lastError = result.exceptionOrNull()
          Log.w(
            TAG,
            "Configure failed (attempt ${attempt + 1}/$MAX_RETRIES): ${lastError?.message}",
          )
        }
      } catch (e: Exception) {
        lastError = e
        Log.w(TAG, "Configure exception (attempt ${attempt + 1}/$MAX_RETRIES)", e)
      }

      if (attempt < MAX_RETRIES - 1) {
        _syncState.value = SyncState.Error(
          lastError?.message ?: "Unknown error",
          attempt + 1,
        )
        delay(RETRY_DELAY_MS * (attempt + 1))
      }
    }

    val errorMessage = lastError?.message ?: "Configuration sync failed after $MAX_RETRIES attempts"
    _syncState.value = SyncState.Error(errorMessage, MAX_RETRIES)
    return Result.failure(lastError ?: Exception(errorMessage))
  }

  /**
   * Validate settings before sending to daemon.
   * @return Error message if invalid, null if valid
   */
  private fun validateSettings(settings: DspPerceptionSettings): String? {
    val targetFps = (1000 / settings.targetLatencyMs).toInt()

    if (targetFps <= 0) {
      return "Invalid target FPS: $targetFps (derived from latency ${settings.targetLatencyMs}ms)"
    }

    if (targetFps > 60) {
      return "Target FPS too high: $targetFps (max 60)"
    }

    if (settings.minConfidence !in 0f..1f) {
      return "Invalid confidence threshold: ${settings.minConfidence} (must be 0.0-1.0)"
    }

    if (settings.maxConcurrentBuffers !in 1..10) {
      return "Invalid buffer pool size: ${settings.maxConcurrentBuffers} (must be 1-10)"
    }

    if (settings.downscaleFactor !in 1..4) {
      return "Invalid downscale factor: ${settings.downscaleFactor} (must be 1-4)"
    }

    return null
  }

  /**
   * Map app settings to daemon FutonConfig.
   * In debug builds, debug stream is always enabled.
   */
  private fun mapToFutonConfig(settings: DspPerceptionSettings): FutonConfig {
    val targetFps = (1000 / settings.targetLatencyMs).toInt().coerceIn(1, 60)

    val isDebugBuild = me.fleey.futon.BuildConfig.DEBUG

    return FutonConfig().apply {
      captureWidth = DEFAULT_CAPTURE_WIDTH / settings.downscaleFactor
      captureHeight = DEFAULT_CAPTURE_HEIGHT / settings.downscaleFactor
      this.targetFps = targetFps
      modelPath = "${DaemonConfig.MODELS_DIR}/detection.tflite"
      ocrDetModelPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/ocr_det_fp16.tflite" else ""
      ocrRecModelPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/ocr_rec_fp16.tflite" else ""
      ocrKeysPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/keys_v5.txt" else ""
      minConfidence = settings.minConfidence
      enableDebugStream = isDebugBuild
      debugStreamPort = DEFAULT_DEBUG_PORT
      statusUpdateIntervalMs = DEFAULT_STATUS_INTERVAL_MS
      bufferPoolSize = settings.maxConcurrentBuffers
      hotPathNoMatchThreshold = DEFAULT_HOT_PATH_NO_MATCH_THRESHOLD
      touchDevicePath = settings.touchDevicePath
    }
  }

  override suspend fun setDebugStreamEnabled(enabled: Boolean, port: Int): Result<Unit> {
    if (!daemonRepository.isConnected()) {
      return Result.failure(IllegalStateException("Daemon not connected"))
    }

    Log.i(TAG, "Setting debug stream enabled=$enabled, port=$port")

    val settings = settingsRepository.getDspPerceptionSettings()
    val config = FutonConfig().apply {
      val targetFps = (1000 / settings.targetLatencyMs).toInt().coerceIn(1, 60)
      captureWidth = DEFAULT_CAPTURE_WIDTH / settings.downscaleFactor
      captureHeight = DEFAULT_CAPTURE_HEIGHT / settings.downscaleFactor
      this.targetFps = targetFps
      modelPath = "${DaemonConfig.MODELS_DIR}/detection.tflite"
      ocrDetModelPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/ocr_det_fp16.tflite" else ""
      ocrRecModelPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/ocr_rec_fp16.tflite" else ""
      ocrKeysPath = if (settings.enableOcr) "${DaemonConfig.MODELS_DIR}/keys_v5.txt" else ""
      minConfidence = settings.minConfidence
      enableDebugStream = enabled
      debugStreamPort = port
      statusUpdateIntervalMs = DEFAULT_STATUS_INTERVAL_MS
      bufferPoolSize = settings.maxConcurrentBuffers
      hotPathNoMatchThreshold = DEFAULT_HOT_PATH_NO_MATCH_THRESHOLD
      touchDevicePath = settings.touchDevicePath
    }
    return sendConfigWithRetry(config)
  }

  override fun stop() {
    isStarted = false
    Log.i(TAG, "Configuration synchronizer stopped")
  }

  override fun close() {
    stop()
    scope.cancel()
  }
}
