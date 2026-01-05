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
package me.fleey.futon.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.navigation.isDebugBuild
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(
  onNavigateToAIProvider: () -> Unit = {},
  onNavigateToAppearance: () -> Unit = {},
  onNavigateToLanguage: () -> Unit = {},
  onNavigateToAutomation: () -> Unit = {},
  onNavigateToLocalModel: () -> Unit = {},
  onNavigateToExecution: () -> Unit = {},
  onNavigateToPrivacy: () -> Unit = {},
  onNavigateToCapability: () -> Unit = {},
  onNavigateToPromptManagement: () -> Unit = {},
  onNavigateToAppDiscovery: () -> Unit = {},
  onNavigateToDaemon: () -> Unit = {},
  onNavigateToInferenceRouting: () -> Unit = {},
  onNavigateToInferenceMetrics: () -> Unit = {},
  onNavigateToIntegrations: () -> Unit = {},
  onNavigateToSomSettings: () -> Unit = {},
  onNavigateToDebugDashboard: () -> Unit = {},
  onNavigateToDebugSecurityLogs: () -> Unit = {},
  onNavigateToDebugChaosTesting: () -> Unit = {},
  onNavigateToAbout: () -> Unit = {},
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      FutonTopBar(title = stringResource(R.string.nav_settings))
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_appearance)) {
        item {
          SettingsItem(
            icon = FutonIcons.Theme,
            title = stringResource(R.string.settings_appearance),
            onClick = onNavigateToAppearance,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Language,
            title = stringResource(R.string.settings_language),
            onClick = onNavigateToLanguage,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_ai)) {
        item {
          SettingsItem(
            icon = FutonIcons.AI,
            title = stringResource(R.string.settings_ai_provider),
            onClick = onNavigateToAIProvider,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Model,
            title = stringResource(R.string.settings_local_model),
            onClick = onNavigateToLocalModel,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Tree,
            title = stringResource(R.string.settings_inference_routing),
            onClick = onNavigateToInferenceRouting,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Speed,
            title = stringResource(R.string.settings_inference_metrics),
            onClick = onNavigateToInferenceMetrics,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Prompt,
            title = stringResource(R.string.prompt_management_title),
            onClick = onNavigateToPromptManagement,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_automation)) {
        item {
          SettingsItem(
            icon = FutonIcons.Automation,
            title = stringResource(R.string.settings_automation),
            onClick = onNavigateToAutomation,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Touch,
            title = stringResource(R.string.settings_execution_layer),
            onClick = onNavigateToExecution,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.VisibilityOff,
            title = stringResource(R.string.settings_privacy),
            onClick = onNavigateToPrivacy,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_perception)) {
        item {
          SettingsItem(
            icon = FutonIcons.Perception,
            title = stringResource(R.string.settings_som),
            onClick = onNavigateToSomSettings,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Apps,
            title = stringResource(R.string.settings_app_discovery),
            onClick = onNavigateToAppDiscovery,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_system)) {
        item {
          SettingsItem(
            icon = FutonIcons.Root,
            title = stringResource(R.string.settings_daemon),
            onClick = onNavigateToDaemon,
          )
        }
        item {
          SettingsItem(
            icon = FutonIcons.Info,
            title = stringResource(R.string.settings_capability_status),
            onClick = onNavigateToCapability,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_integrations)) {
        item {
          SettingsItem(
            icon = FutonIcons.Link,
            title = stringResource(R.string.settings_integrations),
            onClick = onNavigateToIntegrations,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_category_other)) {
        item {
          SettingsItem(
            icon = FutonIcons.Info,
            title = stringResource(R.string.about_title),
            onClick = onNavigateToAbout,
          )
        }
      }

      // Debug (only in debug builds)
      if (isDebugBuild) {
        Spacer(modifier = Modifier.height(12.dp))

        SettingsGroup(title = stringResource(R.string.settings_category_debug)) {
          item {
            SettingsItem(
              icon = FutonIcons.Speed,
              title = stringResource(R.string.debug_dashboard_title),
              onClick = onNavigateToDebugDashboard,
              iconTint = FutonTheme.colors.statusWarning,
            )
          }
          item {
            SettingsItem(
              icon = FutonIcons.Security,
              title = stringResource(R.string.debug_security_logs_title),
              onClick = onNavigateToDebugSecurityLogs,
              iconTint = FutonTheme.colors.statusWarning,
            )
          }
          item {
            SettingsItem(
              icon = FutonIcons.Warning,
              title = stringResource(R.string.debug_chaos_testing_title),
              onClick = onNavigateToDebugChaosTesting,
              iconTint = FutonTheme.colors.statusDanger,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
fun SettingsItem(
  icon: ImageVector,
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  trailing: @Composable (() -> Unit)? = null,
  iconTint: Color = FutonTheme.colors.textMuted,
  verticalPadding: Dp = 16.dp,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    color = Color.Transparent,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = verticalPadding),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = iconTint,
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = FutonTheme.colors.textNormal,
        )
        if (subtitle != null) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
      if (trailing != null) {
        trailing()
      } else {
        Icon(
          imageVector = FutonIcons.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}
