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
package me.fleey.futon.domain.automation.perception

import android.util.Log
import me.fleey.futon.data.capture.PrivacyManager
import me.fleey.futon.data.capture.ScreenCapture
import me.fleey.futon.data.capture.ScreenCaptureStrategyFactory
import me.fleey.futon.data.capture.models.CaptureErrorCode
import me.fleey.futon.data.capture.models.CaptureResult
import me.fleey.futon.data.perception.PerceptionSystem
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.data.perception.models.PerceptionConfig
import me.fleey.futon.data.perception.models.PerceptionOperationResult
import me.fleey.futon.data.privacy.models.CaptureDecision
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.PerceptionModeConfig
import me.fleey.futon.domain.perception.PerceptionEngine
import me.fleey.futon.domain.perception.PerceptionResult
import me.fleey.futon.domain.perception.models.UIBounds
import me.fleey.futon.domain.perception.models.UITree
import me.fleey.futon.domain.som.SomCaptureResult
import me.fleey.futon.domain.som.SomPerceptionCoordinator
import me.fleey.futon.domain.som.SomPromptBuilder
import me.fleey.futon.domain.som.models.SomAnnotation
import me.fleey.futon.domain.som.models.SomElementType
import org.koin.core.annotation.Single

sealed interface PerceptionCaptureResult {
  data class Success(
    val screenshot: String?,
    val uiTree: UITree?,
    val detectedElements: List<DetectedElement>,
    val combinedContext: String?,
    /** SOM annotation when SOM mode is enabled */
    val somAnnotation: SomAnnotation? = null,
    /** Annotated screenshot with SOM markers (when SOM enabled) */
    val annotatedScreenshot: String? = null,
    val isSomMode: Boolean = false,
  ) : PerceptionCaptureResult

  data class Failure(
    val reason: String,
    val isPrivacyBlocked: Boolean = false,
  ) : PerceptionCaptureResult
}

/**
 * Coordinates all perception operations: screenshot, UI tree, DSP detection, and SOM.
 * Handles privacy checks and mode-based capture decisions.
 * When SOM is enabled, automatically annotates screenshots with element markers.
 */
interface PerceptionCoordinator {
  suspend fun initialize(): Boolean
  suspend fun capture(screenshotQuality: Int): PerceptionCaptureResult

  fun isSomEnabled(): Boolean
  fun release()
}

