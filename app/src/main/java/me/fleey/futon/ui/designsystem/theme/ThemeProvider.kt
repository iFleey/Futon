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
package me.fleey.futon.ui.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.ThemePreferences

/**
 * Theme provider that reads theme preferences from SettingsRepository
 * and applies them to FutonTheme.
 *
 * @param settingsRepository The repository to read theme preferences from
 * @param content The content to be themed
 */
@Composable
fun ThemeProvider(
  settingsRepository: SettingsRepository,
  content: @Composable () -> Unit,
) {
  val themePreferences by settingsRepository.getThemePreferencesFlow()
    .collectAsState(initial = ThemePreferences())

  FutonTheme(
    themeMode = themePreferences.themeMode,
    dynamicColor = themePreferences.dynamicColorEnabled,
    content = content,
  )
}
