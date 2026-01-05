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
package me.fleey.futon.data.settings.models

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
  LIGHT,
  DARK,
  SYSTEM
}

@Serializable
enum class AppLanguage(val code: String?) {
  SYSTEM(null),
  ENGLISH("en"),
  CHINESE_SIMPLIFIED("zh-CN"),
  CHINESE_TRADITIONAL("zh-TW"),
  JAPANESE("ja")
}

/**
 * Data class representing user's theme preferences.
 * @param themeMode The selected theme mode (light, dark, or follow system)
 * @param dynamicColorEnabled Whether Material You dynamic color is enabled
 * @param appLanguage The selected app language
 */
@Serializable
data class ThemePreferences(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColorEnabled: Boolean = true,
  val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)
