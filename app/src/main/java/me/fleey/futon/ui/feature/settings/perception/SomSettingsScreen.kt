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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.fleey.futon.R
import me.fleey.futon.data.perception.models.HardwareCapabilities
import me.fleey.futon.data.perception.models.ModelDeploymentStatus
import me.fleey.futon.data.perception.models.PerceptionMetrics
import me.fleey.futon.data.settings.models.DspPerceptionSettings
import me.fleey.futon.data.settings.models.HybridPerceptionSettings
import me.fleey.futon.data.settings.models.PerceptionModeConfig
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSliderItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun SomSettingsScreen(
  onBack: () -> Unit,
  viewModel: SomSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(uiState.errorMessage) {
    uiState.errorMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.onEvent(SomSettingsUiEvent.DismissError)
    }
  }

  LaunchedEffect(uiState.successMessage) {
    uiState.successMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.onEvent(SomSettingsUiEvent.DismissSuccess)
    }
  }

  if (uiState.showTestDialog && uiState.testResult != null) {
    SomTestResultDialog(
      testResult = uiState.testResult!!,
      onDismiss = { viewModel.onEvent(SomSettingsUiEvent.DismissTestDialog) },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_som),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    if (uiState.isLoading) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
      ) {
        Spacer(modifier = Modifier.height(8.dp))

        SomEnabledSection(
          enabled = uiState.somEnabled,
          onEnabledChanged = { viewModel.onEvent(SomSettingsUiEvent.SomEnabledChanged(it)) },
        )

        if (uiState.somEnabled) {
          Spacer(modifier = Modifier.height(12.dp))

          PerceptionModeSection(
            selectedMode = uiState.perceptionMode,
            rootAvailable = uiState.rootStatus == RootStatus.AVAILABLE,
            onModeSelected = { viewModel.onEvent(SomSettingsUiEvent.PerceptionModeChanged(it)) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          TestSection(
            isTesting = uiState.isTesting,
            onRunTest = { viewModel.onEvent(SomSettingsUiEvent.RunTest) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          PerceptionSettingsSection(
            uiState = uiState,
            onIncludeUITreeChanged = { viewModel.onEvent(SomSettingsUiEvent.IncludeUITreeChanged(it)) },
            onMinConfidenceChanged = { viewModel.onEvent(SomSettingsUiEvent.MinConfidenceChanged(it)) },
            onUITreeMaxDepthChanged = { viewModel.onEvent(SomSettingsUiEvent.UITreeMaxDepthChanged(it)) },
            onIncludeNonInteractiveChanged = { viewModel.onEvent(SomSettingsUiEvent.IncludeNonInteractiveChanged(it)) },
            onPerceptionTimeoutChanged = { viewModel.onEvent(SomSettingsUiEvent.PerceptionTimeoutChanged(it)) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          AnnotationSection(
            renderAnnotations = uiState.renderAnnotations,
            showBoundingBoxes = uiState.showBoundingBoxes,
            markerSize = uiState.markerSize,
            maxElements = uiState.maxElements,
            onRenderAnnotationsChanged = { viewModel.onEvent(SomSettingsUiEvent.RenderAnnotationsChanged(it)) },
            onShowBoundingBoxesChanged = { viewModel.onEvent(SomSettingsUiEvent.ShowBoundingBoxesChanged(it)) },
            onMarkerSizeChanged = { viewModel.onEvent(SomSettingsUiEvent.MarkerSizeChanged(it)) },
            onMaxElementsChanged = { viewModel.onEvent(SomSettingsUiEvent.MaxElementsChanged(it)) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          FilteringSection(
            filterSmallElements = uiState.filterSmallElements,
            minElementArea = uiState.minElementArea,
            mergeIouThreshold = uiState.mergeIouThreshold,
            onFilterSmallElementsChanged = { viewModel.onEvent(SomSettingsUiEvent.FilterSmallElementsChanged(it)) },
            onMinElementAreaChanged = { viewModel.onEvent(SomSettingsUiEvent.MinElementAreaChanged(it)) },
            onMergeIouThresholdChanged = { viewModel.onEvent(SomSettingsUiEvent.MergeIouThresholdChanged(it)) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          PerformanceSection(
            uiState = uiState,
            onDownscaleFactorChanged = { viewModel.onEvent(SomSettingsUiEvent.DownscaleFactorChanged(it)) },
            onTargetLatencyChanged = { viewModel.onEvent(SomSettingsUiEvent.TargetLatencyChanged(it)) },
            onMaxBuffersChanged = { viewModel.onEvent(SomSettingsUiEvent.MaxBuffersChanged(it)) },
            onOcrEnabledChanged = { viewModel.onEvent(SomSettingsUiEvent.OcrEnabledChanged(it)) },
            onScreenshotQualityChanged = { viewModel.onEvent(SomSettingsUiEvent.ScreenshotQualityChanged(it)) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          LearningCacheSection(
            adaptiveLearningEnabled = uiState.adaptiveLearningEnabled,
            cacheSize = uiState.cacheSize,
            maxCacheSize = uiState.maxCacheSize,
            isClearingCache = uiState.isClearingCache,
            onAdaptiveLearningChanged = { viewModel.onEvent(SomSettingsUiEvent.AdaptiveLearningChanged(it)) },
            onClearCache = { viewModel.onEvent(SomSettingsUiEvent.ClearCache) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          HardwareInfoSection(capabilities = uiState.hardwareCapabilities)

          Spacer(modifier = Modifier.height(12.dp))

          PerformanceMetricsSection(metrics = uiState.metrics)

          Spacer(modifier = Modifier.height(12.dp))

          ModelListSection(models = uiState.models)

          Spacer(modifier = Modifier.height(12.dp))

          ModelActionsSection(
            isDeploying = uiState.isDeploying,
            isVerifying = uiState.isVerifying,
            hasWarning = uiState.hasModelWarning,
            onRedeploy = { viewModel.onEvent(SomSettingsUiEvent.RedeployModels) },
            onVerify = { viewModel.onEvent(SomSettingsUiEvent.VerifyModels) },
          )

          Spacer(modifier = Modifier.height(12.dp))

          ActionsSection(
            onResetToDefaults = { viewModel.onEvent(SomSettingsUiEvent.ResetToDefaults) },
          )
        }

        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }
}

@Composable
private fun SomEnabledSection(
  enabled: Boolean,
  onEnabledChanged: (Boolean) -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.som_master_switch)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.som_enable),
        description = stringResource(R.string.som_enable_description),
        checked = enabled,
        onCheckedChange = onEnabledChanged,
        leadingIcon = FutonIcons.Perception,
      )
    }
  }
}

@Composable
private fun PerceptionModeSection(
  selectedMode: PerceptionModeConfig,
  rootAvailable: Boolean,
  onModeSelected: (PerceptionModeConfig) -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.perception_method)) {
    item {
      SettingsRadioItem(
        title = stringResource(R.string.perception_screenshot_only),
        description = stringResource(R.string.perception_screenshot_only_description),
        selected = selectedMode == PerceptionModeConfig.SCREENSHOT_ONLY,
        onClick = { onModeSelected(PerceptionModeConfig.SCREENSHOT_ONLY) },
        leadingIcon = FutonIcons.Screenshot,
      )
    }
    item {
      SettingsRadioItem(
        title = stringResource(R.string.perception_ui_tree_only),
        description = stringResource(R.string.perception_ui_tree_only_description),
        selected = selectedMode == PerceptionModeConfig.UI_TREE_ONLY,
        onClick = { onModeSelected(PerceptionModeConfig.UI_TREE_ONLY) },
        leadingIcon = FutonIcons.Tree,
        enabled = rootAvailable,
      )
    }
    item {
      SettingsRadioItem(
        title = stringResource(R.string.perception_hybrid),
        description = stringResource(R.string.perception_hybrid_description),
        selected = selectedMode == PerceptionModeConfig.HYBRID,
        onClick = { onModeSelected(PerceptionModeConfig.HYBRID) },
        leadingIcon = FutonIcons.Hybrid,
        enabled = rootAvailable,
        recommended = rootAvailable,
      )
    }
  }
}

@Composable
private fun TestSection(
  isTesting: Boolean,
  onRunTest: () -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.som_test_section)) {
    item {
      SettingsItem(
        title = stringResource(R.string.som_run_test),
        description = stringResource(R.string.som_run_test_description),
        leadingIcon = FutonIcons.Screenshot,
        onClick = if (!isTesting) onRunTest else null,
        trailing = {
          if (isTesting) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
            )
          } else {
            Icon(
              imageVector = FutonIcons.ChevronRight,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = FutonTheme.colors.textMuted,
            )
          }
        },
      )
    }
  }
}

@Composable
private fun PerceptionSettingsSection(
  uiState: SomSettingsUiState,
  onIncludeUITreeChanged: (Boolean) -> Unit,
  onMinConfidenceChanged: (Float) -> Unit,
  onUITreeMaxDepthChanged: (Int) -> Unit,
  onIncludeNonInteractiveChanged: (Boolean) -> Unit,
  onPerceptionTimeoutChanged: (Float) -> Unit,
) {
  val context = LocalContext.current
  val uiTreeEnabled = uiState.isUITreeSettingsEnabled

  SettingsGroup(title = stringResource(R.string.som_perception_settings)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.som_include_ui_tree),
        description = stringResource(R.string.som_include_ui_tree_description),
        checked = uiState.includeUITree,
        onCheckedChange = onIncludeUITreeChanged,
        leadingIcon = FutonIcons.Tree,
        enabled = uiTreeEnabled,
      )
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.som_min_confidence),
        value = uiState.minConfidence,
        onValueChange = onMinConfidenceChanged,
        leadingIcon = FutonIcons.Filter,
        description = stringResource(R.string.som_min_confidence_description),
        valueRange = 0.1f..0.9f,
        steps = 7,
        valueFormatter = { context.getString(R.string.som_confidence_value, (it * 100).toInt()) },
        minLabel = stringResource(R.string.som_confidence_low),
        maxLabel = stringResource(R.string.som_confidence_high),
      )
    }
    if (uiTreeEnabled) {
      item {
        SettingsSliderItem(
          title = stringResource(R.string.perception_ui_tree_depth),
          value = uiState.uiTreeMaxDepth.toFloat(),
          onValueChange = { onUITreeMaxDepthChanged(it.toInt()) },
          leadingIcon = FutonIcons.Tree,
          description = stringResource(R.string.perception_ui_tree_depth_description),
          valueRange = HybridPerceptionSettings.MIN_UI_TREE_DEPTH.toFloat()..HybridPerceptionSettings.MAX_UI_TREE_DEPTH.toFloat(),
          steps = HybridPerceptionSettings.MAX_UI_TREE_DEPTH - HybridPerceptionSettings.MIN_UI_TREE_DEPTH - 1,
          valueFormatter = { context.getString(R.string.perception_depth_levels, it.toInt()) },
          minLabel = stringResource(R.string.perception_depth_shallow),
          maxLabel = stringResource(R.string.perception_depth_deep),
        )
      }
      item {
        SettingsSwitchItem(
          title = stringResource(R.string.perception_include_non_interactive),
          description = stringResource(R.string.perception_include_non_interactive_description),
          checked = uiState.includeNonInteractive,
          onCheckedChange = onIncludeNonInteractiveChanged,
          leadingIcon = FutonIcons.Filter,
        )
      }
      item {
        SettingsSliderItem(
          title = stringResource(R.string.perception_timeout),
          value = uiState.perceptionTimeoutSeconds,
          onValueChange = onPerceptionTimeoutChanged,
          leadingIcon = FutonIcons.Timer,
          description = stringResource(R.string.perception_timeout_description),
          valueRange = (HybridPerceptionSettings.MIN_TIMEOUT_MS / 1000f)..(HybridPerceptionSettings.MAX_TIMEOUT_MS / 1000f),
          steps = 8,
          valueFormatter = { context.getString(R.string.settings_timeout_seconds, it.toInt()) },
          minLabel = stringResource(R.string.perception_timeout_min),
          maxLabel = stringResource(R.string.perception_timeout_max),
        )
      }
    }
  }
}

