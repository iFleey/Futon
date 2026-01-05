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
package me.fleey.futon.ui.designsystem.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Asymmetric chat bubble shapes for conversation-style UI.
 * Task bubbles point right (user messages), result bubbles point left (system responses).
 */
object ChatBubbleShapes {
  /**
   * Task bubble shape (user message) - right aligned.
   * Larger corners on left, smaller on right to indicate message direction.
   */
  val TaskBubbleShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 4.dp,
    bottomStart = 16.dp,
    bottomEnd = 16.dp,
  )

  /**
   * Result bubble shape (system response) - left aligned.
   * Larger corners on right, smaller on left to indicate message direction.
   */
  val ResultBubbleShape = RoundedCornerShape(
    topStart = 4.dp,
    topEnd = 16.dp,
    bottomStart = 16.dp,
    bottomEnd = 16.dp,
  )
}

/**
 * Styling information for automation result status display.
 */
data class StatusStyling(
  val color: Color,
  val icon: ImageVector,
  val labelResId: Int,
)

/**
 * Maps an AutomationResultType to its corresponding visual styling.
 * Returns consistent styling for status indicators across the app.
 */
@Composable
fun getStatusStyling(result: AutomationResultType): StatusStyling {
  return when (result) {
    AutomationResultType.SUCCESS -> StatusStyling(
      color = FutonTheme.colors.statusPositive,
      icon = FutonIcons.Success,
      labelResId = R.string.history_status_success,
    )

    AutomationResultType.FAILURE -> StatusStyling(
      color = FutonTheme.colors.statusDanger,
      icon = FutonIcons.Error,
      labelResId = R.string.history_status_failure,
    )

    AutomationResultType.CANCELLED -> StatusStyling(
      color = FutonTheme.colors.textMuted,
      icon = FutonIcons.Close,
      labelResId = R.string.history_status_cancelled,
    )

    AutomationResultType.TIMEOUT -> StatusStyling(
      color = FutonTheme.colors.statusWarning,
      icon = FutonIcons.Warning,
      labelResId = R.string.history_status_timeout,
    )
  }
}
