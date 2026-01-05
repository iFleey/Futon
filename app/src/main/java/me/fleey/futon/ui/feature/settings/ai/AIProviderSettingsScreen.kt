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

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import me.fleey.futon.R
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.feedback.FutonDialog
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.component.inputs.FutonInput
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun AIProviderSettingsScreen(
  onBack: () -> Unit,
  onNavigateToProviderDetail: (String) -> Unit,
  viewModel: AIProviderSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val lifecycleOwner = LocalLifecycleOwner.current

  LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      viewModel.onEvent(ProviderUiEvent.RefreshProviders)
    }
  }

  LaunchedEffect(uiState.navigateToProviderId) {
    uiState.navigateToProviderId?.let { providerId ->
      onNavigateToProviderDetail(providerId)
      viewModel.onEvent(ProviderUiEvent.NavigationHandled)
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_ai_provider),
        onBackClick = onBack,
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = { viewModel.onEvent(ProviderUiEvent.ShowAddProviderDialog) },
        containerColor = MaterialTheme.colorScheme.primary,
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = stringResource(R.string.provider_add),
        )
      }
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    FutonLoadingOverlay(loading = uiState.isLoading) {
      ProviderListContent(
        providers = uiState.providers,
        onProviderClick = { onNavigateToProviderDetail(it.id) },
        onProviderEnabledChange = { provider, enabled ->
          viewModel.onEvent(ProviderUiEvent.ToggleProviderEnabled(provider.id, enabled))
        },
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
      )
    }
  }

  if (uiState.showAddProviderDialog) {
    AddProviderDialog(
      onDismiss = { viewModel.onEvent(ProviderUiEvent.DismissAddProviderDialog) },
      onConfirm = { name, protocol ->
        viewModel.onEvent(ProviderUiEvent.CreateProvider(name, protocol, protocol.getDefaultBaseUrl()))
      },
    )
  }

  uiState.errorMessage?.let { error ->
    FutonDialog(
      title = stringResource(R.string.error_unknown),
      message = error,
      confirmText = stringResource(R.string.action_confirm),
      onConfirm = { viewModel.onEvent(ProviderUiEvent.DismissError) },
      onDismiss = { viewModel.onEvent(ProviderUiEvent.DismissError) },
    )
  }
}

@Composable
private fun ProviderListContent(
  providers: List<Provider>,
  onProviderClick: (Provider) -> Unit,
  onProviderEnabledChange: (Provider, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sortedProviders = remember(providers) {
    providers.sortedWith(
      compareByDescending<Provider> { it.enabled }
        .thenByDescending { it.updatedAt },
    )
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    itemsIndexed(
      items = sortedProviders,
      key = { _, provider -> provider.id },
    ) { index, provider ->
      val shape = when {
        sortedProviders.size == 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = 4.dp,
          bottomEnd = 4.dp,
        )

        index == sortedProviders.lastIndex -> RoundedCornerShape(
          topStart = 4.dp,
          topEnd = 4.dp,
          bottomStart = 16.dp,
          bottomEnd = 16.dp,
        )

        else -> RoundedCornerShape(4.dp)
      }

      ProviderListItem(
        provider = provider,
        onClick = { onProviderClick(provider) },
        onEnabledChange = { onProviderEnabledChange(provider, it) },
        shape = shape,
        modifier = Modifier.animateItem(),
      )
    }

    item {
      Spacer(modifier = Modifier.height(72.dp))
    }
  }
}

@Composable
private fun ProviderListItem(
  provider: Provider,
  onClick: () -> Unit,
  onEnabledChange: (Boolean) -> Unit,
  shape: Shape,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          painter = painterResource(provider.getIconRes()),
          contentDescription = null,
          modifier = Modifier.size(36.dp),
          tint = Color.Unspecified,
        )
      }

      Spacer(modifier = Modifier.width(16.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = provider.name,
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
          text = stringResource(provider.protocol.displayNameRes),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )

        if (provider.selectedModelId != null) {
          Text(
            text = provider.selectedModelId,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.statusPositive,
          )
        } else if (!provider.isConfigured()) {
          Text(
            text = stringResource(R.string.provider_not_configured),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.statusWarning,
          )
        } else {
          Text(
            text = stringResource(R.string.provider_no_model_selected),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }

      FutonSwitch(
        checked = provider.enabled,
        onCheckedChange = onEnabledChange,
        enabled = provider.canBeEnabled() || provider.enabled,
      )
    }
  }
}

@Composable
private fun AddProviderDialog(
  onDismiss: () -> Unit,
  onConfirm: (name: String, protocol: ApiProtocol) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var selectedProtocol by remember { mutableStateOf(ApiProtocol.OPENAI_COMPATIBLE) }
  var protocolExpanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.provider_create_title), style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Column {
          Text(
            text = stringResource(R.string.provider_name),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(6.dp))
          FutonInput(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.provider_name_hint),
            leadingIcon = FutonIcons.Edit,
          )
        }

        Column {
          Text(
            text = stringResource(R.string.provider_select_protocol),
            style = MaterialTheme.typography.titleSmall,
            color = FutonTheme.colors.textNormal,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Box {
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { protocolExpanded = true },
              shape = FutonShapes.InputShape,
              color = FutonTheme.colors.channelTextarea,
              border = BorderStroke(1.dp, FutonTheme.colors.interactiveMuted),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = FutonIcons.Settings,
                  contentDescription = null,
                  tint = FutonTheme.colors.interactiveNormal,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(selectedProtocol.displayNameRes),
                  style = MaterialTheme.typography.bodyMedium,
                  color = FutonTheme.colors.textNormal,
                  modifier = Modifier.weight(1f),
                )
                Icon(
                  imageVector = if (protocolExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = null,
                  tint = FutonTheme.colors.interactiveNormal,
                )
              }
            }
            DropdownMenu(
              expanded = protocolExpanded,
              onDismissRequest = { protocolExpanded = false },
            ) {
              ApiProtocol.entries.forEach { protocol ->
                DropdownMenuItem(
                  text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                        text = stringResource(protocol.displayNameRes),
                        style = MaterialTheme.typography.bodyMedium,
                      )
                      if (protocol == selectedProtocol) {
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
                    selectedProtocol = protocol
                    protocolExpanded = false
                  },
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      FutonButton(
        text = stringResource(R.string.provider_create),
        onClick = {
          if (name.isNotBlank()) {
            onConfirm(name.trim(), selectedProtocol)
          }
        },
        enabled = name.isNotBlank(),
      )
    },
    dismissButton = {
      FutonButton(
        text = stringResource(R.string.action_cancel),
        onClick = onDismiss,
        style = ButtonStyle.Secondary,
      )
    },
    shape = FutonShapes.DialogShape,
    containerColor = MaterialTheme.colorScheme.surface,
  )
}

private fun ApiProtocol.getDefaultBaseUrl(): String = when (this) {
  ApiProtocol.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
  ApiProtocol.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
  ApiProtocol.ANTHROPIC -> "https://api.anthropic.com/v1"
  ApiProtocol.OLLAMA -> "http://localhost:11434"
}
