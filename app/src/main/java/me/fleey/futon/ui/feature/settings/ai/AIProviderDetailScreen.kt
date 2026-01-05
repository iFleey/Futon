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
package me.fleey.futon.ui.feature.settings.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ModelConfig
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.data.ai.models.ProviderIcons
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.feedback.FutonDialog
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.component.inputs.FutonInput
import me.fleey.futon.ui.designsystem.component.inputs.FutonPasswordInput
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun AIProviderDetailScreen(
  providerId: String,
  onBack: () -> Unit,
  viewModel: AIProviderDetailViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(providerId) {
    viewModel.loadProvider(providerId)
  }

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      snackbarHostState.showSnackbar(context.getString(R.string.settings_saved))
      viewModel.onEvent(DetailUiEvent.DismissSaveSuccess)
    }
  }

  LaunchedEffect(uiState.providerDeleted) {
    if (uiState.providerDeleted) {
      onBack()
    }
  }

  val provider = uiState.editingProvider

  Scaffold(
    topBar = {
      FutonTopBar(
        title = provider?.name ?: stringResource(R.string.settings_ai_provider),
        onBackClick = onBack,
        actions = {
          if (provider != null) {
            IconButton(onClick = { viewModel.onEvent(DetailUiEvent.ShowDeleteDialog) }) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.provider_delete),
                tint = FutonTheme.colors.statusDanger,
              )
            }
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    FutonLoadingOverlay(loading = uiState.isLoading) {
      if (provider != null) {
        ProviderDetailContent(
          provider = provider,
          models = uiState.providerModels,
          selectedModelId = uiState.selectedModelId,
          hasUnsavedChanges = uiState.hasUnsavedChanges,
          isSaving = uiState.isSaving,
          isLoadingModels = uiState.isLoadingModels,
          connectionTestResult = uiState.connectionTestResult,
          onProviderNameChange = { viewModel.onEvent(DetailUiEvent.ProviderNameChanged(it)) },
          onApiKeyChange = { viewModel.onEvent(DetailUiEvent.ApiKeyChanged(it)) },
          onBaseUrlChange = { viewModel.onEvent(DetailUiEvent.BaseUrlChanged(it)) },
          onEnabledChange = { viewModel.onEvent(DetailUiEvent.ProviderEnabledChanged(it)) },
          onIconChange = { viewModel.onEvent(DetailUiEvent.ProviderIconChanged(it)) },
          onSave = { viewModel.onEvent(DetailUiEvent.SaveProviderConfig) },
          onRefreshModels = { viewModel.onEvent(DetailUiEvent.FetchAvailableModels) },
          onTestConnection = { viewModel.onEvent(DetailUiEvent.TestConnection) },
          onDismissConnectionTestResult = { viewModel.onEvent(DetailUiEvent.DismissConnectionTestResult) },
          onAddModel = { viewModel.onEvent(DetailUiEvent.ShowAddModelDialog) },
          onEditModel = { viewModel.onEvent(DetailUiEvent.ShowEditModelDialog(it)) },
          onSetDefaultModel = { viewModel.onEvent(DetailUiEvent.SetActiveModel(it)) },
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        )
      }
    }
  }

  if (uiState.showDeleteDialog) {
    FutonDialog(
      title = stringResource(R.string.provider_delete_title),
      message = stringResource(R.string.provider_delete_message, provider?.name ?: ""),
      confirmText = stringResource(R.string.action_delete),
      dismissText = stringResource(R.string.action_cancel),
      onConfirm = { viewModel.onEvent(DetailUiEvent.ConfirmDelete) },
      onDismiss = { viewModel.onEvent(DetailUiEvent.DismissDeleteDialog) },
    )
  }

  if (uiState.showAddModelDialog) {
    AddModelDialog(
      availableModels = uiState.availableModels,
      existingModelIds = uiState.providerModels.map { it.modelId }.toSet(),
      isLoadingModels = uiState.isLoadingModels,
      onRefreshModels = { viewModel.onEvent(DetailUiEvent.FetchAvailableModels) },
      onDismiss = { viewModel.onEvent(DetailUiEvent.DismissAddModelDialog) },
      onConfirm = { modelId, displayName ->
        viewModel.onEvent(DetailUiEvent.CreateModel(modelId, displayName))
      },
    )
  }

  if (uiState.showEditModelDialog && uiState.editingModel != null) {
    EditModelDialog(
      model = uiState.editingModel!!,
      onDismiss = { viewModel.onEvent(DetailUiEvent.DismissEditModelDialog) },
      onConfirm = { viewModel.onEvent(DetailUiEvent.UpdateModel(it)) },
      onDelete = { viewModel.onEvent(DetailUiEvent.DeleteModel(it)) },
    )
  }

  uiState.errorMessage?.let { error ->
    FutonDialog(
      title = stringResource(R.string.error_unknown),
      message = error,
      confirmText = stringResource(R.string.action_confirm),
      onConfirm = { viewModel.onEvent(DetailUiEvent.DismissError) },
      onDismiss = { viewModel.onEvent(DetailUiEvent.DismissError) },
    )
  }
}


