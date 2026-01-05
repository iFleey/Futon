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
package me.fleey.futon.ui.feature.settings.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.settings.models.ThemeMode
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.settings.SettingsUiEvent
import me.fleey.futon.ui.feature.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppearanceSettingsScreen(
  onBack: () -> Unit,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_appearance),
        onBackClick = onBack,
      )
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SettingsGroup(title = stringResource(R.string.settings_theme)) {
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_theme_system),
            description = stringResource(R.string.settings_theme_system_description),
            selected = uiState.themeMode == ThemeMode.SYSTEM,
            onClick = { viewModel.onEvent(SettingsUiEvent.ThemeModeChanged(ThemeMode.SYSTEM)) },
            leadingIcon = FutonIcons.Theme,
            recommended = true,
          )
        }
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_theme_light),
            description = stringResource(R.string.settings_theme_light_description),
            selected = uiState.themeMode == ThemeMode.LIGHT,
            onClick = { viewModel.onEvent(SettingsUiEvent.ThemeModeChanged(ThemeMode.LIGHT)) },
            leadingIcon = FutonIcons.LightMode,
          )
        }
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_theme_dark),
            description = stringResource(R.string.settings_theme_dark_description),
            selected = uiState.themeMode == ThemeMode.DARK,
            onClick = { viewModel.onEvent(SettingsUiEvent.ThemeModeChanged(ThemeMode.DARK)) },
            leadingIcon = FutonIcons.DarkMode,
          )
        }
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Spacer(modifier = Modifier.height(12.dp))

        SettingsGroup(title = stringResource(R.string.settings_color)) {
          item {
            SettingsSwitchItem(
              title = stringResource(R.string.settings_dynamic_color),
              checked = uiState.dynamicColorEnabled,
              onCheckedChange = { viewModel.onEvent(SettingsUiEvent.DynamicColorChanged(it)) },
              leadingIcon = FutonIcons.Palette,
              description = stringResource(R.string.settings_dynamic_color_description),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}
