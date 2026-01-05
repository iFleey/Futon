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
package me.fleey.futon.ui.designsystem.component.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * List item component with title, subtitle, icon, and trailing content.
 * @param title Item title
 * @param subtitle Optional item subtitle
 * @param leadingIcon Optional leading icon (20dp, muted color)
 * @param trailing Optional trailing content slot
 * @param showChevron Whether to show trailing chevron icon for navigation items
 * @param onClick Optional click handler
 * @param modifier Modifier for the item
 */
@Composable
fun FutonListItem(
  title: String,
  subtitle: String? = null,
  leadingIcon: ImageVector? = null,
  trailing: @Composable (() -> Unit)? = null,
  showChevron: Boolean = false,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    onClick = onClick ?: {},
    color = Color.Transparent,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.Companion.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.Companion.width(FutonSizes.ListItemIconSpacing))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        subtitle?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
      trailing?.invoke()
      if (showChevron) {
        Icon(
          imageVector = FutonIcons.ChevronRight,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.Companion.size(FutonSizes.IconSize),
        )
      }
    }
  }
}

/**
 * Navigation item component for settings and navigation lists.
 *
 * Uses consistent padding (horizontal 16.dp, vertical 14.dp).
 *
 * @param title Item title text
 * @param onClick Click handler for navigation
 * @param modifier Modifier for the item
 * @param leadingIcon Optional leading icon (20dp, InteractiveNormal color)
 * @param subtitle Optional subtitle text displayed below title
 */
@Composable
fun FutonNavigationItem(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
  subtitle: String? = null,
) {
  FutonListItem(
    title = title,
    subtitle = subtitle,
    leadingIcon = leadingIcon,
    showChevron = true,
    onClick = onClick,
    modifier = modifier,
  )
}