@Composable
private fun ProviderDetailContent(
  provider: Provider,
  models: List<ModelConfig>,
  selectedModelId: String?,
  hasUnsavedChanges: Boolean,
  isSaving: Boolean,
  isLoadingModels: Boolean,
  connectionTestResult: ConnectionTestResult,
  onProviderNameChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onBaseUrlChange: (String) -> Unit,
  onEnabledChange: (Boolean) -> Unit,
  onIconChange: (String?) -> Unit,
  onSave: () -> Unit,
  onRefreshModels: () -> Unit,
  onTestConnection: () -> Unit,
  onDismissConnectionTestResult: () -> Unit,
  onAddModel: () -> Unit,
  onEditModel: (ModelConfig) -> Unit,
  onSetDefaultModel: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp),
  ) {
    Spacer(modifier = Modifier.height(8.dp))

    SettingsGroup(title = stringResource(R.string.provider_config_title, provider.name)) {
      item {
        ProviderHeader(
          provider = provider,
          isEnabled = provider.enabled,
          onEnabledChange = onEnabledChange,
          onIconChange = onIconChange,
        )
      }
      item {
        ProviderNameField(name = provider.name, onNameChange = onProviderNameChange)
      }
      item {
        ApiKeyField(apiKey = provider.apiKey, onApiKeyChange = onApiKeyChange)
      }
      item {
        BaseUrlField(baseUrl = provider.baseUrl, onBaseUrlChange = onBaseUrlChange)
      }
      item {
        TestConnectionField(
          connectionTestResult = connectionTestResult,
          onTestConnection = onTestConnection,
          onDismissConnectionTestResult = onDismissConnectionTestResult,
        )
      }
      item {
        SaveButtonField(
          hasUnsavedChanges = hasUnsavedChanges,
          isSaving = isSaving,
          apiKeyBlank = provider.apiKey.isBlank() && provider.protocol != ApiProtocol.OLLAMA,
          onSave = onSave,
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.provider_model_configs),
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textMuted,
        modifier = Modifier.weight(1f),
      )
      if (isLoadingModels) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
          color = FutonTheme.colors.interactiveNormal,
        )
        Spacer(modifier = Modifier.width(8.dp))
      }
      IconButton(onClick = onRefreshModels) {
        Icon(
          imageVector = FutonIcons.Refresh,
          contentDescription = stringResource(R.string.provider_refresh_models),
          tint = FutonTheme.colors.interactiveNormal,
        )
      }
      IconButton(onClick = onAddModel) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = stringResource(R.string.provider_add_model),
          tint = FutonTheme.colors.interactiveNormal,
        )
      }
    }

    ModelConfigsList(
      models = models,
      selectedModelId = selectedModelId,
      isProviderConfigured = provider.isConfigured(),
      onEditModel = onEditModel,
      onSetDefaultModel = onSetDefaultModel,
    )

    Spacer(modifier = Modifier.height(32.dp))
  }
}

