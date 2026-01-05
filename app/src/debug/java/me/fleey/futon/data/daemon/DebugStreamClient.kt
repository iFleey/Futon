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
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.fleey.futon.data.daemon.models.DebugFrame
import me.fleey.futon.data.daemon.models.DebugStreamState
import java.io.Closeable
import kotlin.math.min
import kotlin.math.pow

/**
 * WebSocket client for connecting to the daemon's debug stream.
 * Only available in debug builds.
 *
 * The debug stream provides real-time detection results and performance metrics
 * from the daemon's perception pipeline.
 *
 * NOTE: The debug stream must be enabled on the daemon side via FutonConfig.enableDebugStream.
 * This client will attempt to connect but will fail if the daemon's WebSocket server is not running.
 */
class DebugStreamClient(
  private val daemonRepository: DaemonRepository? = null,
) : Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var connectionJob: Job? = null

  private val _debugStreamState = MutableStateFlow<DebugStreamState>(DebugStreamState.Disconnected)
  val debugStreamState: StateFlow<DebugStreamState> = _debugStreamState.asStateFlow()

  private val _debugFrames = MutableSharedFlow<DebugFrame>(
    replay = 1,
    extraBufferCapacity = 64,
  )
  val debugFrames: SharedFlow<DebugFrame> = _debugFrames.asSharedFlow()

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  private val httpClient by lazy {
    HttpClient(OkHttp) {
      install(WebSockets) {
        pingIntervalMillis = PING_INTERVAL_MS
      }
    }
  }

  private var currentPort: Int = DEFAULT_DEBUG_PORT
  private var isEnabled: Boolean = false

  /**
   * Enable the debug stream and connect to the daemon.
   * NOTE: This will fail with ECONNREFUSED if the daemon's debug stream is not enabled.
   * The daemon must be configured with enableDebugStream=true via FutonConfig.
   *
   * @param port The WebSocket port to connect to (default: 33212)
   */
  fun enable(port: Int = DEFAULT_DEBUG_PORT) {
    if (isEnabled && currentPort == port) return

    currentPort = port
    isEnabled = true

    // Update state to show we're attempting to connect
    _debugStreamState.value = DebugStreamState.Connecting(1, MAX_RETRY_ATTEMPTS)

    startConnection()
  }

  fun disable() {
    if (!isEnabled) return

    isEnabled = false
    connectionJob?.cancel()
    connectionJob = null
    _debugStreamState.value = DebugStreamState.Disconnected
  }

  fun isEnabled(): Boolean = isEnabled

  private fun startConnection() {
    connectionJob?.cancel()
    connectionJob = scope.launch {
      connectWithRetry()
    }
  }

  private suspend fun connectWithRetry() {
    var attempt = 0

    while (isEnabled && attempt < MAX_RETRY_ATTEMPTS) {
      attempt++
      _debugStreamState.value = DebugStreamState.Connecting(attempt, MAX_RETRY_ATTEMPTS)

      try {
        connect()
        attempt = 0
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Debug stream connection failed (attempt $attempt/$MAX_RETRY_ATTEMPTS)", e)

        if (attempt >= MAX_RETRY_ATTEMPTS) {
          val isConnectionRefused = e.message?.contains("ECONNREFUSED") == true ||
            e.cause?.message?.contains("ECONNREFUSED") == true

          val errorMessage = if (isConnectionRefused) {
            "Debug stream not enabled on daemon. Enable via FutonConfig.enableDebugStream=true"
          } else {
            "Failed to connect after $MAX_RETRY_ATTEMPTS attempts: ${e.message}"
          }

          _debugStreamState.value = DebugStreamState.Error(
            message = errorMessage,
            cause = e,
            retriesExhausted = true,
          )
          return
        }

        val backoffMs = calculateBackoff(attempt)
        Log.d(TAG, "Retrying in ${backoffMs}ms...")
        delay(backoffMs)
      }
    }
  }

  private suspend fun connect() {
    val url = "ws://localhost:$currentPort"
    Log.d(TAG, "Connecting to debug stream at $url")

    httpClient.webSocket(urlString = url) {
      _debugStreamState.value = DebugStreamState.Connected(currentPort)
      Log.i(TAG, "Connected to debug stream")

      try {
        for (frame in incoming) {
          if (!isEnabled) break

          when (frame) {
            is Frame.Text -> {
              processFrame(frame.readText())
            }

            is Frame.Close -> {
              Log.d(TAG, "Debug stream closed by server")
              break
            }

            else -> {
              // Ignore other frame types (Binary, Ping, Pong)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Error reading from debug stream", e)
        throw e
      }
    }

    if (isEnabled) {
      _debugStreamState.value = DebugStreamState.Disconnected
    }
  }

  private suspend fun processFrame(text: String) {
    try {
      val frame = json.decodeFromString<DebugFrame>(text)
      _debugFrames.emit(frame)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse debug frame: ${e.message}")
    }
  }

  private fun calculateBackoff(attempt: Int): Long {
    val baseDelayMs = INITIAL_BACKOFF_MS * 2.0.pow(attempt - 1).toLong()
    return min(baseDelayMs, MAX_BACKOFF_MS)
  }

  override fun close() {
    disable()
    scope.cancel()
    httpClient.close()
  }

  companion object {
    private const val TAG = "DebugStreamClient"
    private const val DEFAULT_DEBUG_PORT = 33212
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val INITIAL_BACKOFF_MS = 100L
    private const val MAX_BACKOFF_MS = 400L
    private const val PING_INTERVAL_MS = 30_000L
  }
}
