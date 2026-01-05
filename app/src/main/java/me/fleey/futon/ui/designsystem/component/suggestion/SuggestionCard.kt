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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.ui.designsystem.component.FutonShapes

@Composable
fun SuggestionCard(
  suggestion: Suggestion,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  when (suggestion.style) {
    SuggestionStyle.Chip -> SuggestionChip(
      textRes = suggestion.labelRes,
      onClick = { onAction(suggestion.action) },
      modifier = modifier,
      icon = suggestion.icon,
      iconRes = suggestion.iconRes,
    )

    SuggestionStyle.Card -> SuggestionCardStandard(
      labelRes = suggestion.labelRes,
      descriptionRes = suggestion.descriptionRes,
      onClick = { onAction(suggestion.action) },
      modifier = modifier,
      icon = suggestion.icon,
      iconRes = suggestion.iconRes,
      iconTint = suggestion.iconTint,
    )

    SuggestionStyle.LargeCard -> SuggestionCardLarge(
      labelRes = suggestion.labelRes,
      descriptionRes = suggestion.descriptionRes,
      onClick = { onAction(suggestion.action) },
      modifier = modifier,
      icon = suggestion.icon,
      iconRes = suggestion.iconRes,
      iconTint = suggestion.iconTint,
    )
  }
}

@Composable
fun SuggestionCardStandard(
  @StringRes labelRes: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  @StringRes descriptionRes: Int? = null,
  icon: ImageVector? = null,
  @DrawableRes iconRes: Int? = null,
  iconTint: Color? = null,
) {
  val label = stringResource(labelRes)
  val description = descriptionRes?.let { stringResource(it) }
  val tint = iconTint ?: MaterialTheme.colorScheme.primary

  Surface(
    onClick = onClick,
    modifier = modifier.semantics { role = Role.Button },
    shape = FutonShapes.CardShape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null || iconRes != null) {
        Surface(
          shape = FutonShapes.IconContainerShape,
          color = tint.copy(alpha = 0.12f),
          modifier = Modifier.size(44.dp),
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            when {
              icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
              )

              iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
        Spacer(modifier = Modifier.width(14.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
fun SuggestionCardLarge(
  @StringRes labelRes: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  @StringRes descriptionRes: Int? = null,
  icon: ImageVector? = null,
  @DrawableRes iconRes: Int? = null,
  iconTint: Color? = null,
) {
  val label = stringResource(labelRes)
  val description = descriptionRes?.let { stringResource(it) }
  val tint = iconTint ?: MaterialTheme.colorScheme.primary

  Surface(
    onClick = onClick,
    modifier = modifier
      .height(140.dp)
      .semantics { role = Role.Button },
    shape = FutonShapes.LargeCardShape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      if (icon != null || iconRes != null) {
        Surface(
          shape = FutonShapes.IconContainerShape,
          color = tint.copy(alpha = 0.12f),
          modifier = Modifier.size(44.dp),
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            when {
              icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
              )

              iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }

      Column {
        Text(
          text = label,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
