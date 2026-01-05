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
package me.fleey.futon.ui.designsystem.component.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Modal dialog component.
 *
 * @param title Dialog title
 * @param message Dialog message
 * @param confirmText Confirm button text
 * @param onConfirm Confirm button click handler
 * @param onDismiss Dismiss handler
 * @param dismissText Optional dismiss button text
 * @param icon Optional dialog icon
 */
@Composable
fun FutonDialog(
  title: String,
  message: String,
  confirmText: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  dismissText: String? = null,
  icon: ImageVector? = null,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
    text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
    confirmButton = {
      FutonButton(text = confirmText, onClick = onConfirm)
    },
    dismissButton = dismissText?.let {
      { FutonButton(text = it, onClick = onDismiss, style = ButtonStyle.Secondary) }
    },
    shape = FutonShapes.DialogShape,
    containerColor = MaterialTheme.colorScheme.surface,
  )
}

/**
 * Empty content state component.
 *
 * @param icon State icon
 * @param title State title
 * @param message Optional state message
 * @param actionText Optional action button text
 * @param onAction Optional action button click handler
 * @param modifier Modifier for the component
 */
@Composable
fun FutonEmptyState(
  icon: ImageVector,
  title: String,
  message: String? = null,
  actionText: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(FutonSizes.EmptyStatePadding),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.Companion.size(FutonSizes.LargeIconSize),
      tint = FutonTheme.colors.interactiveMuted,
    )
    Spacer(modifier = Modifier.Companion.height(FutonSizes.EmptyStateSpacing))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = FutonTheme.colors.textMuted,
    )
    message?.let {
      Spacer(modifier = Modifier.Companion.height(FutonSizes.EmptyStateSmallSpacing))
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
    if (actionText != null && onAction != null) {
      Spacer(modifier = Modifier.Companion.height(FutonSizes.EmptyStateSpacing))
      FutonButton(text = actionText, onClick = onAction)
    }
  }
}

/**
 * Loading overlay component.
 *
 * When loading is true, displays a semi-transparent overlay with a loading indicator
 * that prevents interaction with underlying content.
 *
 * @param loading Whether to show the loading overlay
 * @param modifier Modifier for the component
 * @param content Content to display under the overlay
 */
@Composable
fun FutonLoadingOverlay(
  loading: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Box(modifier = modifier) {
    content()
    if (loading) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
      ) {
        Box(contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
}