@Composable
private fun AnnotationSection(
  renderAnnotations: Boolean,
  showBoundingBoxes: Boolean,
  markerSize: Int,
  maxElements: Int,
  onRenderAnnotationsChanged: (Boolean) -> Unit,
  onShowBoundingBoxesChanged: (Boolean) -> Unit,
  onMarkerSizeChanged: (Int) -> Unit,
  onMaxElementsChanged: (Int) -> Unit,
) {
  val context = LocalContext.current
  SettingsGroup(title = stringResource(R.string.som_annotation_settings)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.som_render_annotations),
        description = stringResource(R.string.som_render_annotations_description),
        checked = renderAnnotations,
        onCheckedChange = onRenderAnnotationsChanged,
        leadingIcon = FutonIcons.Screenshot,
      )
    }
    if (renderAnnotations) {
      item {
        SettingsSwitchItem(
          title = stringResource(R.string.som_show_bounding_boxes),
          description = stringResource(R.string.som_show_bounding_boxes_description),
          checked = showBoundingBoxes,
          onCheckedChange = onShowBoundingBoxesChanged,
          leadingIcon = FutonIcons.Crop,
        )
      }
      item {
        SettingsSliderItem(
          title = stringResource(R.string.som_marker_size),
          value = markerSize.toFloat(),
          onValueChange = { onMarkerSizeChanged(it.toInt()) },
          leadingIcon = FutonIcons.TextSize,
          description = stringResource(R.string.som_marker_size_description),
          valueRange = 12f..48f,
          steps = 8,
          valueFormatter = { context.getString(R.string.som_marker_size_value, it.toInt()) },
          minLabel = stringResource(R.string.som_marker_size_small),
          maxLabel = stringResource(R.string.som_marker_size_large),
        )
      }
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.som_max_elements),
        value = maxElements.toFloat(),
        onValueChange = { onMaxElementsChanged(it.toInt()) },
        leadingIcon = FutonIcons.List,
        description = stringResource(R.string.som_max_elements_description),
        valueRange = 10f..100f,
        steps = 8,
        valueFormatter = { context.getString(R.string.som_max_elements_value, it.toInt()) },
        minLabel = stringResource(R.string.som_max_elements_few),
        maxLabel = stringResource(R.string.som_max_elements_many),
      )
    }
  }
}