@Composable
private fun ProviderHeader(
  provider: Provider,
  isEnabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onIconChange: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showIconPicker by remember { mutableStateOf(false) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box {
      Surface(
        onClick = { showIconPicker = true },
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
      ) {
        Icon(
          painter = painterResource(provider.getIconRes()),
          contentDescription = provider.name,
          modifier = Modifier.size(40.dp),
          tint = Color.Unspecified,
        )
      }

      DropdownMenu(
        expanded = showIconPicker,
        onDismissRequest = { showIconPicker = false },
      ) {
        ProviderIcons.availableIcons.forEach { iconKey ->
          DropdownMenuItem(
            text = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  painter = painterResource(ProviderIcons.getIconRes(iconKey)),
                  contentDescription = iconKey,
                  modifier = Modifier.size(24.dp),
                  tint = Color.Unspecified,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = iconKey.replaceFirstChar { it.uppercase() })
                if (iconKey == provider.iconKey) {
                  Spacer(modifier = Modifier.width(8.dp))
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = FutonTheme.colors.statusPositive,
                    modifier = Modifier.size(16.dp),
                  )
                }
              }
            },
            onClick = {
              onIconChange(iconKey)
              showIconPicker = false
            },
          )
        }
        DropdownMenuItem(
          text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                painter = painterResource(ProviderIcons.getIconRes(null)),
                contentDescription = "custom",
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(text = stringResource(R.string.provider_icon_custom))
              if (provider.iconKey == null || !ProviderIcons.availableIcons.contains(provider.iconKey)) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = FutonTheme.colors.statusPositive,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          },
          onClick = {
            onIconChange(null)
            showIconPicker = false
          },
        )
      }
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = provider.name,
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textNormal,
      )
      Text(
        text = stringResource(provider.protocol.displayNameRes),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      FutonSwitch(checked = isEnabled, onCheckedChange = onEnabledChange)
      Text(
        text = if (isEnabled) stringResource(R.string.provider_enabled) else stringResource(R.string.provider_disabled),
        style = MaterialTheme.typography.labelSmall,
        color = if (isEnabled) FutonTheme.colors.statusPositive else FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun ProviderNameField(name: String, onNameChange: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
  ) {
    Text(
      text = stringResource(R.string.provider_name),
      style = MaterialTheme.typography.titleSmall,
      color = FutonTheme.colors.textNormal,
    )
    Spacer(modifier = Modifier.height(6.dp))
    FutonInput(value = name, onValueChange = onNameChange, placeholder = "My Provider", leadingIcon = FutonIcons.Edit)
  }
}

@Composable
private fun ApiKeyField(apiKey: String, onApiKeyChange: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
  ) {
    Text(
      text = stringResource(R.string.provider_api_key),
      style = MaterialTheme.typography.titleSmall,
      color = FutonTheme.colors.textNormal,
    )
    Spacer(modifier = Modifier.height(6.dp))
    FutonPasswordInput(value = apiKey, onValueChange = onApiKeyChange, placeholder = "sk-...")
  }
}

@Composable
private fun BaseUrlField(baseUrl: String, onBaseUrlChange: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
  ) {
    Text(
      text = stringResource(R.string.provider_base_url),
      style = MaterialTheme.typography.titleSmall,
      color = FutonTheme.colors.textNormal,
    )
    Spacer(modifier = Modifier.height(6.dp))
    FutonInput(
      value = baseUrl,
      onValueChange = onBaseUrlChange,
      placeholder = "https://api.example.com/v1",
      leadingIcon = FutonIcons.Link,
    )
  }
}


