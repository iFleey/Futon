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

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import me.fleey.futon.data.settings.models.ThemeMode

data class FutonColors(
  val background: Color,
  val backgroundSecondary: Color,
  val backgroundTertiary: Color,
  val backgroundFloating: Color,
  val channelDefault: Color,
  val channelTextarea: Color,
  val textNormal: Color,
  val textMuted: Color,
  val textLink: Color,
  val interactiveNormal: Color,
  val interactiveHover: Color,
  val interactiveActive: Color,
  val interactiveMuted: Color,
  val statusPositive: Color,
  val statusWarning: Color,
  val statusDanger: Color,
  val snackbarBackground: Color,
  val snackbarText: Color,
  val snackbarAction: Color,
)

val LocalFutonColors = staticCompositionLocalOf {
  FutonColors(
    background = Color.Unspecified,
    backgroundSecondary = Color.Unspecified,
    backgroundTertiary = Color.Unspecified,
    backgroundFloating = Color.Unspecified,
    channelDefault = Color.Unspecified,
    channelTextarea = Color.Unspecified,
    textNormal = Color.Unspecified,
    textMuted = Color.Unspecified,
    textLink = Color.Unspecified,
    interactiveNormal = Color.Unspecified,
    interactiveHover = Color.Unspecified,
    interactiveActive = Color.Unspecified,
    interactiveMuted = Color.Unspecified,
    statusPositive = Color.Unspecified,
    statusWarning = Color.Unspecified,
    statusDanger = Color.Unspecified,
    snackbarBackground = Color.Unspecified,
    snackbarText = Color.Unspecified,
    snackbarAction = Color.Unspecified,
  )
}

private val DarkColorScheme = darkColorScheme(
  primary = Blurple,
  onPrimary = Color.White,
  primaryContainer = BlurpleDark,
  onPrimaryContainer = Color.White,
  secondary = DarkColors.BackgroundSecondary,
  onSecondary = DarkColors.TextNormal,
  secondaryContainer = DarkColors.BackgroundTertiary,
  onSecondaryContainer = DarkColors.TextNormal,
  tertiary = Green,
  onTertiary = Color.Black,
  background = DarkColors.Background,
  onBackground = DarkColors.TextNormal,
  surface = DarkColors.BackgroundSecondary,
  onSurface = DarkColors.TextNormal,
  surfaceVariant = DarkColors.BackgroundTertiary,
  onSurfaceVariant = DarkColors.TextMuted,
  surfaceContainer = DarkColors.BackgroundSecondary,
  surfaceContainerLow = DarkColors.BackgroundTertiary,
  surfaceContainerLowest = DarkColors.BackgroundFloating,
  surfaceContainerHigh = DarkColors.ChannelTextarea,
  error = Red,
  onError = Color.White,
  errorContainer = Red.copy(alpha = 0.2f),
  onErrorContainer = Red,
  outline = DarkColors.InteractiveMuted,
  outlineVariant = DarkColors.BackgroundTertiary,
)

private val LightColorScheme = lightColorScheme(
  primary = Blurple,
  onPrimary = Color.White,
  primaryContainer = Blurple.copy(alpha = 0.1f),
  onPrimaryContainer = BlurpleDark,
  secondary = LightColors.BackgroundSecondary,
  onSecondary = LightColors.TextNormal,
  secondaryContainer = LightColors.BackgroundTertiary,
  onSecondaryContainer = LightColors.TextNormal,
  tertiary = Green,
  onTertiary = Color.Black,
  background = LightColors.Background,
  onBackground = LightColors.TextNormal,
  surface = LightColors.BackgroundSecondary,
  onSurface = LightColors.TextNormal,
  surfaceVariant = LightColors.BackgroundTertiary,
  onSurfaceVariant = LightColors.TextMuted,
  surfaceContainer = LightColors.BackgroundSecondary,
  surfaceContainerLow = LightColors.BackgroundTertiary,
  surfaceContainerLowest = LightColors.BackgroundFloating,
  surfaceContainerHigh = LightColors.ChannelTextarea,
  error = Red,
  onError = Color.White,
  errorContainer = Red.copy(alpha = 0.1f),
  onErrorContainer = Red,
  outline = LightColors.InteractiveMuted,
  outlineVariant = LightColors.BackgroundTertiary,
)

object FutonTheme {
  val colors: FutonColors
    @Composable
    get() = LocalFutonColors.current
}

/**
 * Main theme composable for Futon app.
 * Supports Material You dynamic color and theme mode selection.
 *
 * @param themeMode The theme mode (LIGHT, DARK, or SYSTEM)
 * @param dynamicColor Whether to use Material You dynamic color (Android 12+)
 * @param content The content to be themed
 */
@Composable
fun FutonTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val darkTheme = when (themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
  }

  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  val futonColors = colorScheme.toFutonColors(darkTheme)

  CompositionLocalProvider(LocalFutonColors provides futonColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content,
    )
  }
}