@Composable
private fun FilteringSection(
  filterSmallElements: Boolean,
  minElementArea: Int,
  mergeIouThreshold: Float,
  onFilterSmallElementsChanged: (Boolean) -> Unit,
  onMinElementAreaChanged: (Int) -> Unit,
  onMergeIouThresholdChanged: (Float) -> Unit,
) {
  val context = LocalContext.current
  SettingsGroup(title = stringResource(R.string.som_filtering_settings)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.som_filter_small_elements),
        description = stringResource(R.string.som_filter_small_elements_description),
        checked = filterSmallElements,
        onCheckedChange = onFilterSmallElementsChanged,
        leadingIcon = FutonIcons.Filter,
      )
    }
    if (filterSmallElements) {
      item {
        SettingsSliderItem(
          title = stringResource(R.string.som_min_element_area),
          value = minElementArea.toFloat(),
          onValueChange = { onMinElementAreaChanged(it.toInt()) },
          leadingIcon = FutonIcons.Crop,
          description = stringResource(R.string.som_min_element_area_description),
          valueRange = 0f..500f,
          steps = 9,
          valueFormatter = { context.getString(R.string.som_min_element_area_value, it.toInt()) },
          minLabel = stringResource(R.string.som_min_element_area_small),
          maxLabel = stringResource(R.string.som_min_element_area_large),
        )
      }
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.som_merge_iou_threshold),
        value = mergeIouThreshold,
        onValueChange = onMergeIouThresholdChanged,
        leadingIcon = FutonIcons.Merge,
        description = stringResource(R.string.som_merge_iou_threshold_description),
        valueRange = 0.3f..0.9f,
        steps = 5,
        valueFormatter = { context.getString(R.string.som_iou_value, (it * 100).toInt()) },
        minLabel = stringResource(R.string.som_iou_low),
        maxLabel = stringResource(R.string.som_iou_high),
      )
    }
  }
}

