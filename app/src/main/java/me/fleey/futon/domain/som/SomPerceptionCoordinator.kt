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
package me.fleey.futon.domain.som

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Base64
import android.util.Log
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.perception.PerformanceMonitor
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.domain.perception.PerceptionEngine
import me.fleey.futon.domain.perception.PerceptionResult
import me.fleey.futon.domain.perception.models.UITree
import me.fleey.futon.domain.som.models.SomAnnotation
import org.koin.core.annotation.Single
import java.io.ByteArrayOutputStream

sealed interface SomCaptureResult {
  data class Success(
    val annotation: SomAnnotation,
    val originalScreenshot: String,
    val annotatedScreenshot: String,
    val uiTree: UITree?,
    val captureLatencyMs: Long,
    val annotationLatencyMs: Long,
  ) : SomCaptureResult

  data class Failure(
    val reason: String,
    val isRecoverable: Boolean = true,
  ) : SomCaptureResult
}

interface SomPerceptionCoordinator {
  suspend fun capture(): SomCaptureResult

  suspend fun capture(
    includeUITree: Boolean,
    renderAnnotations: Boolean,
    screenshotQuality: Int,
  ): SomCaptureResult
}

@Single(binds = [SomPerceptionCoordinator::class])
class SomPerceptionCoordinatorImpl(
  private val daemonRepository: DaemonRepository,
  private val perceptionEngine: PerceptionEngine,
  private val somAnnotator: SomAnnotator,
  private val somImageRenderer: SomImageRenderer,
  private val settingsRepository: SettingsRepository,
  private val performanceMonitor: PerformanceMonitor,
) : SomPerceptionCoordinator {

  override suspend fun capture(): SomCaptureResult {
    val somSettings = settingsRepository.getSomSettings()
    return capture(
      includeUITree = somSettings.includeUITree,
      renderAnnotations = somSettings.renderAnnotations,
      screenshotQuality = somSettings.screenshotQuality,
    )
  }

  override suspend fun capture(
    includeUITree: Boolean,
    renderAnnotations: Boolean,
    screenshotQuality: Int,
  ): SomCaptureResult {
    val startTime = System.currentTimeMillis()
    val somSettings = settingsRepository.getSomSettings()

    // Ensure daemon session is valid before capture
    val daemonState = daemonRepository.daemonState.value
    if (daemonState !is me.fleey.futon.data.daemon.models.DaemonState.Ready) {
      Log.w(TAG, "Daemon not ready, attempting to connect...")
      val connectResult = daemonRepository.connect()
      if (connectResult.isFailure) {
        return SomCaptureResult.Failure(
          reason = "Daemon not connected: ${connectResult.exceptionOrNull()?.message}",
          isRecoverable = true,
        )
      }
    }

    val screenshotResult = daemonRepository.getScreenshot()
    if (screenshotResult.isFailure) {
      val error = screenshotResult.exceptionOrNull()?.message ?: "Unknown error"
      Log.e(TAG, "Screenshot capture failed: $error")
      return SomCaptureResult.Failure(
        reason = "Screenshot capture failed: $error",
        isRecoverable = error.contains("session", ignoreCase = true),
      )
    }

    val screenshot = screenshotResult.getOrNull()
      ?: return SomCaptureResult.Failure("Screenshot is null")

    val bufferId = screenshot.bufferId
    val hardwareBuffer = screenshot.buffer
    val width = screenshot.width
    val height = screenshot.height

    try {
      val bitmap = hardwareBufferToBitmap(hardwareBuffer, width, height)
        ?: return SomCaptureResult.Failure("Failed to convert HardwareBuffer to Bitmap")

      val perceptionResult = daemonRepository.requestPerception()
      val detectedElements = perceptionResult.getOrElse { emptyList() }

      val captureLatencyMs = System.currentTimeMillis() - startTime

      var uiTree: UITree? = null
      if (includeUITree) {
        when (val treeResult = perceptionEngine.captureUITree()) {
          is PerceptionResult.Success -> uiTree = treeResult.tree
          else -> Log.w(TAG, "UI tree capture failed, continuing without it")
        }
      }

      val annotationStartTime = System.currentTimeMillis()
      val annotatorConfig = SomAnnotatorConfig(
        minConfidence = somSettings.minConfidence,
        maxElements = somSettings.maxElements,
        mergeIouThreshold = somSettings.mergeIouThreshold,
        filterSmallElements = somSettings.filterSmallElements,
        minElementArea = somSettings.minElementArea,
      )
      val annotation = somAnnotator.annotate(
        detectedElements = detectedElements,
        screenWidth = width,
        screenHeight = height,
        uiTree = uiTree,
        config = annotatorConfig,
      )
      val annotationLatencyMs = System.currentTimeMillis() - annotationStartTime

      val originalBase64 = bitmapToBase64(bitmap, screenshotQuality)

      val annotatedBase64 = if (renderAnnotations && annotation.elements.isNotEmpty()) {
        val renderConfig = SomRenderConfig(
          markerSize = somSettings.markerSize,
          showBoundingBox = somSettings.showBoundingBoxes,
          compressionQuality = screenshotQuality,
        )
        somImageRenderer.renderToBase64(bitmap, annotation, renderConfig)
      } else {
        originalBase64
      }

      daemonRepository.releaseScreenshot(bufferId)
      bitmap.recycle()

      val totalLatencyMs = System.currentTimeMillis() - startTime

      performanceMonitor.recordLoop(
        captureMs = captureLatencyMs,
        detectionMs = 0L, // Detection is done on daemon side, included in captureLatencyMs
        ocrMs = 0L,       // OCR is done on daemon side, included in captureLatencyMs
        totalMs = totalLatencyMs,
        delegate = DelegateType.GPU,
      )

      return SomCaptureResult.Success(
        annotation = annotation,
        originalScreenshot = originalBase64,
        annotatedScreenshot = annotatedBase64,
        uiTree = uiTree,
        captureLatencyMs = captureLatencyMs,
        annotationLatencyMs = annotationLatencyMs,
      )
    } catch (e: Exception) {
      Log.e(TAG, "SoM capture failed", e)
      daemonRepository.releaseScreenshot(bufferId)
      return SomCaptureResult.Failure(
        reason = "SoM capture error: ${e.message}",
        isRecoverable = true,
      )
    }
  }

  private fun hardwareBufferToBitmap(
    hardwareBuffer: HardwareBuffer?,
    width: Int,
    height: Int,
  ): Bitmap? {
    if (hardwareBuffer == null) return null

    return try {
      val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
        ?: return null

      // Convert to software bitmap for manipulation
      bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
        bitmap.recycle()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to convert HardwareBuffer to Bitmap", e)
      null
    }
  }

  private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
  }

  companion object {
    private const val TAG = "SomPerceptionCoordinator"
  }
}
