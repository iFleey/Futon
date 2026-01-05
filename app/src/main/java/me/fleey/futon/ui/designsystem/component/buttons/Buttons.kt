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
package me.fleey.futon.ui.designsystem.component.buttons

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.Green
import me.fleey.futon.ui.designsystem.theme.Red

enum class ButtonStyle {
  Primary,
  Secondary,
  Danger,
  Success,
  Ghost
}

@Composable
fun FutonButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
  icon: ImageVector? = null,
  style: ButtonStyle = ButtonStyle.Primary,
) {
  val backgroundColor = when (style) {
    ButtonStyle.Primary -> MaterialTheme.colorScheme.primary
    ButtonStyle.Secondary -> MaterialTheme.colorScheme.surfaceVariant
    ButtonStyle.Danger -> Red
    ButtonStyle.Success -> Green
    ButtonStyle.Ghost -> Color.Transparent
  }

  val contentColor = when (style) {
    ButtonStyle.Primary -> MaterialTheme.colorScheme.onPrimary
    ButtonStyle.Danger -> Color.White
    ButtonStyle.Success -> Color.Black
    ButtonStyle.Secondary, ButtonStyle.Ghost -> MaterialTheme.colorScheme.onSurface
  }

  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier = modifier.height(FutonSizes.ButtonHeight),
    shape = FutonShapes.ButtonShape,
    colors = ButtonDefaults.buttonColors(
      containerColor = backgroundColor,
      contentColor = contentColor,
      disabledContainerColor = backgroundColor.copy(alpha = 0.5f),
      disabledContentColor = contentColor.copy(alpha = 0.5f),
    ),
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(FutonSizes.ButtonLoadingSize),
        strokeWidth = FutonSizes.LoadingStrokeWidth,
        color = contentColor,
      )
      Spacer(modifier = Modifier.width(FutonSizes.ButtonIconSpacing))
    } else {
      icon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          modifier = Modifier.size(FutonSizes.SmallIconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ButtonIconSpacing))
      }
    }
    Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

@Composable
fun FutonIconButton(
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  enabled: Boolean = true,
  tint: Color = MaterialTheme.colorScheme.onSurface,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = if (enabled) tint else tint.copy(alpha = 0.5f),
    )
  }
}
