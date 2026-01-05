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
package me.fleey.futon.data.apps.models

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

@Serializable
data class InstalledApp(
  val packageName: String,
  val appName: String,
  val versionName: String?,
  val isSystemApp: Boolean,
  val categoryId: String? = null,
  val userAliases: List<String> = emptyList(),
) {
  fun getSearchableNames(): List<String> {
    return listOf(appName) + userAliases
  }

  fun toAIFormat(categoryName: String? = null): String {
    val aliasStr = if (userAliases.isNotEmpty()) " (${userAliases.joinToString(", ")})" else ""
    val catStr = categoryName?.let { " [$it]" } ?: ""
    return "- $appName$aliasStr$catStr"
  }
}

@Serializable
data class AppCategory(
  val id: String,
  @param:StringRes val nameRes: Int = 0,
  val icon: String? = null,
  val packagePatterns: List<String> = emptyList(),
  val isUserDefined: Boolean = false,
  val customName: String? = null,
)

@Serializable
data class AppAlias(
  val packageName: String,
  val aliases: List<String>,
)

@Serializable
data class AppSettings(
  val enabled: Boolean = true,
  val includeSystemApps: Boolean = false,
  val maxAppsInContext: Int = 30,
  val categories: List<AppCategory> = emptyList(),
  val userAliases: List<AppAlias> = emptyList(),
  val hiddenApps: List<String> = emptyList(),
)
