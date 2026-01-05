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
package me.fleey.futon.ui.feature.localmodel

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.feedback.FutonDialog
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.localmodel.components.CompactModelCard
import me.fleey.futon.ui.feature.localmodel.components.DeleteConfirmationDialog
import me.fleey.futon.ui.feature.localmodel.components.ImportModelSection
import me.fleey.futon.ui.feature.localmodel.components.InferenceConfigSection
import me.fleey.futon.ui.feature.localmodel.components.MmprojPickerDialog
import me.fleey.futon.ui.feature.localmodel.components.ModelSearchSection
import me.fleey.futon.ui.feature.localmodel.components.QuantizationPickerDialog
import org.koin.androidx.compose.koinViewModel
import java.io.File

/**
 * Local Model Settings screen.
 * */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettingsScreen(
  onBack: () -> Unit,
  viewModel: LocalModelViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  var showQuantizationPicker by remember { mutableStateOf(false) }
  var selectedModelForDownload by remember { mutableStateOf<String?>(null) }

  var showMmprojPicker by remember { mutableStateOf(false) }
  var pendingModelPath by remember { mutableStateOf<String?>(null) }

  val modelFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    uri?.let {
      val path = getPathFromUri(context, it)
      if (path != null) {
        pendingModelPath = path
        // Check if VLM and needs mmproj
        if (uiState.importProgress?.requiresMmproj == true) {
          showMmprojPicker = true
        } else {
          viewModel.onEvent(LocalModelUiEvent.ImportModel(path, null))
        }
      }
    }
  }

  val mmprojFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    uri?.let {
      val mmprojPath = getPathFromUri(context, it)
      pendingModelPath?.let { modelPath ->
        viewModel.onEvent(LocalModelUiEvent.ImportModel(modelPath, mmprojPath))
      }
    }
    showMmprojPicker = false
    pendingModelPath = null
  }

  LaunchedEffect(uiState.successMessage) {
    uiState.successMessage?.let {
      snackbarHostState.showSnackbar(it)
    }
  }

  LaunchedEffect(uiState.errorMessage) {
    uiState.errorMessage?.let {
      snackbarHostState.showSnackbar(it)
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.local_model_top_bar_title),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
      isRefreshing = uiState.isLoadingCatalog,
      onRefresh = { viewModel.onEvent(LocalModelUiEvent.RefreshCatalog) },
      state = pullToRefreshState,
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      FutonLoadingOverlay(loading = uiState.isLoading) {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(
            top = 8.dp,
            bottom = 32.dp,
          ),
        ) {
          item(key = "device_capabilities") {
            uiState.deviceCapabilities?.let { capabilities ->
              CollapsibleDeviceCapabilitiesSection(
                capabilities = capabilities,
                recommendedQuantization = uiState.recommendedQuantization,
                availableStorage = uiState.availableStorageFormatted,
                isCollapsed = uiState.isDeviceCapabilitiesCollapsed,
                onToggleCollapsed = {
                  viewModel.onEvent(
                    LocalModelUiEvent.SetDeviceCapabilitiesCollapsed(
                      !uiState.isDeviceCapabilitiesCollapsed,
                    ),
                  )
                },
              )
            }
          }

          item(key = "model_search") {
            ModelSearchSection(
              searchQuery = uiState.searchQuery,
              onSearchQueryChange = { query ->
                viewModel.onEvent(LocalModelUiEvent.SetSearchQuery(query))
              },
              isSearchExpanded = uiState.isSearchExpanded,
              onSearchExpandedChange = { expanded ->
                viewModel.onEvent(LocalModelUiEvent.SetSearchExpanded(expanded))
              },
              downloadSource = uiState.downloadSource,
              onDownloadSourceChange = { source ->
                viewModel.onEvent(LocalModelUiEvent.SetDownloadSource(source))
              },
              isDownloading = uiState.hasActiveDownloads,
              modelTypeFilter = uiState.modelTypeFilter,
              ramFilter = uiState.ramFilter,
              sortOption = uiState.sortOption,
              onModelTypeFilterChange = { isVlm ->
                viewModel.onEvent(LocalModelUiEvent.SetModelTypeFilter(isVlm))
              },
              onRamFilterChange = { maxRamMb ->
                viewModel.onEvent(LocalModelUiEvent.SetRamFilter(maxRamMb))
              },
              onSortOptionChange = { sortOption ->
                viewModel.onEvent(LocalModelUiEvent.SetSortOption(sortOption))
              },
            )
          }

          item(key = "model_list") {
            ModelListCard(
              models = uiState.filteredModels,
              isLoadingCatalog = uiState.isLoadingCatalog,
              hasFilters = uiState.searchQuery.isNotBlank() ||
                uiState.modelTypeFilter != null ||
                uiState.ramFilter != null,
              expandedModelCards = uiState.expandedModelCards,
              deviceCapabilities = uiState.deviceCapabilities,
              onRefreshCatalog = { viewModel.onEvent(LocalModelUiEvent.RefreshCatalog) },
              onResetFilters = { viewModel.onEvent(LocalModelUiEvent.ResetFilters) },
              onToggleModelCardExpanded = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.ToggleModelCardExpanded(modelId))
              },
              onDownloadClick = { modelId ->
                selectedModelForDownload = modelId
                showQuantizationPicker = true
              },
              onPauseClick = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.PauseDownload(modelId))
              },
              onResumeClick = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.ResumeDownload(modelId))
              },
              onCancelClick = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.CancelDownload(modelId))
              },
              onEnableClick = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.EnableModel(modelId))
              },
              onDisableClick = { viewModel.onEvent(LocalModelUiEvent.DisableModel) },
              onDeleteClick = { modelId ->
                viewModel.onEvent(LocalModelUiEvent.RequestDeleteModel(modelId))
              },
              onQuantizationSelect = { modelId, quantization ->
                viewModel.onEvent(
                  LocalModelUiEvent.DownloadModel(
                    modelId = modelId,
                    quantization = quantization.type,
                  ),
                )
              },
              getDownloadedModel = { modelId -> uiState.getDownloadedModel(modelId) },
              getDownloadProgress = { modelId -> uiState.getDownloadProgress(modelId) },
              isActive = { modelId -> uiState.isActive(modelId) },
            )
          }

          item(key = "import_model") {
            ImportModelSection(
              importProgress = uiState.importProgress,
              onImportClick = {
                modelFilePicker.launch("*/*")
              },
              onCancelImport = {
                viewModel.onEvent(LocalModelUiEvent.CancelImport)
              },
            )
          }

          item(key = "inference_config") {
            InferenceConfigSection(
              config = uiState.inferenceConfig,
              onContextLengthChange = { length ->
                viewModel.onEvent(LocalModelUiEvent.SetContextLength(length))
              },
              onThreadCountChange = { count ->
                viewModel.onEvent(LocalModelUiEvent.SetThreadCount(count))
              },
              onPresetSelected = { preset ->
                viewModel.onEvent(LocalModelUiEvent.ApplyInferencePreset(preset))
              },
              onUseNnapiChange = { enabled ->
                viewModel.onEvent(LocalModelUiEvent.SetUseNnapi(enabled))
              },
              supportsNnapi = uiState.deviceCapabilities?.supportsNnapi ?: false,
            )
          }
        }
      }
    }
  }

  if (showQuantizationPicker && selectedModelForDownload != null) {
    val model = uiState.availableModels.find { it.id == selectedModelForDownload }
    model?.let {
      QuantizationPickerDialog(
        model = it,
        recommendedQuantization = uiState.recommendedQuantization,
        availableStorage = uiState.availableStorageBytes,
        onQuantizationSelected = { quantization ->
          viewModel.onEvent(
            LocalModelUiEvent.DownloadModel(
              modelId = selectedModelForDownload!!,
              quantization = quantization,
            ),
          )
          showQuantizationPicker = false
          selectedModelForDownload = null
        },
        onDismiss = {
          showQuantizationPicker = false
          selectedModelForDownload = null
        },
      )
    }
  }

  if (showMmprojPicker) {
    MmprojPickerDialog(
      onSelectFile = {
        mmprojFilePicker.launch("*/*")
      },
      onDismiss = {
        showMmprojPicker = false
        pendingModelPath = null
        viewModel.onEvent(LocalModelUiEvent.CancelImport)
      },
    )
  }

  uiState.modelPendingDeletion?.let { modelId ->
    val downloadedModel = uiState.getDownloadedModel(modelId)
    val modelInfo = uiState.availableModels.find { it.id == modelId }

    DeleteConfirmationDialog(
      modelName = modelInfo?.name ?: modelId,
      modelSize = downloadedModel?.sizeFormatted ?: "",
      isActive = uiState.isActive(modelId),
      onConfirm = {
        viewModel.onEvent(LocalModelUiEvent.ConfirmDeleteModel)
      },
      onDismiss = {
        viewModel.onEvent(LocalModelUiEvent.CancelDeleteModel)
      },
    )
  }

  uiState.errorMessage?.let { error ->
    FutonDialog(
      title = stringResource(R.string.dialog_error_title),
      message = error,
      confirmText = stringResource(R.string.action_confirm),
      onConfirm = { viewModel.onEvent(LocalModelUiEvent.DismissError) },
      onDismiss = { viewModel.onEvent(LocalModelUiEvent.DismissError) },
    )
  }
}