@Composable
private fun PerformanceSection(
  uiState: SomSettingsUiState,
  onDownscaleFactorChanged: (Int) -> Unit,
  onTargetLatencyChanged: (Long) -> Unit,
  onMaxBuffersChanged: (Int) -> Unit,
  onOcrEnabledChanged: (Boolean) -> Unit,
  onScreenshotQualityChanged: (Int) -> Unit,
) {
  val context = LocalContext.current
  SettingsGroup(title = stringResource(R.string.dsp_capture_settings)) {
    item {
      SettingsSliderItem(
        title = stringResource(R.string.dsp_downscale_factor),
        value = uiState.downscaleFactor.toFloat(),
        onValueChange = { onDownscaleFactorChanged(it.toInt()) },
        leadingIcon = FutonIcons.Screenshot,
        description = stringResource(R.string.dsp_downscale_factor_description),
        valueRange = DspPerceptionSettings.MIN_DOWNSCALE.toFloat()..DspPerceptionSettings.MAX_DOWNSCALE.toFloat(),
        steps = DspPerceptionSettings.MAX_DOWNSCALE - DspPerceptionSettings.MIN_DOWNSCALE - 1,
        valueFormatter = { context.getString(R.string.dsp_downscale_value, it.toInt()) },
        minLabel = stringResource(R.string.dsp_downscale_full),
        maxLabel = stringResource(R.string.dsp_downscale_quarter),
      )
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.dsp_target_latency),
        value = uiState.targetLatencyMs.toFloat(),
        onValueChange = { onTargetLatencyChanged(it.toLong()) },
        leadingIcon = FutonIcons.Timer,
        description = stringResource(R.string.dsp_target_latency_description),
        valueRange = DspPerceptionSettings.MIN_LATENCY_MS.toFloat()..DspPerceptionSettings.MAX_LATENCY_MS.toFloat(),
        steps = 8,
        valueFormatter = { context.getString(R.string.dsp_target_latency_value, it.toInt()) },
        minLabel = stringResource(R.string.dsp_target_latency_min),
        maxLabel = stringResource(R.string.dsp_target_latency_max),
      )
    }
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.dsp_enable_ocr),
        description = stringResource(R.string.dsp_enable_ocr_description),
        checked = uiState.enableOcr,
        onCheckedChange = onOcrEnabledChanged,
        leadingIcon = FutonIcons.Learning,
      )
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.dsp_max_buffers),
        value = uiState.maxConcurrentBuffers.toFloat(),
        onValueChange = { onMaxBuffersChanged(it.toInt()) },
        leadingIcon = FutonIcons.Cache,
        description = stringResource(R.string.dsp_max_buffers_description),
        valueRange = DspPerceptionSettings.MIN_BUFFERS.toFloat()..DspPerceptionSettings.MAX_BUFFERS.toFloat(),
        steps = DspPerceptionSettings.MAX_BUFFERS - DspPerceptionSettings.MIN_BUFFERS - 1,
        valueFormatter = { context.getString(R.string.dsp_max_buffers_value, it.toInt()) },
        minLabel = stringResource(R.string.dsp_max_buffers_min),
        maxLabel = stringResource(R.string.dsp_max_buffers_max),
      )
    }
    item {
      SettingsSliderItem(
        title = stringResource(R.string.som_screenshot_quality),
        value = uiState.screenshotQuality.toFloat(),
        onValueChange = { onScreenshotQualityChanged(it.toInt()) },
        leadingIcon = FutonIcons.Image,
        description = stringResource(R.string.som_screenshot_quality_description),
        valueRange = 50f..100f,
        steps = 9,
        valueFormatter = { context.getString(R.string.som_quality_value, it.toInt()) },
        minLabel = stringResource(R.string.som_quality_low),
        maxLabel = stringResource(R.string.som_quality_high),
      )
    }
  }
}

