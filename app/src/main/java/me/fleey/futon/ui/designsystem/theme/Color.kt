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

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

val Blurple = Color(0xFF5865F2)
val BlurpleDark = Color(0xFF4752C4)
val Green = Color(0xFF57F287)
val Yellow = Color(0xFFFEE75C)
val Fuchsia = Color(0xFFEB459E)
val Red = Color(0xFFED4245)

object StatusColors {
  val Positive = Color(0xFF23A55A)
  val Warning = Color(0xFFF0B232)
  val Danger = Color(0xFFF23F43)
}

fun ColorScheme.toFutonColors(isDark: Boolean): FutonColors {

  return FutonColors(
    // Background hierarchy using Material 3 surface containers
    background = surfaceContainerLowest,           // Page background (deepest)
    backgroundSecondary = surfaceContainer,        // Card/section background
    backgroundTertiary = surfaceContainerHigh,     // Elevated elements / dividers
    backgroundFloating = surfaceContainerHighest,  // Floating elements (modals, tooltips)

    // Channel/input colors
    channelDefault = onSurfaceVariant,
    channelTextarea = surfaceContainerHigh,        // Input field background

    // Text colors from Material 3
    textNormal = onSurface,
    textMuted = onSurfaceVariant,
    textLink = primary,

    // Interactive element colors
    interactiveNormal = onSurfaceVariant,
    interactiveHover = onSurface,
    interactiveActive = primary,
    interactiveMuted = outline,

    statusPositive = StatusColors.Positive,
    statusWarning = StatusColors.Warning,
    statusDanger = StatusColors.Danger,

    snackbarBackground = if (isDark) Color(0xFF2B2D31) else Color(0xFF313338),
    snackbarText = if (isDark) Color(0xFFDBDEE1) else Color(0xFFFFFFFF),
    snackbarAction = primary,
  )
}

interface ThemeColors {
  val Background: Color
  val BackgroundSecondary: Color
  val BackgroundTertiary: Color
  val BackgroundFloating: Color
  val ChannelDefault: Color
  val ChannelTextarea: Color
  val TextNormal: Color
  val TextMuted: Color
  val TextLink: Color
  val InteractiveNormal: Color
  val InteractiveHover: Color
  val InteractiveActive: Color
  val InteractiveMuted: Color
  val StatusPositive: Color
  val StatusWarning: Color
  val StatusDanger: Color
}

object DarkColors : ThemeColors {
  override val Background = Color(0xFF313338)
  override val BackgroundSecondary = Color(0xFF2B2D31)
  override val BackgroundTertiary = Color(0xFF1E1F22)
  override val BackgroundFloating = Color(0xFF111214)

  override val ChannelDefault = Color(0xFF949BA4)
  override val ChannelTextarea = Color(0xFF383A40)

  override val TextNormal = Color(0xFFDBDEE1)
  override val TextMuted = Color(0xFF949BA4)
  override val TextLink = Color(0xFF00A8FC)

  override val InteractiveNormal = Color(0xFFB5BAC1)
  override val InteractiveHover = Color(0xFFDBDEE1)
  override val InteractiveActive = Color(0xFFFFFFFF)
  override val InteractiveMuted = Color(0xFF4E5058)

  override val StatusPositive = Color(0xFF23A55A)
  override val StatusWarning = Color(0xFFF0B232)
  override val StatusDanger = Color(0xFFF23F43)
}

object LightColors : ThemeColors {
  override val Background = Color(0xFFFFFFFF)
  override val BackgroundSecondary = Color(0xFFF2F3F5)
  override val BackgroundTertiary = Color(0xFFE3E5E8)
  override val BackgroundFloating = Color(0xFFFFFFFF)

  override val ChannelDefault = Color(0xFF5C5E66)
  override val ChannelTextarea = Color(0xFFEBEDEF)

  override val TextNormal = Color(0xFF313338)
  override val TextMuted = Color(0xFF5C5E66)
  override val TextLink = Color(0xFF006CE7)

  override val InteractiveNormal = Color(0xFF4E5058)
  override val InteractiveHover = Color(0xFF313338)
  override val InteractiveActive = Color(0xFF060607)
  override val InteractiveMuted = Color(0xFFC4C9CE)

  override val StatusPositive = Color(0xFF248046)
  override val StatusWarning = Color(0xFFD4A72C)
  override val StatusDanger = Color(0xFFDA373C)
}
