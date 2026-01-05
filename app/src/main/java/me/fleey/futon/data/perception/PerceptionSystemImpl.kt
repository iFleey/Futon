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

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.DetectionConfig
import me.fleey.futon.data.perception.models.DetectionResult
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.data.perception.models.HardwareCapabilities
import me.fleey.futon.data.perception.models.OCROperationResult
import me.fleey.futon.data.perception.models.PerceptionConfig
import me.fleey.futon.data.perception.models.PerceptionError
import me.fleey.futon.data.perception.models.PerceptionMetrics
import me.fleey.futon.data.perception.models.PerceptionOperationResult
import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.perception.models.PerceptionStage
import me.fleey.futon.data.perception.models.PerceptionStageError
import me.fleey.futon.data.perception.models.PerceptionSystemState
import me.fleey.futon.data.perception.models.ZeroCopyCaptureConfig
import me.fleey.futon.data.perception.models.ZeroCopyCaptureResult
import me.fleey.futon.domain.perception.models.UIBounds
import java.util.concurrent.atomic.AtomicBoolean

class PerceptionSystemImpl(
  private val context: Context,
  private val zeroCopyCapture: ZeroCopyCapture,
  private val uiDetector: UIDetector,
  private val ocrEngine: OCREngine,
  private val hardwareDetector: HardwareDetector,
  private val liteRTEngine: LiteRTEngine? = null,
) : PerceptionSystem {

  companion object {
    private const val TAG = "PerceptionSystemImpl"
    private const val DEFAULT_SCREEN_WIDTH = 1080
    private const val DEFAULT_SCREEN_HEIGHT = 2400
    private const val RESUME_TIMEOUT_MS = 100L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _state = MutableStateFlow(PerceptionSystemState.UNINITIALIZED)
  override val state: StateFlow<PerceptionSystemState> = _state.asStateFlow()

  private var _config: PerceptionConfig? = null
  override val config: PerceptionConfig? get() = _config

  private val perceptionMutex = Mutex()
  private val metricsFlow = MutableSharedFlow<PerceptionMetrics>(replay = 1)

  private val latencyTracker = LatencyTracker()
  private var hardwareCapabilities: HardwareCapabilities? = null

  private var screenWidth: Int = 0
  private var screenHeight: Int = 0

  private val isClosed = AtomicBoolean(false)
  private var allocationLimitsManager: AllocationLimitsManager? = null

  override suspend fun initialize(config: PerceptionConfig): Boolean = withContext(Dispatchers.IO) {
    if (_state.value == PerceptionSystemState.DESTROYED || isClosed.get()) {
      Log.w(TAG, "Cannot initialize - system is destroyed")
      return@withContext false
    }

    _state.value = PerceptionSystemState.INITIALIZING
    _config = config
    Log.i(TAG, "Starting initialization with config: $config")

    try {
      initializeScreenDimensions()
      Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")

      Log.d(TAG, "Detecting hardware capabilities...")
      hardwareCapabilities = hardwareDetector.detectCapabilities()
      Log.d(TAG, "Hardware capabilities: $hardwareCapabilities")

      Log.d(TAG, "Initializing ZeroCopyCapture...")
      val captureConfig = ZeroCopyCaptureConfig(
        width = screenWidth,
        height = screenHeight,
        downscaleFactor = 1,
        maxBuffers = config.maxConcurrentBuffers,
      )
      if (!zeroCopyCapture.initialize(captureConfig)) {
        Log.e(TAG, "Failed to initialize ZeroCopyCapture")
        cleanupPartialInitialization()
        _state.value = PerceptionSystemState.ERROR
        return@withContext false
      }
      Log.d(TAG, "ZeroCopyCapture initialized")

      Log.d(TAG, "Initializing UIDetector (with 10s timeout)...")
      val detectionConfig = DetectionConfig(
        minConfidence = config.minConfidence,
      )
      val uiDetectorResult = withTimeoutOrNull(10_000L) {
        uiDetector.initialize(detectionConfig)
      }
      if (uiDetectorResult != true) {
        Log.e(TAG, "Failed to initialize UIDetector (timeout or error)")
        cleanupPartialInitialization()
        _state.value = PerceptionSystemState.ERROR
        return@withContext false
      }
      Log.d(TAG, "UIDetector initialized")

      if (config.enableOcr) {
        Log.d(TAG, "Initializing OCREngine (with 10s timeout)...")
        val ocrResult = withTimeoutOrNull(10_000L) {
          ocrEngine.initialize(config.ocrScripts).getOrNull()
        }
        if (ocrResult == null) {
          Log.e(TAG, "Failed to initialize OCREngine (timeout or error)")
          cleanupPartialInitialization()
          _state.value = PerceptionSystemState.ERROR
          return@withContext false
        }
        Log.d(TAG, "OCREngine initialized")
      }

      if (liteRTEngine != null) {
        allocationLimitsManager = AllocationLimitsManagerImpl(
          zeroCopyCapture = zeroCopyCapture,
          liteRTEngine = liteRTEngine,
          initialLimits = AllocationLimits(maxBuffers = config.maxConcurrentBuffers),
        )
      }

      _state.value = PerceptionSystemState.READY
      Log.i(TAG, "Perception system initialized successfully")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Exception during initialization", e)
      cleanupPartialInitialization()
      _state.value = PerceptionSystemState.ERROR
      false
    }
  }

  private fun cleanupPartialInitialization() {
    Log.i(TAG, "Cleaning up partial initialization")
    try {
      zeroCopyCapture.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying ZeroCopyCapture during cleanup", e)
    }
    try {
      uiDetector.close()
    } catch (e: Exception) {
      Log.e(TAG, "Error closing UIDetector during cleanup", e)
    }
    try {
      ocrEngine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Error closing OCREngine during cleanup", e)
    }
  }

  override fun setMediaProjection(projection: MediaProjection): Boolean {
    if (_state.value != PerceptionSystemState.READY) {
      Log.w(TAG, "Cannot set MediaProjection - system not ready: ${_state.value}")
      return false
    }

    val success = zeroCopyCapture.setMediaProjection(projection)
    if (success) {
      Log.i(TAG, "MediaProjection set successfully, VirtualDisplay started")
    } else {
      Log.e(TAG, "Failed to set MediaProjection")
    }
    return success
  }

  override fun hasActiveCapture(): Boolean {
    return zeroCopyCapture.hasActiveProjection()
  }

  override suspend fun perceive(): PerceptionOperationResult = withContext(Dispatchers.Default) {
    val currentState = _state.value
    when (currentState) {
      PerceptionSystemState.UNINITIALIZED ->
        return@withContext PerceptionOperationResult.Failure(
          PerceptionError.NOT_INITIALIZED,
          "Perception system not initialized",
        )

      PerceptionSystemState.PAUSED ->
        return@withContext PerceptionOperationResult.Failure(
          PerceptionError.PAUSED,
          "Perception system is paused",
        )

      PerceptionSystemState.DESTROYED ->
        return@withContext PerceptionOperationResult.Failure(
          PerceptionError.DESTROYED,
          "Perception system is destroyed",
        )

      PerceptionSystemState.ERROR ->
        return@withContext PerceptionOperationResult.Failure(
          PerceptionError.NOT_INITIALIZED,
          "Perception system is in error state",
        )

      else -> { /* proceed */
      }
    }

    perceptionMutex.withLock {
      executePerceptionPipeline()
    }
  }

  private suspend fun executePerceptionPipeline(): PerceptionOperationResult {
    val startTime = System.currentTimeMillis()
    val errors = mutableListOf<PerceptionStageError>()
    var activeDelegate = DelegateType.NONE

    _state.value = PerceptionSystemState.PERCEIVING

    val captureStart = System.currentTimeMillis()
    val captureResult = zeroCopyCapture.acquireBuffer()
    val captureLatency = System.currentTimeMillis() - captureStart

    val (bufferId, captureWidth, captureHeight) = when (captureResult) {
      is ZeroCopyCaptureResult.Success -> {
        Triple(captureResult.bufferId, captureResult.width, captureResult.height)
      }

      is ZeroCopyCaptureResult.Failure -> {
        _state.value = PerceptionSystemState.READY
        return PerceptionOperationResult.Failure(
          PerceptionError.CAPTURE_FAILED,
          captureResult.message,
        )
      }
    }

    try {
      val detectionStart = System.currentTimeMillis()
      val detectionResult = uiDetector.detect(
        bufferId = bufferId,
        width = captureWidth,
        height = captureHeight,
        minConfidence = _config?.minConfidence,
      )
      val detectionLatency = System.currentTimeMillis() - detectionStart

      val detections = when (detectionResult) {
        is DetectionResult.Success -> {
          activeDelegate = detectionResult.activeDelegate
          detectionResult.detections
        }

        is DetectionResult.Failure -> {
          errors.add(PerceptionStageError(PerceptionStage.DETECTION, detectionResult.message))
          emptyList()
        }
      }

      var ocrLatency = 0L
      val detectedElements = if (_config?.enableOcr == true && detections.isNotEmpty()) {
        val ocrStart = System.currentTimeMillis()
        val elementsWithOcr = runOcrOnTextElements(
          detections = detections,
          bufferId = bufferId,
          imageWidth = captureWidth,
          imageHeight = captureHeight,
          errors = errors,
        )
        ocrLatency = System.currentTimeMillis() - ocrStart
        elementsWithOcr
      } else {
        detections.map { detection ->
          DetectedElement(
            boundingBox = detection.boundingBox.toAbsolute(captureWidth, captureHeight),
            elementType = ElementType.fromClassId(detection.classId),
            confidence = detection.confidence,
          )
        }
      }

      val totalLatency = System.currentTimeMillis() - startTime

      latencyTracker.record(captureLatency, detectionLatency, ocrLatency, totalLatency)
      emitMetrics(activeDelegate)

      _state.value = PerceptionSystemState.READY

      val result = PerceptionResult(
        elements = detectedElements,
        captureLatencyMs = captureLatency,
        detectionLatencyMs = detectionLatency,
        ocrLatencyMs = ocrLatency,
        totalLatencyMs = totalLatency,
        activeDelegate = activeDelegate,
        timestamp = System.currentTimeMillis(),
        imageWidth = captureWidth,
        imageHeight = captureHeight,
      )

      return if (errors.isEmpty()) {
        PerceptionOperationResult.Success(result)
      } else {
        PerceptionOperationResult.PartialSuccess(result, errors)
      }
    } finally {
      zeroCopyCapture.releaseBuffer(bufferId)
    }
  }

  private suspend fun runOcrOnTextElements(
    detections: List<me.fleey.futon.data.perception.models.Detection>,
    bufferId: Long,
    imageWidth: Int,
    imageHeight: Int,
    errors: MutableList<PerceptionStageError>,
  ): List<DetectedElement> = withContext(Dispatchers.Default) {
    val textElementTypes = setOf(
      ElementType.TEXT_LABEL,
      ElementType.TEXT_FIELD,
      ElementType.BUTTON,
    )

    val bitmap = getBitmapFromBuffer(bufferId, imageWidth, imageHeight)
      ?: return@withContext detections.map { detection ->
        DetectedElement(
          boundingBox = detection.boundingBox.toAbsolute(imageWidth, imageHeight),
          elementType = ElementType.fromClassId(detection.classId),
          confidence = detection.confidence,
        )
      }

    try {
      detections.map { detection ->
        val elementType = ElementType.fromClassId(detection.classId)
        val bounds = detection.boundingBox.toAbsolute(imageWidth, imageHeight)

        val (text, textConfidence) = if (elementType in textElementTypes) {
          runOcrOnRegion(bitmap, bounds, errors)
        } else {
          null to null
        }

        DetectedElement(
          boundingBox = bounds,
          elementType = elementType,
          confidence = detection.confidence,
          text = text,
          textConfidence = textConfidence,
        )
      }
    } finally {
      if (!bitmap.isRecycled) {
        bitmap.recycle()
      }
    }
  }

  private suspend fun runOcrOnRegion(
    bitmap: Bitmap,
    bounds: UIBounds,
    errors: MutableList<PerceptionStageError>,
  ): Pair<String?, Float?> {
    if (!bounds.isValid() || bounds.width < 10 || bounds.height < 10) {
      return null to null
    }

    val croppedBitmap = BitmapCropper.crop(bitmap, bounds) ?: return null to null

    return try {
      when (val result = ocrEngine.recognize(croppedBitmap, null)) {
        is OCROperationResult.Success -> {
          if (result.result.isEmpty) {
            null to null
          } else {
            result.result.text to result.result.confidence
          }
        }

        is OCROperationResult.Failure -> {
          errors.add(PerceptionStageError(PerceptionStage.OCR, result.message))
          null to null
        }
      }
    } finally {
      if (!croppedBitmap.isRecycled && croppedBitmap != bitmap) {
        croppedBitmap.recycle()
      }
    }
  }

  private fun getBitmapFromBuffer(bufferId: Long, width: Int, height: Int): Bitmap? {
    val hardwareBuffer = zeroCopyCapture.getHardwareBuffer(bufferId) ?: return null
    return try {
      Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
        ?.copy(Bitmap.Config.ARGB_8888, false)
    } catch (e: Exception) {
      null
    }
  }

  private fun emitMetrics(activeDelegate: DelegateType) {
    val metrics = PerceptionMetrics(
      averageLatencyMs = latencyTracker.getAverageTotal(),
      p95LatencyMs = latencyTracker.getP95Total(),
      loopCount = latencyTracker.getLoopCount(),
      modelMemoryBytes = getModelMemoryBytes(),
      bufferMemoryBytes = getBufferMemoryBytes(),
      activeDelegate = activeDelegate,
      isAboveThreshold = latencyTracker.getAverageTotal() > (_config?.targetLatencyMs ?: 30),
      captureAverageMs = latencyTracker.getAverageCapture(),
      detectionAverageMs = latencyTracker.getAverageDetection(),
      ocrAverageMs = latencyTracker.getAverageOcr(),
    )
    scope.launch {
      metricsFlow.emit(metrics)
    }
  }

  private fun getModelMemoryBytes(): Long {
    return liteRTEngine?.getStats()?.modelMemoryBytes ?: 0L
  }

  private fun getBufferMemoryBytes(): Long {
    val stats = zeroCopyCapture.getStats()
    return stats.bufferMemoryBytes
  }

  override fun getHardwareCapabilities(): HardwareCapabilities {
    return hardwareCapabilities ?: HardwareCapabilities.UNKNOWN
  }

  override fun observeMetrics(): Flow<PerceptionMetrics> = metricsFlow.asSharedFlow()

  override fun getCurrentMetrics(): PerceptionMetrics {
    val activeDelegate = DelegateType.NONE
    return PerceptionMetrics(
      averageLatencyMs = latencyTracker.getAverageTotal(),
      p95LatencyMs = latencyTracker.getP95Total(),
      loopCount = latencyTracker.getLoopCount(),
      modelMemoryBytes = getModelMemoryBytes(),
      bufferMemoryBytes = getBufferMemoryBytes(),
      activeDelegate = activeDelegate,
      isAboveThreshold = latencyTracker.getAverageTotal() > (_config?.targetLatencyMs ?: 30),
      captureAverageMs = latencyTracker.getAverageCapture(),
      detectionAverageMs = latencyTracker.getAverageDetection(),
      ocrAverageMs = latencyTracker.getAverageOcr(),
    )
  }

  override fun pause() {
    if (_state.value == PerceptionSystemState.READY ||
      _state.value == PerceptionSystemState.PERCEIVING
    ) {
      Log.i(TAG, "Pausing perception system")
      zeroCopyCapture.pause()
      _state.value = PerceptionSystemState.PAUSED
      Log.i(TAG, "Perception system paused")
    }
  }

  override suspend fun resume(): Boolean {
    if (_state.value != PerceptionSystemState.PAUSED) {
      return _state.value == PerceptionSystemState.READY
    }

    Log.i(TAG, "Resuming perception system")
    val startTime = System.currentTimeMillis()

    val resumed = withTimeoutOrNull(RESUME_TIMEOUT_MS) {
      zeroCopyCapture.resume()
    } ?: false

    val latency = System.currentTimeMillis() - startTime

    if (resumed) {
      _state.value = PerceptionSystemState.READY
      Log.i(TAG, "Perception system resumed in ${latency}ms (within ${RESUME_TIMEOUT_MS}ms limit)")
    } else {
      Log.w(TAG, "Resume timed out after ${latency}ms (limit: ${RESUME_TIMEOUT_MS}ms)")
    }
    return resumed
  }

  override fun isReady(): Boolean = _state.value == PerceptionSystemState.READY

  override fun isPaused(): Boolean = _state.value == PerceptionSystemState.PAUSED

  override fun close() {
    if (isClosed.getAndSet(true)) {
      Log.d(TAG, "Already closed")
      return
    }

    Log.i(TAG, "Closing perception system")
    _state.value = PerceptionSystemState.DESTROYED

    try {
      zeroCopyCapture.close()
      Log.d(TAG, "ZeroCopyCapture closed")
    } catch (e: Exception) {
      Log.e(TAG, "Error closing ZeroCopyCapture", e)
    }

    try {
      uiDetector.close()
      Log.d(TAG, "UIDetector closed")
    } catch (e: Exception) {
      Log.e(TAG, "Error closing UIDetector", e)
    }

    try {
      ocrEngine.close()
      Log.d(TAG, "OCREngine closed")
    } catch (e: Exception) {
      Log.e(TAG, "Error closing OCREngine", e)
    }

    try {
      liteRTEngine?.close()
      Log.d(TAG, "LiteRTEngine closed")
    } catch (e: Exception) {
      Log.e(TAG, "Error closing LiteRTEngine", e)
    }

    scope.cancel()
    allocationLimitsManager = null

    Log.i(TAG, "Perception system closed")
  }

  fun getAllocationLimitsManager(): AllocationLimitsManager? = allocationLimitsManager

  private fun initializeScreenDimensions() {
    try {
      val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(metrics)
      screenWidth = metrics.widthPixels
      screenHeight = metrics.heightPixels
    } catch (e: Exception) {
      Log.w(TAG, "Failed to get screen dimensions, using defaults", e)
      screenWidth = DEFAULT_SCREEN_WIDTH
      screenHeight = DEFAULT_SCREEN_HEIGHT
    }
  }
}
