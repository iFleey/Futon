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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import me.fleey.futon.DetectionResult
import me.fleey.futon.FutonConfig
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.DaemonPerceptionResult
import me.fleey.futon.data.daemon.models.DaemonPerceptionState
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.data.perception.models.PerceptionConfig
import me.fleey.futon.domain.perception.models.UIBounds
import org.koin.core.annotation.Single

/**
 * Implementation of DaemonPerceptionProxy that delegates all perception to the daemon.
 */
@Single(binds = [DaemonPerceptionProxy::class])
class DaemonPerceptionProxyImpl(
  private val daemonRepository: DaemonRepository,
) : DaemonPerceptionProxy {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _daemonPerceptionState = MutableStateFlow<DaemonPerceptionState>(
    DaemonPerceptionState.Unavailable,
  )
  override val daemonPerceptionState: StateFlow<DaemonPerceptionState> =
    _daemonPerceptionState.asStateFlow()

  private var lastImageWidth: Int = 0
  private var lastImageHeight: Int = 0

  init {
    observeDaemonState()
  }

  private fun observeDaemonState() {
    daemonRepository.daemonState
      .onEach { state ->
        _daemonPerceptionState.value = when (state) {
          is DaemonState.Ready -> DaemonPerceptionState.Ready
          is DaemonState.Error -> DaemonPerceptionState.Error(
            message = state.message,
            code = state.code,
          )

          else -> DaemonPerceptionState.Unavailable
        }
      }
      .launchIn(scope)
  }

  override suspend fun perceive(): DaemonPerceptionResult = withContext(Dispatchers.IO) {
    if (!isDaemonAvailable()) {
      return@withContext DaemonPerceptionResult.DaemonUnavailable
    }

    _daemonPerceptionState.value = DaemonPerceptionState.Processing

    try {
      val startTime = System.nanoTime()

      val result = daemonRepository.requestPerception()

      result.fold(
        onSuccess = { elements ->
          val endTime = System.nanoTime()
          val totalLatencyMs = (endTime - startTime) / 1_000_000L

          _daemonPerceptionState.value = DaemonPerceptionState.Ready

          DaemonPerceptionResult.Success(
            elements = elements,
            captureLatencyMs = 0,
            inferenceLatencyMs = totalLatencyMs,
            totalLatencyMs = totalLatencyMs,
            activeDelegate = DelegateType.NONE,
            timestamp = System.currentTimeMillis(),
            imageWidth = lastImageWidth,
            imageHeight = lastImageHeight,
          )
        },
        onFailure = { throwable ->
          _daemonPerceptionState.value = DaemonPerceptionState.Ready

          val error = when (throwable) {
            is DaemonBinderException -> throwable.error
            else -> DaemonError.runtime(
              ErrorCode.PERCEPTION_FAILED,
              "Perception failed: ${throwable.message}",
              throwable,
            )
          }

          DaemonPerceptionResult.Failure(
            error = error,
            message = error.message,
          )
        },
      )
    } catch (e: Exception) {
      _daemonPerceptionState.value = DaemonPerceptionState.Ready

      val error = DaemonError.runtime(
        ErrorCode.PERCEPTION_FAILED,
        "Perception failed: ${e.message}",
        e,
      )

      DaemonPerceptionResult.Failure(
        error = error,
        message = error.message,
      )
    }
  }

  override suspend fun configure(config: PerceptionConfig): Result<Unit> =
    withContext(Dispatchers.IO) {
      if (!isDaemonAvailable()) {
        return@withContext Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.SERVICE_NOT_FOUND,
              "Daemon not available for configuration",
            ),
          ),
        )
      }

      val futonConfig = mapToFutonConfig(config)
      daemonRepository.configure(futonConfig)
    }

  override fun isDaemonAvailable(): Boolean {
    return daemonRepository.isConnected() &&
      daemonRepository.daemonState.value is DaemonState.Ready
  }

  override fun close() {
    // No local resources to clean up - all inference is on daemon
  }

  private fun mapToFutonConfig(config: PerceptionConfig): FutonConfig {
    return FutonConfig().apply {
      minConfidence = config.minConfidence
      bufferPoolSize = config.maxConcurrentBuffers
      targetFps = (1000 / config.targetLatencyMs).toInt().coerceIn(1, 60)
    }
  }

  companion object {
    /**
     * Convert daemon DetectionResult array to List<DetectedElement>.
     * Preserves all fields from the daemon result.
     */
    fun convertDetectionResults(
      results: Array<DetectionResult>,
      imageWidth: Int,
      imageHeight: Int,
    ): List<DetectedElement> {
      return results.map { result ->
        val bounds = UIBounds(
          left = (result.x1 * imageWidth).toInt(),
          top = (result.y1 * imageHeight).toInt(),
          right = (result.x2 * imageWidth).toInt(),
          bottom = (result.y2 * imageHeight).toInt(),
        )

        val elementType = ElementType.fromClassId(result.classId)

        DetectedElement(
          boundingBox = bounds,
          elementType = elementType,
          confidence = result.confidence,
          text = result.text?.takeIf { it.isNotBlank() },
          textConfidence = if (result.textConfidence > 0f) result.textConfidence else null,
        )
      }
    }
  }
}
