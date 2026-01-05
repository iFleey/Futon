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
package me.fleey.futon.ui.feature.settings.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.perception.ElementCacheRepository
import me.fleey.futon.data.perception.HardwareDetector
import me.fleey.futon.data.perception.PerformanceMonitor
import me.fleey.futon.data.perception.models.HardwareCapabilities
import me.fleey.futon.data.perception.models.ModelDeploymentState
import me.fleey.futon.data.perception.models.ModelDeploymentStatus
import me.fleey.futon.data.perception.models.ModelManager
import me.fleey.futon.data.perception.models.ModelManifest
import me.fleey.futon.data.perception.models.PerceptionMetrics
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.DspPerceptionSettings
import me.fleey.futon.data.settings.models.HybridPerceptionSettings
import me.fleey.futon.data.settings.models.PerceptionModeConfig
import me.fleey.futon.data.settings.models.SomSettings
import me.fleey.futon.domain.som.SomCaptureResult
import me.fleey.futon.domain.som.SomPerceptionCoordinator
import me.fleey.futon.domain.som.models.SomAnnotation
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.RootType
import org.koin.android.annotation.KoinViewModel

data class SomTestResult(
  val annotatedBitmap: Bitmap,
  val annotation: SomAnnotation,
  val captureLatencyMs: Long,
  val annotationLatencyMs: Long,
)

data class ModelDisplayInfo(
  val name: String,
  val description: String?,
  val status: ModelDeploymentStatus,
  val sizeBytes: Long,
  val required: Boolean,
)

enum class RootStatus {
  CHECKING,
  AVAILABLE,
  UNAVAILABLE,
  DENIED
}

data class SomSettingsUiState(
  val isLoading: Boolean = true,

  val somEnabled: Boolean = true,
  val renderAnnotations: Boolean = true,
  val showBoundingBoxes: Boolean = true,
  val markerSize: Int = 24,
  val maxElements: Int = 50,
  val screenshotQuality: Int = 85,

  val perceptionMode: PerceptionModeConfig = PerceptionModeConfig.HYBRID,
  val includeUITree: Boolean = true,
  val uiTreeMaxDepth: Int = HybridPerceptionSettings.DEFAULT_UI_TREE_MAX_DEPTH,
  val includeNonInteractive: Boolean = false,
  val perceptionTimeoutMs: Long = HybridPerceptionSettings.DEFAULT_TIMEOUT_MS,

  val minConfidence: Float = 0.3f,
  val filterSmallElements: Boolean = true,
  val minElementArea: Int = 100,
  val mergeIouThreshold: Float = 0.7f,

  val downscaleFactor: Int = DspPerceptionSettings.DEFAULT_DOWNSCALE_FACTOR,
  val targetLatencyMs: Long = DspPerceptionSettings.DEFAULT_TARGET_LATENCY_MS,
  val maxConcurrentBuffers: Int = DspPerceptionSettings.DEFAULT_MAX_BUFFERS,
  val enableOcr: Boolean = true,

  val adaptiveLearningEnabled: Boolean = true,
  val maxCacheSize: Int = HybridPerceptionSettings.DEFAULT_MAX_CACHE_SIZE,
  val cacheSize: Int = 0,
  val isClearingCache: Boolean = false,

  val rootStatus: RootStatus = RootStatus.CHECKING,
  val rootType: RootType = RootType.NONE,

  val hardwareCapabilities: HardwareCapabilities? = null,
  val metrics: PerceptionMetrics? = null,

  val models: List<ModelDisplayInfo> = emptyList(),
  val isDeploying: Boolean = false,
  val isVerifying: Boolean = false,
  val hasModelWarning: Boolean = false,

  val isTesting: Boolean = false,
  val testResult: SomTestResult? = null,
  val showTestDialog: Boolean = false,

  val errorMessage: String? = null,
  val successMessage: String? = null,
) {
  val perceptionTimeoutSeconds: Float
    get() = perceptionTimeoutMs / 1000f

  val isUITreeSettingsEnabled: Boolean
    get() = somEnabled && rootStatus == RootStatus.AVAILABLE &&
      perceptionMode != PerceptionModeConfig.SCREENSHOT_ONLY
}

sealed interface SomSettingsUiEvent {
  // SoM Core
  data class SomEnabledChanged(val enabled: Boolean) : SomSettingsUiEvent
  data class RenderAnnotationsChanged(val enabled: Boolean) : SomSettingsUiEvent
  data class ShowBoundingBoxesChanged(val enabled: Boolean) : SomSettingsUiEvent
  data class MarkerSizeChanged(val size: Int) : SomSettingsUiEvent
  data class MaxElementsChanged(val maxElements: Int) : SomSettingsUiEvent
  data class ScreenshotQualityChanged(val quality: Int) : SomSettingsUiEvent