/**
 * Model list card component that wraps all models in a SettingsSection-style card.
 *
 * This provides consistent styling with other settings sections.
 */
@Composable
private fun ModelListCard(
  models: List<ModelInfo>,
  isLoadingCatalog: Boolean,
  hasFilters: Boolean,
  expandedModelCards: Set<String>,
  deviceCapabilities: me.fleey.futon.data.localmodel.inference.DeviceCapabilities?,
  onRefreshCatalog: () -> Unit,
  onResetFilters: () -> Unit,
  onToggleModelCardExpanded: (String) -> Unit,
  onDownloadClick: (String) -> Unit,
  onPauseClick: (String) -> Unit,
  onResumeClick: (String) -> Unit,
  onCancelClick: (String) -> Unit,
  onEnableClick: (String) -> Unit,
  onDisableClick: () -> Unit,
  onDeleteClick: (String) -> Unit,
  onQuantizationSelect: (String, QuantizationInfo) -> Unit,
  getDownloadedModel: (String) -> DownloadedModel?,
  getDownloadProgress: (String) -> DownloadProgress?,
  isActive: (String) -> Boolean,
  modifier: Modifier = Modifier,
) {
  SettingsGroup(
    title = stringResource(R.string.local_model_available_models),
    modifier = modifier,
  ) {
    when {
      isLoadingCatalog && models.isEmpty() -> {
        item {
          LoadingModelState()
        }
      }

      models.isEmpty() -> {
        item {
          EmptyModelState(
            hasFilters = hasFilters,
            onResetFilters = onResetFilters,
          )
        }
      }

      else -> {
        models.forEach { model ->
          item {
            val downloadedModel = getDownloadedModel(model.id)
            val downloadProgress = getDownloadProgress(model.id)
            val modelIsActive = isActive(model.id)
            val isExpanded = expandedModelCards.contains(model.id)
            val isRecommended = deviceCapabilities?.let { capabilities ->
              model.quantizations.any { it.minRamMb <= capabilities.availableRamMb }
            } ?: false

            CompactModelCard(
              model = model,
              downloadedModel = downloadedModel,
              downloadProgress = downloadProgress,
              isActive = modelIsActive,
              isExpanded = isExpanded,
              isRecommended = isRecommended,
              onExpandToggle = { onToggleModelCardExpanded(model.id) },
              onDownloadClick = { onDownloadClick(model.id) },
              onPauseClick = { onPauseClick(model.id) },
              onResumeClick = { onResumeClick(model.id) },
              onCancelClick = { onCancelClick(model.id) },
              onEnableClick = { onEnableClick(model.id) },
              onDisableClick = onDisableClick,
              onDeleteClick = { onDeleteClick(model.id) },
              onQuantizationSelect = { quantization ->
                onQuantizationSelect(model.id, quantization)
              },
            )
          }
        }
      }
    }
  }
}