@Composable
private fun TestConnectionField(
  connectionTestResult: ConnectionTestResult,
  onTestConnection: () -> Unit,
  onDismissConnectionTestResult: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FutonButton(
      text = when (connectionTestResult) {
        ConnectionTestResult.Testing -> stringResource(R.string.provider_testing)
        else -> stringResource(R.string.provider_test_connection)
      },
      onClick = onTestConnection,
      loading = connectionTestResult == ConnectionTestResult.Testing,
      enabled = connectionTestResult != ConnectionTestResult.Testing,
      icon = FutonIcons.Refresh,
      style = ButtonStyle.Secondary,
      modifier = Modifier.fillMaxWidth(),
    )

    AnimatedVisibility(
      visible = connectionTestResult is ConnectionTestResult.Success || connectionTestResult is ConnectionTestResult.Failure,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      when (connectionTestResult) {
        is ConnectionTestResult.Success -> InfoBanner(
          message = connectionTestResult.message,
          type = BannerType.Success,
          onDismiss = onDismissConnectionTestResult,
        )

        is ConnectionTestResult.Failure -> InfoBanner(
          message = connectionTestResult.error,
          type = BannerType.Error,
          onDismiss = onDismissConnectionTestResult,
        )

        else -> {}
      }
    }
  }
}

@Composable
private fun SaveButtonField(
  hasUnsavedChanges: Boolean,
  isSaving: Boolean,
  apiKeyBlank: Boolean,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.CardPadding),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FutonButton(
      text = if (hasUnsavedChanges) stringResource(R.string.provider_save_changes) else stringResource(R.string.provider_saved),
      onClick = onSave,
      loading = isSaving,
      enabled = hasUnsavedChanges && !isSaving,
      icon = FutonIcons.Save,
      style = if (hasUnsavedChanges) ButtonStyle.Primary else ButtonStyle.Secondary,
      modifier = Modifier.fillMaxWidth(),
    )

    AnimatedVisibility(
      visible = apiKeyBlank,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      InfoBanner(message = stringResource(R.string.provider_api_key_empty_warning), type = BannerType.Warning)
    }
  }
}

@Composable
private fun ModelConfigsList(
  models: List<ModelConfig>,
  selectedModelId: String?,
  isProviderConfigured: Boolean,
  onEditModel: (ModelConfig) -> Unit,
  onSetDefaultModel: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (models.isEmpty()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = FutonTheme.colors.backgroundSecondary,
      ) {
        Text(
          text = stringResource(R.string.provider_no_models),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(16.dp),
        )
      }
    } else {
      models.forEachIndexed { index, model ->
        val shape = when {
          models.size == 1 -> RoundedCornerShape(16.dp)
          index == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
          )

          index == models.lastIndex -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp,
          )

          else -> RoundedCornerShape(4.dp)
        }

        val isSelected = model.modelId == selectedModelId

        ModelConfigItem(
          model = model,
          isSelected = isSelected,
          canSetDefault = isProviderConfigured && !isSelected,
          onEditClick = { onEditModel(model) },
          onSetDefaultClick = { onSetDefaultModel(model.modelId) },
          shape = shape,
        )
      }
    }
  }
}

