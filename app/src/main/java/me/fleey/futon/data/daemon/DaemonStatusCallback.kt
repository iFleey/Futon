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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.fleey.futon.DaemonStatus
import me.fleey.futon.DetectionResult
import me.fleey.futon.IStatusCallback
import me.fleey.futon.data.daemon.models.AutomationEvent
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.domain.perception.models.UIBounds
import java.io.Closeable

interface AutomationCompleteListener {
  fun onAutomationComplete(success: Boolean, message: String?)
  fun onHotPathNoMatch(consecutiveFrames: Int)
}

interface DaemonStatusCallbackProvider {
  val status: StateFlow<DaemonStatus?>
  val detectionResults: SharedFlow<List<DetectedElement>>
  val errors: SharedFlow<DaemonError>
  val loopDetectedEvents: SharedFlow<AutomationEvent.LoopDetected>
  val memoryPressureEvents: SharedFlow<MemoryPressureEvent>
  val asyncResults: SharedFlow<AsyncResult>

  fun setAutomationCompleteListener(listener: AutomationCompleteListener?)
}

data class MemoryPressureEvent(
  val level: Int,
  val timestamp: Long = System.currentTimeMillis(),
) {
  val isLow: Boolean get() = level == LEVEL_LOW
  val isMedium: Boolean get() = level == LEVEL_MEDIUM
  val isCritical: Boolean get() = level == LEVEL_CRITICAL

  companion object {
    const val LEVEL_LOW = 1
    const val LEVEL_MEDIUM = 2
    const val LEVEL_CRITICAL = 3
  }
}

data class AsyncResult(
  val requestId: Long,
  val result: ByteArray,
  val timestamp: Long = System.currentTimeMillis(),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as AsyncResult
    return requestId == other.requestId &&
      result.contentEquals(other.result) &&
      timestamp == other.timestamp
  }

  override fun hashCode(): Int {
    var result1 = requestId.hashCode()
    result1 = 31 * result1 + result.contentHashCode()
    result1 = 31 * result1 + timestamp.hashCode()
    return result1
  }
}

class DaemonStatusCallback : IStatusCallback.Stub(), DaemonStatusCallbackProvider, Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _status = MutableStateFlow<DaemonStatus?>(null)
  override val status: StateFlow<DaemonStatus?> = _status.asStateFlow()

  private val _detectionResults = MutableSharedFlow<List<DetectedElement>>(
    replay = 1,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val detectionResults: SharedFlow<List<DetectedElement>> =
    _detectionResults.asSharedFlow()

  private val _errors = MutableSharedFlow<DaemonError>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val errors: SharedFlow<DaemonError> = _errors.asSharedFlow()

  private val _loopDetectedEvents = MutableSharedFlow<AutomationEvent.LoopDetected>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val loopDetectedEvents: SharedFlow<AutomationEvent.LoopDetected> =
    _loopDetectedEvents.asSharedFlow()

  private val _memoryPressureEvents = MutableSharedFlow<MemoryPressureEvent>(
    extraBufferCapacity = 4,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val memoryPressureEvents: SharedFlow<MemoryPressureEvent> =
    _memoryPressureEvents.asSharedFlow()

  private val _asyncResults = MutableSharedFlow<AsyncResult>(
    extraBufferCapacity = 32,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val asyncResults: SharedFlow<AsyncResult> = _asyncResults.asSharedFlow()

  @Volatile
  private var automationCompleteListener: AutomationCompleteListener? = null

  override fun setAutomationCompleteListener(listener: AutomationCompleteListener?) {
    automationCompleteListener = listener
  }

  override fun onStatusUpdate(status: DaemonStatus) {
    scope.launch {
      _status.value = status
    }
  }

  override fun onDetectionResult(results: Array<DetectionResult>?) {
    if (results == null) return

    scope.launch {
      val elements = results.mapNotNull { result ->
        convertToDetectedElement(result)
      }
      _detectionResults.emit(elements)
    }
  }

  override fun onAutomationComplete(success: Boolean, message: String?) {
    scope.launch(Dispatchers.Default) {
      try {
        automationCompleteListener?.onAutomationComplete(success, message)
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying automation complete listener", e)
      }
    }
  }

  override fun onError(code: Int, message: String?) {
    scope.launch {
      val errorCode = ErrorCode.fromCode(code)
      val error = DaemonError(
        code = errorCode,
        message = message ?: "Unknown error from daemon",
      )
      Log.e(TAG, "Daemon error: code=$code, message=$message")
      _errors.emit(error)
    }
  }

  override fun onLoopDetected(stateHash: Long, consecutiveCount: Int) {
    scope.launch {
      val event = AutomationEvent.LoopDetected(
        stateHash = stateHash,
        consecutiveCount = consecutiveCount,
      )
      Log.w(TAG, "Loop detected: hash=$stateHash, count=$consecutiveCount")
      _loopDetectedEvents.emit(event)
    }
  }

  override fun onMemoryPressure(level: Int) {
    scope.launch {
      val event = MemoryPressureEvent(level = level)
      Log.w(TAG, "Memory pressure: level=$level")
      _memoryPressureEvents.emit(event)
    }
  }

  override fun onAsyncResult(requestId: Long, result: ByteArray?) {
    if (result == null) return

    scope.launch {
      val asyncResult = AsyncResult(
        requestId = requestId,
        result = result,
      )
      _asyncResults.emit(asyncResult)
    }
  }

  override fun close() {
    automationCompleteListener = null
    _status.value = null
  }

  private fun convertToDetectedElement(result: DetectionResult): DetectedElement? {
    return try {
      val boundingBox = UIBounds(
        left = result.x1.toInt(),
        top = result.y1.toInt(),
        right = result.x2.toInt(),
        bottom = result.y2.toInt(),
      )

      if (!boundingBox.isValid()) {
        Log.w(TAG, "Invalid bounding box: $boundingBox")
        return null
      }

      val confidence = result.confidence.coerceIn(0f, 1f)
      val elementType = ElementType.fromClassId(result.classId)

      val text = result.text?.takeIf { it.isNotBlank() }
      val textConfidence = if (text != null) {
        result.textConfidence.coerceIn(0f, 1f)
      } else {
        null
      }

      DetectedElement(
        boundingBox = boundingBox,
        elementType = elementType,
        confidence = confidence,
        text = text,
        textConfidence = textConfidence,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to convert DetectionResult to DetectedElement", e)
      null
    }
  }

  companion object {
    private const val TAG = "DaemonStatusCallback"
  }
}