/**
 * Collapsible wrapper for DeviceCapabilitiesSection.
 */
@Composable
private fun CollapsibleDeviceCapabilitiesSection(
  capabilities: me.fleey.futon.data.localmodel.inference.DeviceCapabilities,
  recommendedQuantization: me.fleey.futon.data.localmodel.models.QuantizationType,
  availableStorage: String,
  isCollapsed: Boolean,
  onToggleCollapsed: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val expandIconRotation by animateFloatAsState(
    targetValue = if (isCollapsed) 0f else 180f,
    animationSpec = tween(durationMillis = 200),
    label = "expandIconRotation",
  )

  SettingsGroup(
    title = stringResource(R.string.local_model_device_info),
    modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 200)),
  ) {
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggleCollapsed)
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = FutonTheme.colors.interactiveNormal,
          )
          Column {
            Text(
              text = if (isCollapsed) stringResource(R.string.local_model_expand_details) else stringResource(
                R.string.local_model_device_details,
              ),
              style = MaterialTheme.typography.bodyMedium,
              color = FutonTheme.colors.textNormal,
            )
            if (isCollapsed) {
              Text(
                text = stringResource(
                  R.string.local_model_ram_info,
                  capabilities.availableRamFormatted,
                  recommendedQuantization.name,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.textMuted,
              )
            }
          }
        }
        Icon(
          imageVector = FutonIcons.ExpandMore,
          contentDescription = if (isCollapsed) stringResource(R.string.local_model_expand) else stringResource(
            R.string.local_model_collapse,
          ),
          modifier = Modifier
            .size(24.dp)
            .rotate(expandIconRotation),
          tint = FutonTheme.colors.textMuted,
        )
      }
    }

    if (!isCollapsed) {
      item {
        DeviceCapabilityRow(
          icon = FutonIcons.Cache,
          label = stringResource(R.string.local_model_memory),
          value = stringResource(
            R.string.local_model_memory_value,
            capabilities.totalRamFormatted,
            capabilities.availableRamFormatted,
          ),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }

      item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
          DeviceCapabilityRow(
            icon = FutonIcons.Speed,
            label = stringResource(R.string.local_model_processor),
            value = capabilities.processorName,
          )
          if (capabilities.isSnapdragon && capabilities.snapdragonModel != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Snapdragon ${capabilities.snapdragonModel}",
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
              modifier = Modifier.padding(start = 32.dp),
            )
          }
        }
      }

      item {
        DeviceCapabilityRow(
          icon = FutonIcons.Save,
          label = stringResource(R.string.local_model_available_storage),
          value = availableStorage,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }

      item {
        DeviceCapabilityRow(
          icon = FutonIcons.Learning,
          label = stringResource(R.string.local_model_nnapi_acceleration),
          value = if (capabilities.supportsNnapi) stringResource(R.string.local_model_supported) else stringResource(
            R.string.local_model_not_supported,
          ),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }

      item {
        Column(modifier = Modifier.padding(16.dp)) {
          DeviceRecommendationCard(
            recommendedQuantization = recommendedQuantization,
            meetsMinimum = capabilities.meetsMinimumRequirements,
            isRecommended = capabilities.isRecommendedDevice,
          )

          if (capabilities.warnings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            capabilities.warnings.forEach { warning ->
              DeviceWarningCard(message = warning)
              Spacer(modifier = Modifier.height(8.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DeviceCapabilityRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(FutonSizes.IconSize),
      tint = FutonTheme.colors.interactiveNormal,
    )
    Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textNormal,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun DeviceRecommendationCard(
  recommendedQuantization: me.fleey.futon.data.localmodel.models.QuantizationType,
  meetsMinimum: Boolean,
  isRecommended: Boolean,
) {
  val backgroundColor = when {
    !meetsMinimum -> FutonTheme.colors.statusDanger.copy(alpha = 0.1f)
    isRecommended -> FutonTheme.colors.statusPositive.copy(alpha = 0.1f)
    else -> FutonTheme.colors.statusWarning.copy(alpha = 0.1f)
  }

  val textColor = when {
    !meetsMinimum -> FutonTheme.colors.statusDanger
    isRecommended -> FutonTheme.colors.statusPositive
    else -> FutonTheme.colors.statusWarning
  }

  val icon = when {
    !meetsMinimum -> FutonIcons.Error
    isRecommended -> FutonIcons.Success
    else -> FutonIcons.Warning
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = backgroundColor,
    shape = MaterialTheme.shapes.small,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = textColor,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = when {
            !meetsMinimum -> stringResource(R.string.local_model_device_not_meet_minimum)
            isRecommended -> stringResource(R.string.local_model_device_good_performance)
            else -> stringResource(R.string.local_model_device_average_performance)
          },
          style = MaterialTheme.typography.bodyMedium,
          color = textColor,
        )
        Text(
          text = stringResource(
            R.string.local_model_recommended_quantization,
            recommendedQuantization.name,
          ),
          style = MaterialTheme.typography.bodySmall,
          color = textColor.copy(alpha = 0.8f),
        )
      }
    }
  }
}

@Composable
private fun DeviceWarningCard(message: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
    shape = MaterialTheme.shapes.small,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = FutonTheme.colors.statusWarning,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.statusWarning,
      )
    }
  }
}

/**
 * Loading state for the model list.
 */
@Composable
private fun LoadingModelState() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(32.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 3.dp,
      )
      Text(
        text = stringResource(R.string.local_model_loading),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

/**
 * Empty state when no models match the current filters.
 */
@Composable
private fun EmptyModelState(
  hasFilters: Boolean,
  onResetFilters: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = FutonIcons.Model,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = FutonTheme.colors.interactiveMuted,
    )

    Text(
      text = if (hasFilters) stringResource(R.string.local_model_no_matching) else stringResource(R.string.local_model_no_available),
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )

    if (hasFilters) {
      Text(
        text = stringResource(R.string.local_model_no_results_hint),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )

      Spacer(modifier = Modifier.height(8.dp))

      FutonButton(
        text = stringResource(R.string.local_model_reset_filters),
        onClick = onResetFilters,
        icon = FutonIcons.Refresh,
      )
    }
  }
}

/**
 * Helper function to get file path from URI.
 * NOTE: This is a simplified implementation. In production,
 * may need to handle different URI schemes.
 */
private fun getPathFromUri(context: Context, uri: Uri): String? {
  return try {
    // For content URIs, we need to copy the file to app's cache
    // and return the cache path
    val inputStream = context.contentResolver.openInputStream(uri)
    val fileName = uri.lastPathSegment ?: "model.gguf"
    val cacheFile = File(context.cacheDir, fileName)
    inputStream?.use { input ->
      cacheFile.outputStream().use { output ->
        input.copyTo(output)
      }
    }
    cacheFile.absolutePath
  } catch (e: Exception) {
    null
  }
}
