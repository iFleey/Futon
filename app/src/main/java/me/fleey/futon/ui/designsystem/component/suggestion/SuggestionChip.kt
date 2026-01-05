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
package me.fleey.futon.ui.designsystem.component.suggestion

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.fleey.futon.ui.designsystem.component.FutonShapes

enum class SuggestionChipStyle {
  Outlined,
  Filled,
  Tonal
}

@Composable
fun SuggestionChip(
  @StringRes textRes: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  @DrawableRes iconRes: Int? = null,
  style: SuggestionChipStyle = SuggestionChipStyle.Outlined,
  enabled: Boolean = true,
) {
  val text = stringResource(textRes)
  SuggestionChipContent(
    text = text,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    iconRes = iconRes,
    style = style,
    enabled = enabled,
  )
}

@Composable
fun SuggestionChip(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  @DrawableRes iconRes: Int? = null,
  style: SuggestionChipStyle = SuggestionChipStyle.Outlined,
  enabled: Boolean = true,
) {
  SuggestionChipContent(
    text = text,
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    iconRes = iconRes,
    style = style,
    enabled = enabled,
  )
}

@Composable
private fun SuggestionChipContent(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier,
  icon: ImageVector?,
  @DrawableRes iconRes: Int?,
  style: SuggestionChipStyle,
  enabled: Boolean,
) {
  val (containerColor, contentColor, border) = when (style) {
    SuggestionChipStyle.Outlined -> Triple(
      Color.Transparent,
      MaterialTheme.colorScheme.onSurface,
      BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    )

    SuggestionChipStyle.Filled -> Triple(
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
      null,
    )

    SuggestionChipStyle.Tonal -> Triple(
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
      null,
    )
  }

  Surface(
    onClick = onClick,
    modifier = modifier.semantics { role = Role.Button },
    enabled = enabled,
    shape = FutonShapes.ChipShape,
    color = containerColor,
    contentColor = contentColor,
    border = border,
    tonalElevation = if (style == SuggestionChipStyle.Tonal) 1.dp else 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      when {
        icon != null -> {
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
        }

        iconRes != null -> {
          Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
      }
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