  // Perception Mode
  data class PerceptionModeChanged(val mode: PerceptionModeConfig) : SomSettingsUiEvent
  data class IncludeUITreeChanged(val enabled: Boolean) : SomSettingsUiEvent
  data class UITreeMaxDepthChanged(val depth: Int) : SomSettingsUiEvent
  data class IncludeNonInteractiveChanged(val include: Boolean) : SomSettingsUiEvent
  data class PerceptionTimeoutChanged(val seconds: Float) : SomSettingsUiEvent

  // Detection
  data class MinConfidenceChanged(val confidence: Float) : SomSettingsUiEvent
  data class FilterSmallElementsChanged(val enabled: Boolean) : SomSettingsUiEvent
  data class MinElementAreaChanged(val area: Int) : SomSettingsUiEvent
  data class MergeIouThresholdChanged(val threshold: Float) : SomSettingsUiEvent

  // Performance
  data class DownscaleFactorChanged(val factor: Int) : SomSettingsUiEvent
  data class TargetLatencyChanged(val latencyMs: Long) : SomSettingsUiEvent
  data class MaxBuffersChanged(val buffers: Int) : SomSettingsUiEvent
  data class OcrEnabledChanged(val enabled: Boolean) : SomSettingsUiEvent

  // Learning & Cache
  data class AdaptiveLearningChanged(val enabled: Boolean) : SomSettingsUiEvent
  data object ClearCache : SomSettingsUiEvent

  // Model Management
  data object RedeployModels : SomSettingsUiEvent
  data object VerifyModels : SomSettingsUiEvent

  // Actions
  data object ResetToDefaults : SomSettingsUiEvent
  data object RunTest : SomSettingsUiEvent
  data object DismissTestDialog : SomSettingsUiEvent
  data object RefreshRootStatus : SomSettingsUiEvent
  data object DismissError : SomSettingsUiEvent
  data object DismissSuccess : SomSettingsUiEvent
}

