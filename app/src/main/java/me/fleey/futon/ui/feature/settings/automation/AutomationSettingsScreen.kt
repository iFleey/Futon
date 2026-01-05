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
package me.fleey.futon.ui.feature.settings.automation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.settings.models.ScreenshotQuality
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSliderItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.settings.SettingsUiEvent
import me.fleey.futon.ui.feature.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun AutomationSettingsScreen(
  onBack: () -> Unit,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      snackbarHostState.showSnackbar(context.getString(R.string.settings_saved))
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_automation),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
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

      SettingsGroup(title = stringResource(R.string.settings_execution)) {
        item {
          SettingsSliderItem(
            title = stringResource(R.string.max_steps),
            value = uiState.maxSteps.toFloat(),
            onValueChange = { viewModel.onEvent(SettingsUiEvent.MaxStepsChanged(it.toInt())) },
            valueRange = 1f..100f,
            steps = 98,
            leadingIcon = FutonIcons.Steps,
            description = stringResource(R.string.settings_max_steps_description),
            valueFormatter = { context.getString(R.string.settings_steps_count, it.toInt()) },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_timeout)) {
        item {
          SettingsSliderItem(
            title = stringResource(R.string.settings_ai_timeout),
            value = uiState.requestTimeoutSeconds.toFloat(),
            onValueChange = { viewModel.onEvent(SettingsUiEvent.RequestTimeoutChanged(it.toLong())) },
            valueRange = 30f..900f,
            steps = 28,
            leadingIcon = FutonIcons.Timer,
            description = stringResource(R.string.settings_ai_timeout_description),
            valueFormatter = { context.getString(R.string.settings_timeout_seconds, it.toInt()) },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_token)) {
        item {
          SettingsSliderItem(
            title = stringResource(R.string.settings_max_tokens),
            value = uiState.maxTokens.toFloat(),
            onValueChange = { viewModel.onEvent(SettingsUiEvent.MaxTokensChanged(it.toInt())) },
            valueRange = 512f..16384f,
            steps = 30,
            leadingIcon = FutonIcons.Token,
            description = stringResource(R.string.settings_max_tokens_description),
            valueFormatter = { it.toInt().toString() },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_screenshot)) {
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_screenshot_quality_low),
            description = stringResource(R.string.settings_screenshot_quality_low_description),
            selected = uiState.screenshotQuality == ScreenshotQuality.LOW,
            onClick = { viewModel.onEvent(SettingsUiEvent.ScreenshotQualityChanged(ScreenshotQuality.LOW)) },
            leadingIcon = FutonIcons.Speed,
          )
        }
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_screenshot_quality_medium),
            description = stringResource(R.string.settings_screenshot_quality_medium_description),
            selected = uiState.screenshotQuality == ScreenshotQuality.MEDIUM,
            onClick = { viewModel.onEvent(SettingsUiEvent.ScreenshotQualityChanged(ScreenshotQuality.MEDIUM)) },
            leadingIcon = FutonIcons.Screenshot,
            recommended = true,
          )
        }
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_screenshot_quality_high),
            description = stringResource(R.string.settings_screenshot_quality_high_description),
            selected = uiState.screenshotQuality == ScreenshotQuality.HIGH,
            onClick = { viewModel.onEvent(SettingsUiEvent.ScreenshotQualityChanged(ScreenshotQuality.HIGH)) },
            leadingIcon = FutonIcons.AI,
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}