@Composable
private fun ModelConfigItem(
  model: ModelConfig,
  isSelected: Boolean,
  canSetDefault: Boolean,
  onEditClick: () -> Unit,
  onSetDefaultClick: () -> Unit,
  shape: Shape,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = model.getDisplayName,
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal,
          )
          if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = FutonTheme.colors.statusPositive.copy(alpha = 0.15f),
              shape = FutonShapes.InputShape,
            ) {
              Text(
                text = stringResource(R.string.model_default),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusPositive,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        if (model.modelId != model.displayName && model.displayName.isNotBlank()) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = model.modelId,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
        if (model.hasPricing()) {
          Text(
            text = stringResource(R.string.provider_model_pricing, model.inputPrice, model.outputPrice),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }

      if (canSetDefault) {
        Surface(
          onClick = onSetDefaultClick,
          shape = FutonShapes.InputShape,
          color = FutonTheme.colors.interactiveNormal.copy(alpha = 0.1f),
        ) {
          Text(
            text = stringResource(R.string.model_use),
            style = MaterialTheme.typography.labelMedium,
            color = FutonTheme.colors.interactiveNormal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
      }

      IconButton(onClick = onEditClick, modifier = Modifier.size(32.dp)) {
        Icon(
          imageVector = FutonIcons.Edit,
          contentDescription = stringResource(R.string.action_edit),
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

private enum class BannerType { Info, Success, Warning, Error }

@Composable
private fun InfoBanner(
  message: String,
  type: BannerType,
  modifier: Modifier = Modifier,
  onDismiss: (() -> Unit)? = null,
) {
  val backgroundColor = when (type) {
    BannerType.Info -> FutonTheme.colors.interactiveNormal.copy(alpha = 0.1f)
    BannerType.Success -> FutonTheme.colors.statusPositive.copy(alpha = 0.1f)
    BannerType.Warning -> FutonTheme.colors.statusWarning.copy(alpha = 0.1f)
    BannerType.Error -> FutonTheme.colors.statusDanger.copy(alpha = 0.1f)
  }
  val contentColor = when (type) {
    BannerType.Info -> FutonTheme.colors.interactiveNormal
    BannerType.Success -> FutonTheme.colors.statusPositive
    BannerType.Warning -> FutonTheme.colors.statusWarning
    BannerType.Error -> FutonTheme.colors.statusDanger
  }
  val icon = when (type) {
    BannerType.Info -> FutonIcons.Info
    BannerType.Success -> Icons.Default.Check
    BannerType.Warning -> FutonIcons.Warning
    BannerType.Error -> FutonIcons.Error
  }

  Surface(modifier = modifier.fillMaxWidth(), shape = FutonShapes.CardShape, color = backgroundColor) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
        modifier = Modifier.weight(1f),
      )
      if (onDismiss != null) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
          Icon(
            imageVector = FutonIcons.Close,
            contentDescription = stringResource(R.string.action_cancel),
            tint = contentColor,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}


@Composable
private fun AddModelDialog(
  availableModels: List<String>,
  existingModelIds: Set<String>,
  isLoadingModels: Boolean,
  onRefreshModels: () -> Unit,
  onDismiss: () -> Unit,
  onConfirm: (modelId: String, displayName: String) -> Unit,
) {
  var modelId by remember { mutableStateOf("") }
  var displayName by remember { mutableStateOf("") }
  var showModelPicker by remember { mutableStateOf(false) }

  val selectableModels = remember(availableModels, existingModelIds) {
    availableModels.filter { it !in existingModelIds }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.model_add), style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.model_id),
              style = MaterialTheme.typography.titleSmall,
              color = FutonTheme.colors.textNormal,
              modifier = Modifier.weight(1f),
            )
            if (isLoadingModels) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = FutonTheme.colors.interactiveNormal,
              )
            } else {
              IconButton(onClick = onRefreshModels, modifier = Modifier.size(24.dp)) {
                Icon(
                  imageVector = FutonIcons.Refresh,
                  contentDescription = stringResource(R.string.provider_refresh_models),
                  tint = FutonTheme.colors.interactiveNormal,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Box {
            FutonInput(
              value = modelId,
              onValueChange = { modelId = it },
              placeholder = stringResource(R.string.model_id_hint),
              leadingIcon = FutonIcons.Model,
              trailingIcon = if (selectableModels.isNotEmpty()) {
                {
                  IconButton(onClick = { showModelPicker = !showModelPicker }) {
                    Icon(
                      imageVector = if (showModelPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                      contentDescription = null,
                      tint = FutonTheme.colors.interactiveNormal,
                    )
                  }
                }
              } else null,
            )
            DropdownMenu(
              expanded = showModelPicker && selectableModels.isNotEmpty(),
              onDismissRequest = { showModelPicker = false },
            ) {
              selectableModels.forEach { model ->
                DropdownMenuItem(
                  text = {
                    Text(
                      text = model,
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                    )
                  },
                  onClick = {
                    modelId = model
                    if (displayName.isBlank()) {
                      displayName = model
                    }
                    showModelPicker = false
                  },
                )
              }
            }
          }
          if (selectableModels.isNotEmpty()) {
            Text(
              text = stringResource(R.string.model_available_count, selectableModels.size),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
        Column {
          Text(
            text = stringResource(R.string.model_display_name),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(4.dp))
          FutonInput(
            value = displayName,
            onValueChange = { displayName = it },
            placeholder = stringResource(R.string.model_display_name_hint),
            leadingIcon = FutonIcons.Edit,
          )
        }
      }
    },
    confirmButton = {
      FutonButton(
        text = stringResource(R.string.action_confirm),
        onClick = { if (modelId.isNotBlank()) onConfirm(modelId.trim(), displayName.trim()) },
        enabled = modelId.isNotBlank(),
      )
    },
    dismissButton = {
      FutonButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, style = ButtonStyle.Secondary)
    },
    shape = FutonShapes.DialogShape,
    containerColor = MaterialTheme.colorScheme.surface,
  )
}

@Composable
private fun EditModelDialog(
  model: ModelConfig,
  onDismiss: () -> Unit,
  onConfirm: (ModelConfig) -> Unit,
  onDelete: (String) -> Unit,
) {
  var displayName by remember { mutableStateOf(model.displayName) }
  var inputPrice by remember { mutableStateOf(model.inputPrice.toString()) }
  var outputPrice by remember { mutableStateOf(model.outputPrice.toString()) }
  var contextWindow by remember { mutableStateOf(model.contextWindow.toString()) }
  var supportsVision by remember { mutableStateOf(model.supportsVision) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  if (showDeleteConfirm) {
    FutonDialog(
      title = stringResource(R.string.model_delete_title),
      message = stringResource(R.string.model_delete_message, model.getDisplayName),
      confirmText = stringResource(R.string.action_delete),
      dismissText = stringResource(R.string.action_cancel),
      onConfirm = { onDelete(model.id) },
      onDismiss = { showDeleteConfirm = false },
    )
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.model_edit), style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
          Text(
            text = stringResource(R.string.model_id),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = FutonShapes.CardShape,
            color = FutonTheme.colors.backgroundTertiary,
          ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(imageVector = FutonIcons.Model, contentDescription = null, tint = FutonTheme.colors.textMuted)
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = model.modelId,
                style = MaterialTheme.typography.bodyMedium,
                color = FutonTheme.colors.textMuted,
              )
            }
          }
        }

        Column {
          Text(
            text = stringResource(R.string.model_display_name),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(4.dp))
          FutonInput(
            value = displayName,
            onValueChange = { displayName = it },
            placeholder = stringResource(R.string.model_display_name_hint),
            leadingIcon = FutonIcons.Edit,
          )
        }

        Text(
          text = stringResource(R.string.model_pricing),
          style = MaterialTheme.typography.titleSmall,
          color = FutonTheme.colors.textNormal,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.model_input_price),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FutonInput(
              value = inputPrice,
              onValueChange = { inputPrice = it },
              placeholder = "0.0",
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.model_output_price),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FutonInput(
              value = outputPrice,
              onValueChange = { outputPrice = it },
              placeholder = "0.0",
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
          }
        }

        Text(
          text = stringResource(R.string.model_price_unit),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )

        Column {
          Text(
            text = stringResource(R.string.model_context_window),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(4.dp))
          FutonInput(
            value = contextWindow,
            onValueChange = { contextWindow = it },
            placeholder = "128000",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = stringResource(R.string.model_supports_vision),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal,
            modifier = Modifier.weight(1f),
          )
          FutonSwitch(checked = supportsVision, onCheckedChange = { supportsVision = it })
        }

        FutonButton(
          text = stringResource(R.string.model_delete),
          onClick = { showDeleteConfirm = true },
          icon = Icons.Default.Delete,
          style = ButtonStyle.Danger,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      FutonButton(
        text = stringResource(R.string.action_save),
        onClick = {
          onConfirm(
            model.copy(
              displayName = displayName.trim(),
              inputPrice = inputPrice.toDoubleOrNull() ?: 0.0,
              outputPrice = outputPrice.toDoubleOrNull() ?: 0.0,
              contextWindow = contextWindow.toIntOrNull() ?: 128000,
              supportsVision = supportsVision,
            ),
          )
        },
      )
    },
    dismissButton = {
      FutonButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, style = ButtonStyle.Secondary)
    },
    shape = FutonShapes.DialogShape,
    containerColor = MaterialTheme.colorScheme.surface,
  )
}
