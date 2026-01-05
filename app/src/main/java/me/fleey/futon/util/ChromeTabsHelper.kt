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
package me.fleey.futon.util

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri

object ChromeTabsHelper {

  fun openUrl(
    context: Context,
    url: String,
    toolbarColor: Color,
    isDarkTheme: Boolean,
  ) {
    val colorScheme = if (isDarkTheme) {
      CustomTabsIntent.COLOR_SCHEME_DARK
    } else {
      CustomTabsIntent.COLOR_SCHEME_LIGHT
    }

    val colorParams = CustomTabColorSchemeParams.Builder()
      .setToolbarColor(toolbarColor.toArgb())
      .build()

    val customTabsIntent = CustomTabsIntent.Builder()
      .setColorScheme(colorScheme)
      .setDefaultColorSchemeParams(colorParams)
      .setShowTitle(true)
      .setShareState(CustomTabsIntent.SHARE_STATE_ON)
      .build()

    try {
      customTabsIntent.launchUrl(context, url.toUri())
    } catch (e: Exception) {
      val intent = Intent(Intent.ACTION_VIEW, url.toUri())
      context.startActivity(intent)
    }
  }
}
