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
package me.fleey.futon.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.data.settings.models.AISettings
import me.fleey.futon.data.settings.models.AppLanguage
import me.fleey.futon.data.settings.models.DspPerceptionSettings
import me.fleey.futon.data.settings.models.ExecutionSettings
import me.fleey.futon.data.settings.models.HybridPerceptionSettings
import me.fleey.futon.data.settings.models.PerceptionModeConfig
import me.fleey.futon.data.settings.models.ScreenshotQuality
import me.fleey.futon.data.settings.models.SomSettings
import me.fleey.futon.data.settings.models.ThemeMode
import me.fleey.futon.data.settings.models.ThemePreferences
import me.fleey.futon.platform.input.models.InjectionMode
import me.fleey.futon.platform.input.models.InputMethod
import org.koin.core.annotation.Single

@Single(binds = [SettingsRepository::class])
class SettingsRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

  override fun getSettingsFlow(): Flow<AISettings> = dataStore.data.map { prefs ->
    val screenshotQuality = prefs[SCREENSHOT_QUALITY_KEY]?.let {
      try {
        ScreenshotQuality.valueOf(it)
      } catch (e: Exception) {
        ScreenshotQuality.MEDIUM
      }
    } ?: ScreenshotQuality.MEDIUM

    AISettings(
      systemPrompt = prefs[SYSTEM_PROMPT_KEY] ?: AISettings.DEFAULT_SYSTEM_PROMPT,
      maxSteps = prefs[MAX_STEPS_KEY] ?: AISettings().maxSteps,
      stepDelayMs = prefs[STEP_DELAY_KEY] ?: AISettings().stepDelayMs,
      requestTimeoutMs = prefs[REQUEST_TIMEOUT_KEY] ?: AISettings.DEFAULT_TIMEOUT_MS,
      maxTokens = prefs[MAX_TOKENS_KEY] ?: AISettings.DEFAULT_MAX_TOKENS,
      screenshotQuality = screenshotQuality,
      maxRetries = prefs[MAX_RETRIES_KEY] ?: AISettings().maxRetries,
    )
  }

  override suspend fun getSettings(): AISettings = getSettingsFlow().first()

  override suspend fun updateSettings(settings: AISettings) {
    dataStore.edit { prefs ->
      prefs[SYSTEM_PROMPT_KEY] = settings.systemPrompt
      prefs[MAX_STEPS_KEY] = settings.maxSteps
      prefs[STEP_DELAY_KEY] = settings.stepDelayMs
      prefs[REQUEST_TIMEOUT_KEY] = settings.requestTimeoutMs
      prefs[MAX_TOKENS_KEY] = settings.maxTokens
      prefs[SCREENSHOT_QUALITY_KEY] = settings.screenshotQuality.name
      prefs[MAX_RETRIES_KEY] = settings.maxRetries
    }
  }

  override suspend fun clearSettings() {
    dataStore.edit { it.clear() }
  }

  override fun getThemePreferencesFlow(): Flow<ThemePreferences> = dataStore.data.map { prefs ->
    val themeModeStr = prefs[THEME_MODE_KEY]
    val themeMode = themeModeStr?.let {
      try {
        ThemeMode.valueOf(it)
      } catch (e: Exception) {
        ThemeMode.SYSTEM
      }
    } ?: ThemeMode.SYSTEM

    val dynamicColorEnabled = prefs[DYNAMIC_COLOR_ENABLED_KEY] ?: true

    val appLanguageStr = prefs[APP_LANGUAGE_KEY]
    val appLanguage = appLanguageStr?.let {
      try {
        AppLanguage.valueOf(it)
      } catch (e: Exception) {
        AppLanguage.SYSTEM
      }
    } ?: AppLanguage.SYSTEM

    ThemePreferences(
      themeMode = themeMode,
      dynamicColorEnabled = dynamicColorEnabled,
      appLanguage = appLanguage,
    )
  }

  override suspend fun getThemePreferences(): ThemePreferences = getThemePreferencesFlow().first()

  override suspend fun setThemeMode(mode: ThemeMode) {
    dataStore.edit { prefs ->
      prefs[THEME_MODE_KEY] = mode.name
    }
  }

  override suspend fun setDynamicColorEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[DYNAMIC_COLOR_ENABLED_KEY] = enabled
    }
  }

  override suspend fun setAppLanguage(language: AppLanguage) {
    dataStore.edit { prefs ->
      prefs[APP_LANGUAGE_KEY] = language.name
    }
  }

  override fun getHybridPerceptionSettingsFlow(): Flow<HybridPerceptionSettings> =
    dataStore.data.map { prefs ->
      val perceptionModeStr = prefs[PERCEPTION_MODE_KEY]
      val perceptionMode = perceptionModeStr?.let {
        try {
          PerceptionModeConfig.valueOf(it)
        } catch (e: Exception) {
          PerceptionModeConfig.HYBRID
        }
      } ?: PerceptionModeConfig.HYBRID

      HybridPerceptionSettings(
        enabled = prefs[HYBRID_PERCEPTION_ENABLED_KEY] ?: false,
        perceptionMode = perceptionMode,
        perceptionTimeoutMs = prefs[PERCEPTION_TIMEOUT_KEY]
          ?: HybridPerceptionSettings.Companion.DEFAULT_TIMEOUT_MS,
        adaptiveLearningEnabled = prefs[ADAPTIVE_LEARNING_ENABLED_KEY] ?: true,
        maxCacheSize = prefs[MAX_CACHE_SIZE_KEY]
          ?: HybridPerceptionSettings.Companion.DEFAULT_MAX_CACHE_SIZE,
        uiTreeMaxDepth = prefs[UI_TREE_MAX_DEPTH_KEY]
          ?: HybridPerceptionSettings.Companion.DEFAULT_UI_TREE_MAX_DEPTH,
        includeNonInteractive = prefs[INCLUDE_NON_INTERACTIVE_KEY] ?: false,
      )
    }

  override suspend fun getHybridPerceptionSettings(): HybridPerceptionSettings =
    getHybridPerceptionSettingsFlow().first()

  override suspend fun updateHybridPerceptionSettings(settings: HybridPerceptionSettings) {
    val validSettings = settings.withValidTimeout().withValidCacheSize()

    dataStore.edit { prefs ->
      prefs[HYBRID_PERCEPTION_ENABLED_KEY] = validSettings.enabled
      prefs[PERCEPTION_MODE_KEY] = validSettings.perceptionMode.name
      prefs[PERCEPTION_TIMEOUT_KEY] = validSettings.perceptionTimeoutMs
      prefs[ADAPTIVE_LEARNING_ENABLED_KEY] = validSettings.adaptiveLearningEnabled
      prefs[MAX_CACHE_SIZE_KEY] = validSettings.maxCacheSize
    }
  }

  override suspend fun setHybridPerceptionEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[HYBRID_PERCEPTION_ENABLED_KEY] = enabled
    }
  }

  override suspend fun setPerceptionTimeout(timeoutMs: Long) {
    val clampedTimeout = timeoutMs.coerceIn(
      HybridPerceptionSettings.Companion.MIN_TIMEOUT_MS,
      HybridPerceptionSettings.Companion.MAX_TIMEOUT_MS,
    )
    dataStore.edit { prefs ->
      prefs[PERCEPTION_TIMEOUT_KEY] = clampedTimeout
    }
  }

  override suspend fun setAdaptiveLearningEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[ADAPTIVE_LEARNING_ENABLED_KEY] = enabled
    }
  }

  override suspend fun setPerceptionMode(mode: PerceptionModeConfig) {
    dataStore.edit { prefs ->
      prefs[PERCEPTION_MODE_KEY] = mode.name
    }
  }

  override suspend fun setUITreeMaxDepth(depth: Int) {
    val clampedDepth = depth.coerceIn(
      HybridPerceptionSettings.Companion.MIN_UI_TREE_DEPTH,
      HybridPerceptionSettings.Companion.MAX_UI_TREE_DEPTH,
    )
    dataStore.edit { prefs ->
      prefs[UI_TREE_MAX_DEPTH_KEY] = clampedDepth
    }
  }

  override suspend fun setIncludeNonInteractive(include: Boolean) {
    dataStore.edit { prefs ->
      prefs[INCLUDE_NON_INTERACTIVE_KEY] = include
    }
  }

  override fun getExecutionSettingsFlow(): Flow<ExecutionSettings> = dataStore.data.map { prefs ->
    val preferredInputMethodStr = prefs[PREFERRED_INPUT_METHOD_KEY]
    val preferredInputMethod = preferredInputMethodStr?.let {
      try {
        InputMethod.valueOf(it)
      } catch (e: Exception) {
        null
      }
    }

    val preferredCaptureMethodStr = prefs[PREFERRED_CAPTURE_METHOD_KEY]
    val preferredCaptureMethod = preferredCaptureMethodStr?.let {
      try {
        CaptureMethod.valueOf(it)
      } catch (e: Exception) {
        null
      }
    }

    val privacyModeStr = prefs[PRIVACY_MODE_KEY]
    val privacyMode = privacyModeStr?.let {
      try {
        PrivacyMode.valueOf(it)
      } catch (e: Exception) {
        PrivacyMode.STRICT
      }
    } ?: PrivacyMode.STRICT

    ExecutionSettings(
      preferredInputMethod = preferredInputMethod,
      preferredCaptureMethod = preferredCaptureMethod,
      enableFallback = prefs[ENABLE_FALLBACK_KEY] ?: true,
      privacyMode = privacyMode,
      auditLogEnabled = prefs[AUDIT_LOG_ENABLED_KEY] ?: true,
      showCapabilityWarnings = prefs[SHOW_CAPABILITY_WARNINGS_KEY] ?: true,
    )
  }

  override suspend fun getExecutionSettings(): ExecutionSettings =
    getExecutionSettingsFlow().first()

  override suspend fun updateExecutionSettings(settings: ExecutionSettings) {
    dataStore.edit { prefs ->
      if (settings.preferredInputMethod != null) {
        prefs[PREFERRED_INPUT_METHOD_KEY] = settings.preferredInputMethod.name
      } else {
        prefs.remove(PREFERRED_INPUT_METHOD_KEY)
      }

      if (settings.preferredCaptureMethod != null) {
        prefs[PREFERRED_CAPTURE_METHOD_KEY] = settings.preferredCaptureMethod.name
      } else {
        prefs.remove(PREFERRED_CAPTURE_METHOD_KEY)
      }

      prefs[ENABLE_FALLBACK_KEY] = settings.enableFallback
      prefs[PRIVACY_MODE_KEY] = settings.privacyMode.name
      prefs[AUDIT_LOG_ENABLED_KEY] = settings.auditLogEnabled
      prefs[SHOW_CAPABILITY_WARNINGS_KEY] = settings.showCapabilityWarnings
    }
  }

  override suspend fun setPreferredInputMethod(method: InputMethod?) {
    dataStore.edit { prefs ->
      if (method != null) {
        prefs[PREFERRED_INPUT_METHOD_KEY] = method.name
      } else {
        prefs.remove(PREFERRED_INPUT_METHOD_KEY)
      }
    }
  }

  override suspend fun setPreferredCaptureMethod(method: CaptureMethod?) {
    dataStore.edit { prefs ->
      if (method != null) {
        prefs[PREFERRED_CAPTURE_METHOD_KEY] = method.name
      } else {
        prefs.remove(PREFERRED_CAPTURE_METHOD_KEY)
      }
    }
  }

  override suspend fun setEnableFallback(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[ENABLE_FALLBACK_KEY] = enabled
    }
  }

  override suspend fun setPrivacyMode(mode: PrivacyMode) {
    dataStore.edit { prefs ->
      prefs[PRIVACY_MODE_KEY] = mode.name
    }
  }

  override suspend fun setAuditLogEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[AUDIT_LOG_ENABLED_KEY] = enabled
    }
  }

  override suspend fun setShowCapabilityWarnings(show: Boolean) {
    dataStore.edit { prefs ->
      prefs[SHOW_CAPABILITY_WARNINGS_KEY] = show
    }
  }

  override fun getDspPerceptionSettingsFlow(): Flow<DspPerceptionSettings> =
    dataStore.data.map { prefs ->
      val injectionModeStr = prefs[DSP_INJECTION_MODE_KEY]
      val injectionMode = injectionModeStr?.let {
        try {
          InjectionMode.valueOf(it)
        } catch (e: Exception) {
          InjectionMode.ROOT_UINPUT
        }
      } ?: InjectionMode.ROOT_UINPUT

      DspPerceptionSettings(
        enabled = prefs[DSP_ENABLED_KEY] ?: true,
        injectionMode = injectionMode,
        downscaleFactor = prefs[DSP_DOWNSCALE_FACTOR_KEY]
          ?: DspPerceptionSettings.DEFAULT_DOWNSCALE_FACTOR,
        targetLatencyMs = prefs[DSP_TARGET_LATENCY_KEY]
          ?: DspPerceptionSettings.DEFAULT_TARGET_LATENCY_MS,
        enableOcr = prefs[DSP_ENABLE_OCR_KEY] ?: true,
        minConfidence = prefs[DSP_MIN_CONFIDENCE_KEY]?.let { it / 100f }
          ?: DspPerceptionSettings.DEFAULT_MIN_CONFIDENCE,
        maxConcurrentBuffers = prefs[DSP_MAX_BUFFERS_KEY]
          ?: DspPerceptionSettings.DEFAULT_MAX_BUFFERS,
        touchDevicePath = prefs[DSP_TOUCH_DEVICE_PATH_KEY]
          ?: DspPerceptionSettings.AUTO_DETECT_PATH,
      )
    }

  override suspend fun getDspPerceptionSettings(): DspPerceptionSettings =
    getDspPerceptionSettingsFlow().first()

  override suspend fun updateDspPerceptionSettings(settings: DspPerceptionSettings) {
    val validSettings = settings
      .withValidDownscale()
      .withValidLatency()
      .withValidConfidence()
      .withValidBuffers()

    dataStore.edit { prefs ->
      prefs[DSP_ENABLED_KEY] = validSettings.enabled
      prefs[DSP_INJECTION_MODE_KEY] = validSettings.injectionMode.name
      prefs[DSP_DOWNSCALE_FACTOR_KEY] = validSettings.downscaleFactor
      prefs[DSP_TARGET_LATENCY_KEY] = validSettings.targetLatencyMs
      prefs[DSP_ENABLE_OCR_KEY] = validSettings.enableOcr
      prefs[DSP_MIN_CONFIDENCE_KEY] = (validSettings.minConfidence * 100).toInt()
      prefs[DSP_MAX_BUFFERS_KEY] = validSettings.maxConcurrentBuffers
      prefs[DSP_TOUCH_DEVICE_PATH_KEY] = validSettings.touchDevicePath
    }
  }

  override suspend fun setInjectionMode(mode: InjectionMode) {
    dataStore.edit { prefs ->
      prefs[DSP_INJECTION_MODE_KEY] = mode.name
    }
  }

  override suspend fun setDownscaleFactor(factor: Int) {
    val clampedFactor = factor.coerceIn(
      DspPerceptionSettings.MIN_DOWNSCALE,
      DspPerceptionSettings.MAX_DOWNSCALE,
    )
    dataStore.edit { prefs ->
      prefs[DSP_DOWNSCALE_FACTOR_KEY] = clampedFactor
    }
  }

  override suspend fun setTargetLatency(latencyMs: Long) {
    val clampedLatency = latencyMs.coerceIn(
      DspPerceptionSettings.MIN_LATENCY_MS,
      DspPerceptionSettings.MAX_LATENCY_MS,
    )
    dataStore.edit { prefs ->
      prefs[DSP_TARGET_LATENCY_KEY] = clampedLatency
    }
  }

  override suspend fun setOcrEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[DSP_ENABLE_OCR_KEY] = enabled
    }
  }

  override suspend fun setMinConfidence(confidence: Float) {
    val clampedConfidence = confidence.coerceIn(0f, 1f)
    dataStore.edit { prefs ->
      prefs[DSP_MIN_CONFIDENCE_KEY] = (clampedConfidence * 100).toInt()
    }
  }

  override suspend fun setMaxConcurrentBuffers(buffers: Int) {
    val clampedBuffers = buffers.coerceIn(
      DspPerceptionSettings.MIN_BUFFERS,
      DspPerceptionSettings.MAX_BUFFERS,
    )
    dataStore.edit { prefs ->
      prefs[DSP_MAX_BUFFERS_KEY] = clampedBuffers
    }
  }

  override suspend fun setTouchDevicePath(path: String) {
    dataStore.edit { prefs ->
      prefs[DSP_TOUCH_DEVICE_PATH_KEY] = path
    }
  }

  override fun getSomSettingsFlow(): Flow<SomSettings> = dataStore.data.map { prefs ->
    SomSettings(
      enabled = prefs[SOM_ENABLED_KEY] ?: SomSettings.DEFAULT.enabled,
      renderAnnotations = prefs[SOM_RENDER_ANNOTATIONS_KEY] ?: SomSettings.DEFAULT.renderAnnotations,
      includeUITree = prefs[SOM_INCLUDE_UI_TREE_KEY] ?: SomSettings.DEFAULT.includeUITree,
      minConfidence = prefs[SOM_MIN_CONFIDENCE_KEY]?.let { it / 100f }
        ?: SomSettings.DEFAULT.minConfidence,
      maxElements = prefs[SOM_MAX_ELEMENTS_KEY] ?: SomSettings.DEFAULT.maxElements,
      markerSize = prefs[SOM_MARKER_SIZE_KEY] ?: SomSettings.DEFAULT.markerSize,
      showBoundingBoxes = prefs[SOM_SHOW_BOUNDING_BOXES_KEY] ?: SomSettings.DEFAULT.showBoundingBoxes,
      screenshotQuality = prefs[SOM_SCREENSHOT_QUALITY_KEY] ?: SomSettings.DEFAULT.screenshotQuality,
      filterSmallElements = prefs[SOM_FILTER_SMALL_ELEMENTS_KEY]
        ?: SomSettings.DEFAULT.filterSmallElements,
      minElementArea = prefs[SOM_MIN_ELEMENT_AREA_KEY] ?: SomSettings.DEFAULT.minElementArea,
      mergeIouThreshold = prefs[SOM_MERGE_IOU_THRESHOLD_KEY]?.let { it / 100f }
        ?: SomSettings.DEFAULT.mergeIouThreshold,
    )
  }

  override suspend fun getSomSettings(): SomSettings = getSomSettingsFlow().first()

  override suspend fun updateSomSettings(settings: SomSettings) {
    dataStore.edit { prefs ->
      prefs[SOM_ENABLED_KEY] = settings.enabled
      prefs[SOM_RENDER_ANNOTATIONS_KEY] = settings.renderAnnotations
      prefs[SOM_INCLUDE_UI_TREE_KEY] = settings.includeUITree
      prefs[SOM_MIN_CONFIDENCE_KEY] = (settings.minConfidence.coerceIn(0f, 1f) * 100).toInt()
      prefs[SOM_MAX_ELEMENTS_KEY] = settings.maxElements.coerceIn(1, 100)
      prefs[SOM_MARKER_SIZE_KEY] = settings.markerSize.coerceIn(12, 48)
      prefs[SOM_SHOW_BOUNDING_BOXES_KEY] = settings.showBoundingBoxes
      prefs[SOM_SCREENSHOT_QUALITY_KEY] = settings.screenshotQuality.coerceIn(50, 100)
      prefs[SOM_FILTER_SMALL_ELEMENTS_KEY] = settings.filterSmallElements
      prefs[SOM_MIN_ELEMENT_AREA_KEY] = settings.minElementArea.coerceIn(0, 1000)
      prefs[SOM_MERGE_IOU_THRESHOLD_KEY] = (settings.mergeIouThreshold.coerceIn(0f, 1f) * 100).toInt()
    }
  }

  override suspend fun setSomEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[SOM_ENABLED_KEY] = enabled
    }
  }

  override suspend fun setSomRenderAnnotations(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[SOM_RENDER_ANNOTATIONS_KEY] = enabled
    }
  }

  override suspend fun setSomIncludeUITree(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[SOM_INCLUDE_UI_TREE_KEY] = enabled
    }
  }

  override suspend fun setSomMinConfidence(confidence: Float) {
    val clampedConfidence = confidence.coerceIn(0f, 1f)
    dataStore.edit { prefs ->
      prefs[SOM_MIN_CONFIDENCE_KEY] = (clampedConfidence * 100).toInt()
    }
  }

  override suspend fun setSomMaxElements(maxElements: Int) {
    val clampedMax = maxElements.coerceIn(1, 100)
    dataStore.edit { prefs ->
      prefs[SOM_MAX_ELEMENTS_KEY] = clampedMax
    }
  }

  companion object {
    private val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")
    private val MAX_STEPS_KEY = intPreferencesKey("max_steps")
    private val STEP_DELAY_KEY = longPreferencesKey("step_delay")
    private val REQUEST_TIMEOUT_KEY = longPreferencesKey("request_timeout")
    private val MAX_TOKENS_KEY = intPreferencesKey("max_tokens")
    private val SCREENSHOT_QUALITY_KEY = stringPreferencesKey("screenshot_quality")
    private val MAX_RETRIES_KEY = intPreferencesKey("max_retries")

    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR_ENABLED_KEY = booleanPreferencesKey("dynamic_color_enabled")
    private val APP_LANGUAGE_KEY = stringPreferencesKey("app_language")

    private val HYBRID_PERCEPTION_ENABLED_KEY = booleanPreferencesKey("hybrid_perception_enabled")
    private val PERCEPTION_MODE_KEY = stringPreferencesKey("perception_mode")
    private val PERCEPTION_TIMEOUT_KEY = longPreferencesKey("perception_timeout")
    private val ADAPTIVE_LEARNING_ENABLED_KEY = booleanPreferencesKey("adaptive_learning_enabled")
    private val MAX_CACHE_SIZE_KEY = intPreferencesKey("max_cache_size")
    private val UI_TREE_MAX_DEPTH_KEY = intPreferencesKey("ui_tree_max_depth")
    private val INCLUDE_NON_INTERACTIVE_KEY = booleanPreferencesKey("include_non_interactive")

    private val PREFERRED_INPUT_METHOD_KEY = stringPreferencesKey("preferred_input_method")
    private val PREFERRED_CAPTURE_METHOD_KEY = stringPreferencesKey("preferred_capture_method")
    private val ENABLE_FALLBACK_KEY = booleanPreferencesKey("enable_fallback")
    private val PRIVACY_MODE_KEY = stringPreferencesKey("privacy_mode")
    private val AUDIT_LOG_ENABLED_KEY = booleanPreferencesKey("audit_log_enabled")
    private val SHOW_CAPABILITY_WARNINGS_KEY = booleanPreferencesKey("show_capability_warnings")

    private val DSP_INJECTION_MODE_KEY = stringPreferencesKey("dsp_injection_mode")
    private val DSP_ENABLED_KEY = booleanPreferencesKey("dsp_enabled")
    private val DSP_DOWNSCALE_FACTOR_KEY = intPreferencesKey("dsp_downscale_factor")
    private val DSP_TARGET_LATENCY_KEY = longPreferencesKey("dsp_target_latency")
    private val DSP_ENABLE_OCR_KEY = booleanPreferencesKey("dsp_enable_ocr")
    private val DSP_MIN_CONFIDENCE_KEY = intPreferencesKey("dsp_min_confidence")
    private val DSP_MAX_BUFFERS_KEY = intPreferencesKey("dsp_max_buffers")
    private val DSP_TOUCH_DEVICE_PATH_KEY = stringPreferencesKey("dsp_touch_device_path")

    private val SOM_ENABLED_KEY = booleanPreferencesKey("som_enabled")
    private val SOM_RENDER_ANNOTATIONS_KEY = booleanPreferencesKey("som_render_annotations")
    private val SOM_INCLUDE_UI_TREE_KEY = booleanPreferencesKey("som_include_ui_tree")
    private val SOM_MIN_CONFIDENCE_KEY = intPreferencesKey("som_min_confidence")
    private val SOM_MAX_ELEMENTS_KEY = intPreferencesKey("som_max_elements")
    private val SOM_MARKER_SIZE_KEY = intPreferencesKey("som_marker_size")
    private val SOM_SHOW_BOUNDING_BOXES_KEY = booleanPreferencesKey("som_show_bounding_boxes")
    private val SOM_SCREENSHOT_QUALITY_KEY = intPreferencesKey("som_screenshot_quality")
    private val SOM_FILTER_SMALL_ELEMENTS_KEY = booleanPreferencesKey("som_filter_small_elements")
    private val SOM_MIN_ELEMENT_AREA_KEY = intPreferencesKey("som_min_element_area")
    private val SOM_MERGE_IOU_THRESHOLD_KEY = intPreferencesKey("som_merge_iou_threshold")
  }
}