@Composable
private fun LearningCacheSection(
  adaptiveLearningEnabled: Boolean,
  cacheSize: Int,
  maxCacheSize: Int,
  isClearingCache: Boolean,
  onAdaptiveLearningChanged: (Boolean) -> Unit,
  onClearCache: () -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.perception_adaptive_learning)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.perception_enable_learning),
        description = stringResource(R.string.perception_learning_description),
        checked = adaptiveLearningEnabled,
        onCheckedChange = onAdaptiveLearningChanged,
        leadingIcon = FutonIcons.Learning,
      )
    }
    item {
      CacheSectionContent(
        cacheSize = cacheSize,
        maxCacheSize = maxCacheSize,
        isClearing = isClearingCache,
        onClearCache = onClearCache,
      )
    }
  }
}

@Composable
private fun CacheSectionContent(
  cacheSize: Int,
  maxCacheSize: Int,
  isClearing: Boolean,
  onClearCache: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = FutonIcons.Cache,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = FutonTheme.colors.interactiveNormal,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
          Text(
            text = stringResource(R.string.perception_cache),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal,
          )
          Text(
            text = stringResource(R.string.perception_cache_count, cacheSize, maxCacheSize),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
      FutonButton(
        text = if (isClearing) stringResource(R.string.perception_clearing) else stringResource(R.string.perception_clear_cache),
        onClick = onClearCache,
        style = ButtonStyle.Danger,
        icon = FutonIcons.Clear,
        enabled = !isClearing && cacheSize > 0,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.perception_cache_warning),
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun HardwareInfoSection(capabilities: HardwareCapabilities?) {
  SettingsGroup(title = stringResource(R.string.dsp_hardware_info)) {
    item {
      HardwareInfoContent(capabilities = capabilities)
    }
  }
}

@Composable
private fun HardwareInfoContent(capabilities: HardwareCapabilities?) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = FutonIcons.Speed,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = FutonTheme.colors.interactiveNormal,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = stringResource(R.string.dsp_hardware_accelerators),
        style = MaterialTheme.typography.titleSmall,
        color = FutonTheme.colors.textNormal,
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (capabilities != null) {
      InfoRow(
        label = stringResource(R.string.dsp_active_delegate),
        value = capabilities.recommendedDelegate.name,
      )
      InfoRow(
        label = stringResource(R.string.dsp_gpu_vendor),
        value = capabilities.gpuRenderer ?: capabilities.gpuVendor
        ?: stringResource(R.string.dsp_not_available),
      )
      InfoRow(
        label = stringResource(R.string.dsp_hexagon_dsp),
        value = if (capabilities.hasHexagonDsp) {
          capabilities.hexagonVersion ?: stringResource(R.string.dsp_available)
        } else {
          stringResource(R.string.dsp_not_available)
        },
      )
      InfoRow(
        label = stringResource(R.string.dsp_nnapi),
        value = if (capabilities.hasNnapi) "API ${capabilities.nnapiVersion}" else stringResource(R.string.dsp_not_available),
      )
      InfoRow(
        label = stringResource(R.string.dsp_npu),
        value = if (capabilities.hasNpu) stringResource(R.string.dsp_available) else stringResource(R.string.dsp_not_available),
      )
    } else {
      Text(
        text = stringResource(R.string.dsp_no_metrics),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun PerformanceMetricsSection(metrics: PerceptionMetrics?) {
  val context = LocalContext.current
  SettingsGroup(title = stringResource(R.string.dsp_performance_metrics)) {
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = FutonIcons.Timer,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = FutonTheme.colors.interactiveNormal,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = stringResource(R.string.dsp_performance_metrics),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (metrics != null && metrics.loopCount > 0) {
          InfoRow(
            label = stringResource(R.string.dsp_avg_latency),
            value = context.getString(R.string.dsp_latency_ms, metrics.averageLatencyMs.toInt()),
          )
          InfoRow(
            label = stringResource(R.string.dsp_p95_latency),
            value = context.getString(R.string.dsp_latency_ms, metrics.p95LatencyMs.toInt()),
          )
          InfoRow(
            label = stringResource(R.string.dsp_loop_count),
            value = metrics.loopCount.toString(),
          )
          InfoRow(
            label = stringResource(R.string.dsp_capture_avg),
            value = context.getString(R.string.dsp_latency_ms, metrics.captureAverageMs.toInt()),
          )
          InfoRow(
            label = stringResource(R.string.dsp_detection_avg),
            value = context.getString(R.string.dsp_latency_ms, metrics.detectionAverageMs.toInt()),
          )
          InfoRow(
            label = stringResource(R.string.dsp_ocr_avg),
            value = context.getString(R.string.dsp_latency_ms, metrics.ocrAverageMs.toInt()),
          )
          InfoRow(
            label = stringResource(R.string.dsp_model_memory),
            value = context.getString(R.string.dsp_memory_mb, metrics.modelMemoryMb),
          )
          InfoRow(
            label = stringResource(R.string.dsp_buffer_memory),
            value = context.getString(R.string.dsp_memory_mb, metrics.bufferMemoryMb),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            color = if (metrics.isAboveThreshold) {
              FutonTheme.colors.statusWarning.copy(alpha = 0.2f)
            } else {
              FutonTheme.colors.statusPositive.copy(alpha = 0.2f)
            },
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = if (metrics.isAboveThreshold) {
                stringResource(R.string.dsp_above_threshold)
              } else {
                stringResource(R.string.dsp_below_threshold)
              },
              style = MaterialTheme.typography.labelSmall,
              color = if (metrics.isAboveThreshold) FutonTheme.colors.statusWarning else FutonTheme.colors.statusPositive,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        } else {
          Text(
            text = stringResource(R.string.dsp_no_metrics),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
  }
}

@Composable
private fun ModelListSection(models: List<ModelDisplayInfo>) {
  SettingsGroup(title = stringResource(R.string.model_settings_deployed_models)) {
    if (models.isEmpty()) {
      item {
        SettingsItem(
          title = stringResource(R.string.model_settings_no_models),
          description = stringResource(R.string.model_settings_no_models_description),
          leadingIcon = FutonIcons.Info,
        )
      }
    } else {
      models.forEach { model ->
        item {
          ModelListItem(model)
        }
      }
    }
  }
}

@Composable
private fun ModelListItem(model: ModelDisplayInfo) {
  val (statusText, statusColor, statusIcon) = getStatusDisplay(model.status)

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Model,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = FutonTheme.colors.interactiveNormal,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = model.name,
              style = MaterialTheme.typography.bodyMedium,
              color = FutonTheme.colors.textNormal,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (model.required) {
              Spacer(modifier = Modifier.width(8.dp))
              Surface(
                color = FutonTheme.colors.statusWarning.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small,
              ) {
                Text(
                  text = stringResource(R.string.model_settings_required),
                  style = MaterialTheme.typography.labelSmall,
                  color = FutonTheme.colors.statusWarning,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }
          model.description?.let { desc ->
            Text(
              text = desc,
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
          imageVector = statusIcon,
          contentDescription = statusText,
          modifier = Modifier.size(20.dp),
          tint = statusColor,
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = formatFileSize(model.sizeBytes),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
        Text(
          text = statusText,
          style = MaterialTheme.typography.labelSmall,
          color = statusColor,
        )
      }
      if (model.status is ModelDeploymentStatus.Deploying) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { model.status.progress },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun getStatusDisplay(status: ModelDeploymentStatus): Triple<String, Color, ImageVector> {
  return when (status) {
    is ModelDeploymentStatus.NotDeployed -> Triple(
      stringResource(R.string.model_settings_status_not_deployed),
      FutonTheme.colors.textMuted,
      FutonIcons.Info,
    )

    is ModelDeploymentStatus.Deploying -> Triple(
      stringResource(R.string.model_settings_status_deploying, (status.progress * 100).toInt()),
      FutonTheme.colors.statusWarning,
      FutonIcons.Refresh,
    )

    is ModelDeploymentStatus.Deployed -> Triple(
      stringResource(R.string.model_settings_status_deployed),
      FutonTheme.colors.statusPositive,
      FutonIcons.CheckCircle,
    )

    is ModelDeploymentStatus.Corrupted -> Triple(
      stringResource(R.string.model_settings_status_corrupted),
      FutonTheme.colors.statusDanger,
      FutonIcons.Warning,
    )

    is ModelDeploymentStatus.Failed -> Triple(
      stringResource(R.string.model_settings_status_failed),
      FutonTheme.colors.statusDanger,
      FutonIcons.Error,
    )
  }
}

@Composable
private fun ModelActionsSection(
  isDeploying: Boolean,
  isVerifying: Boolean,
  hasWarning: Boolean,
  onRedeploy: () -> Unit,
  onVerify: () -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.model_settings_actions)) {
    item {
      SettingsItem(
        title = stringResource(R.string.model_settings_redeploy),
        description = stringResource(R.string.model_settings_redeploy_description),
        leadingIcon = FutonIcons.Refresh,
        onClick = if (!isDeploying && !isVerifying) onRedeploy else null,
        trailing = {
          if (isDeploying) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          } else if (hasWarning) {
            Icon(
              imageVector = FutonIcons.Warning,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = FutonTheme.colors.statusWarning,
            )
          }
        },
      )
    }
    item {
      SettingsItem(
        title = stringResource(R.string.model_settings_verify),
        description = stringResource(R.string.model_settings_verify_description),
        leadingIcon = FutonIcons.Security,
        onClick = if (!isDeploying && !isVerifying) onVerify else null,
        trailing = {
          if (isVerifying) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          }
        },
      )
    }
  }
}

@Composable
private fun ActionsSection(onResetToDefaults: () -> Unit) {
  SettingsGroup(title = stringResource(R.string.som_actions)) {
    item {
      SettingsItem(
        title = stringResource(R.string.som_reset_to_defaults),
        description = stringResource(R.string.som_reset_to_defaults_description),
        leadingIcon = FutonIcons.Refresh,
        onClick = onResetToDefaults,
      )
    }
  }
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textNormal,
    )
  }
}

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }
}

@Composable
private fun SomTestResultDialog(
  testResult: SomTestResult,
  onDismiss: () -> Unit,
) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = FutonTheme.colors.background,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Surface(
          color = FutonTheme.colors.backgroundSecondary,
          shadowElevation = 4.dp,
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.som_test_result_title),
                style = MaterialTheme.typography.titleLarge,
                color = FutonTheme.colors.textNormal,
              )
              FutonButton(
                text = stringResource(R.string.common_close),
                onClick = onDismiss,
                style = ButtonStyle.Secondary,
              )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              StatItem(
                label = stringResource(R.string.som_test_elements),
                value = testResult.annotation.elementCount.toString(),
              )
              StatItem(
                label = stringResource(R.string.som_test_capture_time),
                value = stringResource(R.string.som_test_ms, testResult.captureLatencyMs),
              )
              StatItem(
                label = stringResource(R.string.som_test_annotation_time),
                value = stringResource(R.string.som_test_ms, testResult.annotationLatencyMs),
              )
            }
          }
        }
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(FutonTheme.colors.backgroundTertiary)
            .pointerInput(Unit) {
              detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(0.5f, 5f)
                offset = Offset(x = offset.x + pan.x, y = offset.y + pan.y)
              }
            },
          contentAlignment = Alignment.Center,
        ) {
          Image(
            bitmap = testResult.annotatedBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.som_test_result_image),
            modifier = Modifier.graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offset.x,
              translationY = offset.y,
            ),
          )
          Surface(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(16.dp),
            color = FutonTheme.colors.backgroundSecondary.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = stringResource(R.string.som_test_zoom_hint),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StatItem(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = FutonTheme.colors.textNormal,
    )
  }
}
