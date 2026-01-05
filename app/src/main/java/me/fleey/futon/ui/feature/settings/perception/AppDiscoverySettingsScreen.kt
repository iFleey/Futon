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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.apps.models.InstalledApp
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsNavigationItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSliderItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppDiscoverySettingsScreen(
  onBack: () -> Unit,
  viewModel: AppDiscoverySettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  var showAliasDialog by remember { mutableStateOf<InstalledApp?>(null) }
  var showResetDialog by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      snackbarHostState.showSnackbar(context.getString(R.string.settings_saved))
      viewModel.onEvent(AppDiscoveryUiEvent.DismissSuccess)
    }
  }

  if (showResetDialog) {
    AlertDialog(
      onDismissRequest = { showResetDialog = false },
      title = { Text(stringResource(R.string.app_discovery_reset)) },
      text = { Text(stringResource(R.string.app_discovery_reset_confirm)) },
      confirmButton = {
        Button(
          onClick = {
            viewModel.onEvent(AppDiscoveryUiEvent.ResetToDefaults)
            showResetDialog = false
          },
        ) {
          Text(stringResource(R.string.common_ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetDialog = false }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }

  showAliasDialog?.let { app ->
    AppAliasDialog(
      app = app,
      currentAliases = uiState.discoveredApps.find { it.packageName == app.packageName }?.userAliases
        ?: emptyList(),
      onDismiss = { showAliasDialog = null },
      onSave = { aliases ->
        viewModel.onEvent(AppDiscoveryUiEvent.SetAppAliases(app.packageName, aliases))
        showAliasDialog = null
      },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_app_discovery),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item { Spacer(modifier = Modifier.height(8.dp)) }

      item {
        SettingsGroup {
          item {
            SettingsSwitchItem(
              title = stringResource(R.string.app_discovery_enable),
              description = stringResource(R.string.app_discovery_enable_description),
              checked = uiState.enabled,
              onCheckedChange = {
                viewModel.onEvent(AppDiscoveryUiEvent.EnabledChanged(it))
              },
              leadingIcon = FutonIcons.Apps,
            )
          }
        }
      }

      if (uiState.enabled) {
        item {
          SettingsGroup {
            item {
              SettingsSwitchItem(
                title = stringResource(R.string.app_discovery_include_system),
                description = stringResource(R.string.app_discovery_include_system_description),
                checked = uiState.includeSystemApps,
                onCheckedChange = {
                  viewModel.onEvent(AppDiscoveryUiEvent.IncludeSystemAppsChanged(it))
                },
                leadingIcon = FutonIcons.Settings,
              )
            }
            item {
              SettingsSliderItem(
                title = stringResource(R.string.app_discovery_max_apps),
                value = uiState.maxAppsInContext.toFloat(),
                onValueChange = {
                  viewModel.onEvent(AppDiscoveryUiEvent.MaxAppsChanged(it.toInt()))
                },
                valueRange = 10f..100f,
                steps = 8,
                leadingIcon = FutonIcons.Filter,
                description = stringResource(R.string.app_discovery_max_apps_description),
                valueFormatter = {
                  context.getString(R.string.app_discovery_max_apps_value, it.toInt())
                },
              )
            }
          }
        }

        item {
          SettingsGroup(title = stringResource(R.string.app_discovery_discovered_apps)) {
            item {
              SettingsNavigationItem(
                title = stringResource(R.string.app_discovery_view_apps),
                description = stringResource(
                  R.string.app_discovery_apps_count,
                  uiState.discoveredApps.size,
                ),
                leadingIcon = FutonIcons.Apps,
                onClick = { viewModel.onEvent(AppDiscoveryUiEvent.ToggleAppsExpanded) },
              )
            }
          }
        }

        item {
          AnimatedVisibility(
            visible = uiState.appsExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
          ) {
            if (uiState.isLoadingApps) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(32.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator()
              }
            } else {
              DiscoveredAppsList(
                apps = uiState.discoveredApps,
                hiddenApps = uiState.hiddenApps,
                onHideApp = { viewModel.onEvent(AppDiscoveryUiEvent.HideApp(it)) },
                onUnhideApp = { viewModel.onEvent(AppDiscoveryUiEvent.UnhideApp(it)) },
                onEditAliases = { showAliasDialog = it },
                getCategoryName = { categoryId ->
                  viewModel.getCategoryName(categoryId, context)
                },
              )
            }
          }
        }

        if (uiState.hiddenApps.isNotEmpty()) {
          item {
            SettingsGroup(title = stringResource(R.string.app_discovery_hidden_apps)) {
              item {
                SettingsNavigationItem(
                  title = stringResource(R.string.app_discovery_hidden_apps),
                  description = stringResource(
                    R.string.app_discovery_hidden_apps_count,
                    uiState.hiddenApps.size,
                  ),
                  leadingIcon = FutonIcons.VisibilityOff,
                  onClick = { viewModel.onEvent(AppDiscoveryUiEvent.ToggleHiddenExpanded) },
                )
              }
            }
          }

          item {
            AnimatedVisibility(
              visible = uiState.hiddenExpanded,
              enter = expandVertically(),
              exit = shrinkVertically(),
            ) {
              HiddenAppsList(
                hiddenPackages = uiState.hiddenApps,
                onUnhide = { viewModel.onEvent(AppDiscoveryUiEvent.UnhideApp(it)) },
              )
            }
          }
        }
      }

      item {
        SettingsGroup {
          item {
            SettingsItem(
              title = stringResource(R.string.app_discovery_refresh),
              description = stringResource(R.string.app_discovery_refresh_description),
              leadingIcon = FutonIcons.Refresh,
              onClick = { viewModel.onEvent(AppDiscoveryUiEvent.RefreshApps) },
            )
          }
          item {
            SettingsItem(
              title = stringResource(R.string.app_discovery_reset),
              leadingIcon = FutonIcons.Delete,
              onClick = { showResetDialog = true },
            )
          }
        }
      }

      item { Spacer(modifier = Modifier.height(32.dp)) }
    }
  }
}

@Composable
private fun DiscoveredAppsList(
  apps: List<InstalledApp>,
  hiddenApps: List<String>,
  onHideApp: (String) -> Unit,
  onUnhideApp: (String) -> Unit,
  onEditAliases: (InstalledApp) -> Unit,
  getCategoryName: (String?) -> String,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    apps.forEachIndexed { index, app ->
      val isHidden = app.packageName in hiddenApps
      val shape = when {
        apps.size == 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(
          topStart = 16.dp, topEnd = 16.dp,
          bottomStart = 4.dp, bottomEnd = 4.dp,
        )

        index == apps.lastIndex -> RoundedCornerShape(
          topStart = 4.dp, topEnd = 4.dp,
          bottomStart = 16.dp, bottomEnd = 16.dp,
        )

        else -> RoundedCornerShape(4.dp)
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FutonTheme.colors.backgroundSecondary,
        shape = shape,
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = FutonSizes.ListItemHorizontalPadding,
              vertical = 12.dp,
            ),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (app.isSystemApp) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                  color = FutonTheme.colors.textMuted.copy(alpha = 0.2f),
                  shape = MaterialTheme.shapes.small,
                ) {
                  Text(
                    text = stringResource(R.string.app_discovery_system_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = FutonTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                  )
                }
              }
            }
            Text(
              text = app.packageName,
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              app.categoryId?.let { catId ->
                Text(
                  text = getCategoryName(catId),
                  style = MaterialTheme.typography.labelSmall,
                  color = FutonTheme.colors.interactiveNormal,
                )
              }
              if (app.userAliases.isNotEmpty()) {
                Text(
                  text = stringResource(
                    R.string.app_discovery_aliases_count,
                    app.userAliases.size,
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = FutonTheme.colors.statusPositive,
                )
              }
            }
          }

          IconButton(onClick = { onEditAliases(app) }) {
            Icon(
              imageVector = FutonIcons.Edit,
              contentDescription = stringResource(R.string.app_discovery_edit_aliases),
              tint = FutonTheme.colors.textMuted,
              modifier = Modifier.size(20.dp),
            )
          }

          IconButton(
            onClick = {
              if (isHidden) onUnhideApp(app.packageName)
              else onHideApp(app.packageName)
            },
          ) {
            Icon(
              imageVector = if (isHidden) FutonIcons.Visibility else FutonIcons.VisibilityOff,
              contentDescription = if (isHidden) {
                stringResource(R.string.app_discovery_unhide)
              } else {
                stringResource(R.string.app_discovery_hide)
              },
              tint = if (isHidden) FutonTheme.colors.statusWarning else FutonTheme.colors.textMuted,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HiddenAppsList(
  hiddenPackages: List<String>,
  onUnhide: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    hiddenPackages.forEachIndexed { index, packageName ->
      val shape = when {
        hiddenPackages.size == 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(
          topStart = 16.dp, topEnd = 16.dp,
          bottomStart = 4.dp, bottomEnd = 4.dp,
        )

        index == hiddenPackages.lastIndex -> RoundedCornerShape(
          topStart = 4.dp, topEnd = 4.dp,
          bottomStart = 16.dp, bottomEnd = 16.dp,
        )

        else -> RoundedCornerShape(4.dp)
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FutonTheme.colors.backgroundSecondary,
        shape = shape,
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = FutonSizes.ListItemHorizontalPadding,
              vertical = 12.dp,
            ),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = FutonIcons.VisibilityOff,
            contentDescription = null,
            tint = FutonTheme.colors.textMuted,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = packageName,
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          TextButton(onClick = { onUnhide(packageName) }) {
            Text(stringResource(R.string.app_discovery_unhide))
          }
        }
      }
    }
  }
}

@Composable
private fun AppAliasDialog(
  app: InstalledApp,
  currentAliases: List<String>,
  onDismiss: () -> Unit,
  onSave: (List<String>) -> Unit,
) {
  var aliasText by remember { mutableStateOf(currentAliases.joinToString("\n")) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.app_discovery_edit_aliases_title, app.appName),
        style = MaterialTheme.typography.titleMedium,
      )
    },
    text = {
      Column {
        Text(
          text = stringResource(R.string.app_discovery_aliases_hint),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
          value = aliasText,
          onValueChange = { aliasText = it },
          modifier = Modifier.fillMaxWidth(),
          placeholder = {
            Text(stringResource(R.string.app_discovery_aliases_placeholder))
          },
          minLines = 3,
          maxLines = 6,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val aliases = aliasText
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
          onSave(aliases)
        },
      ) {
        Text(stringResource(R.string.common_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.common_cancel))
      }
    },
  )
}