@Single(binds = [PerceptionCoordinator::class])
class PerceptionCoordinatorImpl(
  private val screenCaptureStrategyFactory: ScreenCaptureStrategyFactory,
  private val privacyManager: PrivacyManager,
  private val perceptionEngine: PerceptionEngine,
  private val perceptionSystem: PerceptionSystem,
  private val settingsRepository: SettingsRepository,
  private val somPerceptionCoordinator: SomPerceptionCoordinator,
  private val somPromptBuilder: SomPromptBuilder,
) : PerceptionCoordinator {

  private var currentScreenCapture: ScreenCapture? = null
  private var dspPerceptionInitialized = false

  override suspend fun initialize(): Boolean {
    return try {
      currentScreenCapture = screenCaptureStrategyFactory.selectBestMethod()
      Log.i(TAG, "Selected capture method: ${currentScreenCapture?.method}")
      true
    } catch (e: IllegalStateException) {
      Log.e(TAG, "No capture method available", e)
      false
    }
  }

  override fun isSomEnabled(): Boolean {
    return try {
      kotlinx.coroutines.runBlocking { settingsRepository.getSomSettings().enabled }
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun capture(screenshotQuality: Int): PerceptionCaptureResult {
    val somSettings = settingsRepository.getSomSettings()

    if (somSettings.enabled) {
      return captureSom(screenshotQuality)
    }

    return captureLegacy(screenshotQuality)
  }

  /**
   * SOM-enhanced capture: annotated screenshot + element list
   */
  private suspend fun captureSom(screenshotQuality: Int): PerceptionCaptureResult {
    Log.i(TAG, "Using SOM capture mode")

    when (val somResult = somPerceptionCoordinator.capture()) {
      is SomCaptureResult.Success -> {
        // Build SOM context for AI
        val somContext = somPromptBuilder.buildElementList(somResult.annotation)

        // If very few elements detected, SOM might not be useful for this screen
        // (e.g., game screens with non-standard UI elements)
        val elementCount = somResult.annotation.elementCount
        val useAnnotatedScreenshot = elementCount >= 5

        if (elementCount < 5) {
          Log.w(TAG, "SOM detected only $elementCount elements - may be a game/custom UI screen")
        }

        return PerceptionCaptureResult.Success(
          screenshot = if (useAnnotatedScreenshot) somResult.annotatedScreenshot else somResult.originalScreenshot,
          uiTree = somResult.uiTree,
          detectedElements = somResult.annotation.elements.map { somElement ->
            DetectedElement(
              elementType = mapSomTypeToElementType(somElement.type),
              boundingBox = UIBounds(
                left = somElement.bounds.left,
                top = somElement.bounds.top,
                right = somElement.bounds.right,
                bottom = somElement.bounds.bottom,
              ),
              confidence = somElement.confidence,
              text = somElement.text,
            )
          },
          combinedContext = if (elementCount >= 5) somContext else {
            // For screens with few detected elements, add a hint
            "$somContext\n\nNote: Few UI elements detected. This may be a game or custom interface. Use visual analysis of the screenshot for non-standard elements."
          },
          somAnnotation = somResult.annotation,
          annotatedScreenshot = somResult.annotatedScreenshot,
          isSomMode = elementCount >= 5,
        )
      }

      is SomCaptureResult.Failure -> {
        Log.w(TAG, "SOM capture failed: ${somResult.reason}, falling back to legacy")
        return if (somResult.isRecoverable) {
          captureLegacy(screenshotQuality)
        } else {
          PerceptionCaptureResult.Failure(somResult.reason)
        }
      }
    }
  }

  private fun mapSomTypeToElementType(somType: SomElementType): ElementType {
    return when (somType) {
      SomElementType.BUTTON -> ElementType.BUTTON
      SomElementType.INPUT_FIELD -> ElementType.TEXT_FIELD
      SomElementType.CHECKBOX -> ElementType.CHECKBOX
      SomElementType.SWITCH -> ElementType.SWITCH
      SomElementType.ICON -> ElementType.ICON
      SomElementType.IMAGE -> ElementType.IMAGE
      SomElementType.TEXT -> ElementType.TEXT_LABEL
      SomElementType.LIST_ITEM -> ElementType.LIST_ITEM
      SomElementType.UNKNOWN -> ElementType.UNKNOWN
    }
  }

  /**
   * Legacy capture: raw screenshot + UI tree + DSP detection
   */
  private suspend fun captureLegacy(screenshotQuality: Int): PerceptionCaptureResult {
    val hybridSettings = settingsRepository.getHybridPerceptionSettings()
    val perceptionMode = if (hybridSettings.enabled) {
      hybridSettings.perceptionMode
    } else {
      PerceptionModeConfig.SCREENSHOT_ONLY
    }

    val requiresScreenshot = perceptionMode != PerceptionModeConfig.UI_TREE_ONLY
    val requiresUITree =
      hybridSettings.enabled && perceptionMode != PerceptionModeConfig.SCREENSHOT_ONLY

    // Capture screenshot if needed
    var screenshot: String? = null
    if (requiresScreenshot) {
      when (val result = captureScreenshotWithPrivacy(screenshotQuality)) {
        is CaptureResult.Success -> {
          screenshot = result.base64Image
        }

        is CaptureResult.Failure -> {
          return PerceptionCaptureResult.Failure("Screenshot failed: ${result.message}")
        }

        is CaptureResult.PrivacyBlocked -> {
          return PerceptionCaptureResult.Failure(
            reason = "Screenshot blocked: ${result.reason}",
            isPrivacyBlocked = true,
          )
        }
      }
    }
    var uiTree: UITree? = null
    var uiContext: String? = null
    if (requiresUITree) {
      Log.i(TAG, "Capturing UI tree (enabled=$requiresUITree)")
      when (val result = perceptionEngine.captureUITree()) {
        is PerceptionResult.Success -> {
          uiTree = result.tree
          uiContext = result.tree.toAIContext(
            maxDepth = hybridSettings.uiTreeMaxDepth,
            includeNonInteractive = hybridSettings.includeNonInteractive,
          )
          Log.i(TAG, "UI tree captured: ${result.nodeCount} nodes, context length=${uiContext.length}")
        }

        is PerceptionResult.Error -> {
          Log.e(TAG, "UI tree capture error: ${result.message}")
          if (perceptionMode == PerceptionModeConfig.UI_TREE_ONLY) {
            return PerceptionCaptureResult.Failure("UI tree capture failed: ${result.message}")
          }
        }

        is PerceptionResult.RootUnavailable -> {
          Log.w(TAG, "UI tree capture failed: root unavailable")
          if (perceptionMode == PerceptionModeConfig.UI_TREE_ONLY) {
            return PerceptionCaptureResult.Failure("UI tree capture failed: root unavailable")
          }
        }

        is PerceptionResult.Timeout -> {
          Log.w(TAG, "UI tree capture timeout: ${result.timeoutMs}ms")
          if (perceptionMode == PerceptionModeConfig.UI_TREE_ONLY) {
            return PerceptionCaptureResult.Failure("UI tree capture failed: timeout")
          }
        }
      }
    } else {
      Log.d(TAG, "UI tree capture skipped (enabled=$requiresUITree, hybridEnabled=${hybridSettings.enabled})")
    }

    var detectedElements: List<DetectedElement> = emptyList()
    var dspContext: String? = null
    val dspSettings = settingsRepository.getDspPerceptionSettings()

    if (dspSettings.enabled) {
      initializeDspIfNeeded(dspSettings)
      if (perceptionSystem.isReady()) {
        when (val dspResult = perceptionSystem.perceive()) {
          is PerceptionOperationResult.Success -> {
            detectedElements = dspResult.result.elements
            dspContext = formatDspContextForAI(detectedElements)
          }

          is PerceptionOperationResult.PartialSuccess -> {
            detectedElements = dspResult.result.elements
            dspContext = formatDspContextForAI(detectedElements)
          }

          is PerceptionOperationResult.Failure -> {
            Log.w(TAG, "DSP perception failed: ${dspResult.message}")
          }
        }
      }
    }

    // Ensure we have at least one perception source
    if (screenshot == null && uiContext == null) {
      return PerceptionCaptureResult.Failure("No perception data available")
    }

    val combinedContext = buildCombinedContext(uiContext, dspContext)
    Log.i(
      TAG,
      "Combined context: uiContext=${uiContext?.length ?: 0} chars, dspContext=${dspContext?.length ?: 0} chars, combined=${combinedContext?.length ?: 0} chars",
    )

    return PerceptionCaptureResult.Success(
      screenshot = screenshot,
      uiTree = uiTree,
      detectedElements = detectedElements,
      combinedContext = combinedContext,
      somAnnotation = null,
      annotatedScreenshot = null,
      isSomMode = false,
    )
  }

  override fun release() {
    currentScreenCapture = null
    dspPerceptionInitialized = false
  }

  private suspend fun captureScreenshotWithPrivacy(quality: Int): CaptureResult {
    val screenCapture = currentScreenCapture
      ?: return CaptureResult.Failure(
        code = CaptureErrorCode.ROOT_NOT_AVAILABLE,
        message = "No screen capture method available",
      )

    return when (val decision = privacyManager.shouldAllowCapture()) {
      is CaptureDecision.Allowed -> screenCapture.capture(quality)
      is CaptureDecision.Blocked -> {
        val windowStatus = privacyManager.checkSecureWindow()
        CaptureResult.PrivacyBlocked(
          packageName = windowStatus.packageName ?: "unknown",
          reason = "Screenshot blocked: secure window detected",
        )
      }

      is CaptureDecision.NeedsConsent -> {
        CaptureResult.PrivacyBlocked(
          packageName = decision.packageName,
          reason = "Screenshot blocked: consent required",
        )
      }
    }
  }

  private suspend fun initializeDspIfNeeded(
    dspSettings: me.fleey.futon.data.settings.models.DspPerceptionSettings,
  ) {
    if (!dspPerceptionInitialized && !perceptionSystem.isReady()) {
      val config = PerceptionConfig(
        targetLatencyMs = dspSettings.targetLatencyMs,
        minConfidence = dspSettings.minConfidence,
        enableOcr = dspSettings.enableOcr,
        maxConcurrentBuffers = dspSettings.maxConcurrentBuffers,
      )
      dspPerceptionInitialized = perceptionSystem.initialize(config)
    }
  }

  private fun formatDspContextForAI(elements: List<DetectedElement>): String? {
    if (elements.isEmpty()) return null

    return buildString {
      appendLine("[DSP Detection Results - LiteRT + OCR]")
      appendLine("Detected ${elements.size} UI elements:")

      elements.forEachIndexed { index, element ->
        val typeStr = element.elementType.label
        append("  ${index + 1}. $typeStr at (${element.centerX}, ${element.centerY})")
        append(" conf=${String.format("%.2f", element.confidence)}")
        if (element.hasText) {
          append(" text=\"${element.text}\"")
        }
        appendLine()
      }

      val clickable = elements.filter {
        it.elementType in listOf(
          ElementType.BUTTON, ElementType.CHECKBOX,
          ElementType.SWITCH, ElementType.LIST_ITEM,
        )
      }
      if (clickable.isNotEmpty()) {
        appendLine()
        appendLine("Clickable elements:")
        clickable.forEach {
          appendLine("  - \"${it.text ?: it.elementType.label}\" at (${it.centerX}, ${it.centerY})")
        }
      }
    }
  }

  private fun buildCombinedContext(uiTreeContext: String?, dspContext: String?): String? {
    return when {
      uiTreeContext != null && dspContext != null -> {
        "$uiTreeContext\n\n--- Additional Visual Analysis ---\n$dspContext"
      }

      uiTreeContext != null -> uiTreeContext
      dspContext != null -> dspContext
      else -> null
    }
  }

  companion object {
    private const val TAG = "PerceptionCoordinator"
  }
}