@KoinViewModel
class SomSettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val somPerceptionCoordinator: SomPerceptionCoordinator,
  private val rootShell: RootShell,
  private val elementCacheRepository: ElementCacheRepository,
  private val hardwareDetector: HardwareDetector,
  private val performanceMonitor: PerformanceMonitor,
  private val modelManager: ModelManager,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SomSettingsUiState())
  val uiState: StateFlow<SomSettingsUiState> = _uiState.asStateFlow()

  private var manifest: ModelManifest? = null

  init {
    loadAllSettings()
    checkRootStatus()
    loadCacheSize()
    loadHardwareInfo()
    observeMetrics()
    loadModels()
    observeDeploymentState()
  }

  private fun loadAllSettings() {
    viewModelScope.launch {
      try {
        val somSettings = settingsRepository.getSomSettings()
        val hybridSettings = settingsRepository.getHybridPerceptionSettings()
        val dspSettings = settingsRepository.getDspPerceptionSettings()

        _uiState.update {
          it.copy(
            isLoading = false,
            // SoM settings
            somEnabled = somSettings.enabled,
            renderAnnotations = somSettings.renderAnnotations,
            showBoundingBoxes = somSettings.showBoundingBoxes,
            markerSize = somSettings.markerSize,
            maxElements = somSettings.maxElements,
            screenshotQuality = somSettings.screenshotQuality,
            includeUITree = somSettings.includeUITree,
            minConfidence = somSettings.minConfidence,
            filterSmallElements = somSettings.filterSmallElements,
            minElementArea = somSettings.minElementArea,
            mergeIouThreshold = somSettings.mergeIouThreshold,
            // Hybrid perception settings
            perceptionMode = hybridSettings.perceptionMode,
            uiTreeMaxDepth = hybridSettings.uiTreeMaxDepth,
            includeNonInteractive = hybridSettings.includeNonInteractive,
            perceptionTimeoutMs = hybridSettings.perceptionTimeoutMs,
            adaptiveLearningEnabled = hybridSettings.adaptiveLearningEnabled,
            maxCacheSize = hybridSettings.maxCacheSize,
            // DSP perception settings
            downscaleFactor = dspSettings.downscaleFactor,
            targetLatencyMs = dspSettings.targetLatencyMs,
            maxConcurrentBuffers = dspSettings.maxConcurrentBuffers,
            enableOcr = dspSettings.enableOcr,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, errorMessage = e.message)
        }
      }
    }
  }

  private fun checkRootStatus() {
    viewModelScope.launch {
      _uiState.update { it.copy(rootStatus = RootStatus.CHECKING) }
      try {
        val isAvailable = rootShell.isRootAvailable()
        val rootType = rootShell.getRootType()
        val status = when {
          !isAvailable -> RootStatus.UNAVAILABLE
          rootType == RootType.NONE -> RootStatus.UNAVAILABLE
          else -> RootStatus.AVAILABLE
        }
        _uiState.update { it.copy(rootStatus = status, rootType = rootType) }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(rootStatus = RootStatus.UNAVAILABLE, rootType = RootType.NONE)
        }
      }
    }
  }

  private fun loadCacheSize() {
    viewModelScope.launch {
      try {
        val size = elementCacheRepository.size()
        _uiState.update { it.copy(cacheSize = size) }
      } catch (_: Exception) {
      }
    }
  }

  private fun loadHardwareInfo() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val capabilities = hardwareDetector.detectCapabilities()
        _uiState.update { it.copy(hardwareCapabilities = capabilities) }
      } catch (_: Exception) {
      }
    }
  }

  private fun observeMetrics() {
    viewModelScope.launch {
      performanceMonitor.observeMetrics().collect { metrics ->
        _uiState.update { it.copy(metrics = metrics) }
      }
    }
  }

  private fun loadModels() {
    viewModelScope.launch {
      modelManager.loadManifest()
        .onSuccess { loadedManifest ->
          manifest = loadedManifest
          updateModelList(loadedManifest, modelManager.deploymentState.value)
        }
        .onFailure { error ->
          _uiState.update { it.copy(errorMessage = error.message) }
        }
    }
  }

  private fun observeDeploymentState() {
    viewModelScope.launch {
      modelManager.deploymentState.collect { state ->
        manifest?.let { m -> updateModelList(m, state) }
        _uiState.update {
          it.copy(
            isDeploying = state.isDeploying,
            hasModelWarning = state.hasCorruptedModels || state.hasFailedModels ||
              !state.requiredModelsDeployed,
          )
        }
      }
    }
  }

  private fun updateModelList(manifest: ModelManifest, state: ModelDeploymentState) {
    val models = manifest.models.map { metadata ->
      ModelDisplayInfo(
        name = metadata.name,
        description = metadata.description,
        status = state.models[metadata.name] ?: ModelDeploymentStatus.NotDeployed,
        sizeBytes = metadata.sizeBytes,
        required = metadata.required,
      )
    }
    _uiState.update { it.copy(models = models) }
  }

  fun onEvent(event: SomSettingsUiEvent) {
    when (event) {
      is SomSettingsUiEvent.SomEnabledChanged -> updateSomEnabled(event.enabled)
      is SomSettingsUiEvent.RenderAnnotationsChanged -> updateRenderAnnotations(event.enabled)
      is SomSettingsUiEvent.ShowBoundingBoxesChanged -> updateShowBoundingBoxes(event.enabled)
      is SomSettingsUiEvent.MarkerSizeChanged -> updateMarkerSize(event.size)
      is SomSettingsUiEvent.MaxElementsChanged -> updateMaxElements(event.maxElements)
      is SomSettingsUiEvent.ScreenshotQualityChanged -> updateScreenshotQuality(event.quality)

      is SomSettingsUiEvent.PerceptionModeChanged -> updatePerceptionMode(event.mode)
      is SomSettingsUiEvent.IncludeUITreeChanged -> updateIncludeUITree(event.enabled)
      is SomSettingsUiEvent.UITreeMaxDepthChanged -> updateUITreeMaxDepth(event.depth)
      is SomSettingsUiEvent.IncludeNonInteractiveChanged -> updateIncludeNonInteractive(event.include)
      is SomSettingsUiEvent.PerceptionTimeoutChanged -> updatePerceptionTimeout(event.seconds)

      is SomSettingsUiEvent.MinConfidenceChanged -> updateMinConfidence(event.confidence)
      is SomSettingsUiEvent.FilterSmallElementsChanged -> updateFilterSmallElements(event.enabled)
      is SomSettingsUiEvent.MinElementAreaChanged -> updateMinElementArea(event.area)
      is SomSettingsUiEvent.MergeIouThresholdChanged -> updateMergeIouThreshold(event.threshold)

      is SomSettingsUiEvent.DownscaleFactorChanged -> updateDownscaleFactor(event.factor)
      is SomSettingsUiEvent.TargetLatencyChanged -> updateTargetLatency(event.latencyMs)
      is SomSettingsUiEvent.MaxBuffersChanged -> updateMaxBuffers(event.buffers)
      is SomSettingsUiEvent.OcrEnabledChanged -> updateOcrEnabled(event.enabled)

      is SomSettingsUiEvent.AdaptiveLearningChanged -> updateAdaptiveLearning(event.enabled)
      SomSettingsUiEvent.ClearCache -> clearCache()

      SomSettingsUiEvent.RedeployModels -> redeployModels()
      SomSettingsUiEvent.VerifyModels -> verifyModels()

      SomSettingsUiEvent.ResetToDefaults -> resetToDefaults()
      SomSettingsUiEvent.RunTest -> runTest()
      SomSettingsUiEvent.DismissTestDialog -> dismissTestDialog()
      SomSettingsUiEvent.RefreshRootStatus -> checkRootStatus()
      SomSettingsUiEvent.DismissError -> _uiState.update { it.copy(errorMessage = null) }
      SomSettingsUiEvent.DismissSuccess -> _uiState.update { it.copy(successMessage = null) }
    }
  }

  private fun updateSomEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.setSomEnabled(enabled)
      _uiState.update { it.copy(somEnabled = enabled) }
    }
  }

  private fun updateRenderAnnotations(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.setSomRenderAnnotations(enabled)
      _uiState.update { it.copy(renderAnnotations = enabled) }
    }
  }

  private fun updateShowBoundingBoxes(enabled: Boolean) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(showBoundingBoxes = enabled))
      _uiState.update { it.copy(showBoundingBoxes = enabled) }
    }
  }

  private fun updateMarkerSize(size: Int) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(markerSize = size))
      _uiState.update { it.copy(markerSize = size) }
    }
  }

  private fun updateMaxElements(maxElements: Int) {
    viewModelScope.launch {
      settingsRepository.setSomMaxElements(maxElements)
      _uiState.update { it.copy(maxElements = maxElements) }
    }
  }

  private fun updateScreenshotQuality(quality: Int) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(screenshotQuality = quality))
      _uiState.update { it.copy(screenshotQuality = quality) }
    }
  }

  private fun updatePerceptionMode(mode: PerceptionModeConfig) {
    val state = _uiState.value
    if (mode != PerceptionModeConfig.SCREENSHOT_ONLY && state.rootStatus != RootStatus.AVAILABLE) {
      _uiState.update { it.copy(errorMessage = "Root permission required for UI tree mode") }
      return
    }
    viewModelScope.launch {
      settingsRepository.setPerceptionMode(mode)
      _uiState.update { it.copy(perceptionMode = mode) }
    }
  }

  private fun updateIncludeUITree(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.setSomIncludeUITree(enabled)
      _uiState.update { it.copy(includeUITree = enabled) }
    }
  }

  private fun updateUITreeMaxDepth(depth: Int) {
    viewModelScope.launch {
      settingsRepository.setUITreeMaxDepth(depth)
      _uiState.update { it.copy(uiTreeMaxDepth = depth) }
    }
  }

  private fun updateIncludeNonInteractive(include: Boolean) {
    viewModelScope.launch {
      settingsRepository.setIncludeNonInteractive(include)
      _uiState.update { it.copy(includeNonInteractive = include) }
    }
  }

  private fun updatePerceptionTimeout(seconds: Float) {
    val timeoutMs = (seconds * 1000).toLong()
    viewModelScope.launch {
      settingsRepository.setPerceptionTimeout(timeoutMs)
      _uiState.update { it.copy(perceptionTimeoutMs = timeoutMs) }
    }
  }

  private fun updateMinConfidence(confidence: Float) {
    viewModelScope.launch {
      settingsRepository.setSomMinConfidence(confidence)
      settingsRepository.setMinConfidence(confidence)
      _uiState.update { it.copy(minConfidence = confidence) }
    }
  }

  private fun updateFilterSmallElements(enabled: Boolean) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(filterSmallElements = enabled))
      _uiState.update { it.copy(filterSmallElements = enabled) }
    }
  }

  private fun updateMinElementArea(area: Int) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(minElementArea = area))
      _uiState.update { it.copy(minElementArea = area) }
    }
  }

  private fun updateMergeIouThreshold(threshold: Float) {
    viewModelScope.launch {
      val settings = settingsRepository.getSomSettings()
      settingsRepository.updateSomSettings(settings.copy(mergeIouThreshold = threshold))
      _uiState.update { it.copy(mergeIouThreshold = threshold) }
    }
  }

  private fun updateDownscaleFactor(factor: Int) {
    viewModelScope.launch {
      settingsRepository.setDownscaleFactor(factor)
      _uiState.update { it.copy(downscaleFactor = factor) }
    }
  }

  private fun updateTargetLatency(latencyMs: Long) {
    viewModelScope.launch {
      settingsRepository.setTargetLatency(latencyMs)
      _uiState.update { it.copy(targetLatencyMs = latencyMs) }
    }
  }

  private fun updateMaxBuffers(buffers: Int) {
    viewModelScope.launch {
      settingsRepository.setMaxConcurrentBuffers(buffers)
      _uiState.update { it.copy(maxConcurrentBuffers = buffers) }
    }
  }

  private fun updateOcrEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.setOcrEnabled(enabled)
      _uiState.update { it.copy(enableOcr = enabled) }
    }
  }

  private fun updateAdaptiveLearning(enabled: Boolean) {
    viewModelScope.launch {
      settingsRepository.setAdaptiveLearningEnabled(enabled)
      _uiState.update { it.copy(adaptiveLearningEnabled = enabled) }
    }
  }

  private fun clearCache() {
    viewModelScope.launch {
      _uiState.update { it.copy(isClearingCache = true) }
      try {
        elementCacheRepository.clearAll()
        _uiState.update {
          it.copy(isClearingCache = false, cacheSize = 0, successMessage = "Cache cleared")
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isClearingCache = false, errorMessage = "Failed to clear cache: ${e.message}")
        }
      }
    }
  }

  private fun redeployModels() {
    viewModelScope.launch {
      _uiState.update { it.copy(isDeploying = true, errorMessage = null) }
      modelManager.redeployCorruptedModels()
        .onSuccess {
          _uiState.update { it.copy(isDeploying = false, successMessage = "Models re-deployed") }
        }
        .onFailure { error ->
          _uiState.update { it.copy(isDeploying = false, errorMessage = error.message) }
        }
    }
  }

  private fun verifyModels() {
    viewModelScope.launch {
      _uiState.update { it.copy(isVerifying = true, errorMessage = null) }
      modelManager.verifyAllModels()
        .onSuccess { results ->
          val allValid = results.values.all { it }
          val message = if (allValid) "All models verified" else "${results.values.count { !it }} model(s) failed"
          _uiState.update {
            it.copy(
              isVerifying = false,
              successMessage = if (allValid) message else null,
              errorMessage = if (!allValid) message else null,
            )
          }
        }
        .onFailure { error ->
          _uiState.update { it.copy(isVerifying = false, errorMessage = error.message) }
        }
    }
  }

  // === Actions ===
  private fun resetToDefaults() {
    viewModelScope.launch {
      settingsRepository.updateSomSettings(SomSettings.DEFAULT)
      loadAllSettings()
      _uiState.update { it.copy(successMessage = "Settings reset to defaults") }
    }
  }

  private fun runTest() {
    viewModelScope.launch {
      _uiState.update { it.copy(isTesting = true, errorMessage = null) }
      val state = _uiState.value
      when (val result = somPerceptionCoordinator.capture(
        includeUITree = state.includeUITree,
        renderAnnotations = true,
        screenshotQuality = state.screenshotQuality,
      )) {
        is SomCaptureResult.Success -> {
          val bitmap = base64ToBitmap(result.annotatedScreenshot)
          if (bitmap != null) {
            _uiState.update {
              it.copy(
                isTesting = false,
                testResult = SomTestResult(
                  annotatedBitmap = bitmap,
                  annotation = result.annotation,
                  captureLatencyMs = result.captureLatencyMs,
                  annotationLatencyMs = result.annotationLatencyMs,
                ),
                showTestDialog = true,
              )
            }
          } else {
            _uiState.update { it.copy(isTesting = false, errorMessage = "Failed to decode screenshot") }
          }
        }

        is SomCaptureResult.Failure -> {
          _uiState.update { it.copy(isTesting = false, errorMessage = result.reason) }
        }
      }
    }
  }

  private fun dismissTestDialog() {
    _uiState.update {
      it.testResult?.annotatedBitmap?.recycle()
      it.copy(showTestDialog = false, testResult = null)
    }
  }

  private fun base64ToBitmap(base64: String): Bitmap? {
    return try {
      val bytes = Base64.decode(base64, Base64.NO_WRAP)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
      null
    }
  }
}
