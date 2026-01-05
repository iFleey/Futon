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
package me.fleey.futon.ui.designsystem.component.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.FutonIconButton
import me.fleey.futon.ui.designsystem.theme.Red

/**
 * Contextual action bar for conversation items.
 * Displays retry, view details (conditional), and delete actions.
 *
 * @param hasExecutionLog Whether an execution log exists for this item
 * @param onRetry Callback when retry action is triggered
 * @param onViewDetails Callback when view details action is triggered
 * @param onDelete Callback when delete action is triggered
 * @param modifier Modifier for the action bar container
 */
@Composable
fun ConversationActionBar(
  hasExecutionLog: Boolean,
  onRetry: () -> Unit,
  onViewDetails: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FutonIconButton(
      icon = FutonIcons.Retry,
      onClick = onRetry,
      contentDescription = stringResource(R.string.action_retry),
      tint = MaterialTheme.colorScheme.primary,
    )

    if (hasExecutionLog) {
      FutonIconButton(
        icon = FutonIcons.Info,
        onClick = onViewDetails,
        contentDescription = stringResource(R.string.view_details),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    FutonIconButton(
      icon = FutonIcons.Delete,
      onClick = onDelete,
      contentDescription = stringResource(R.string.action_delete),
      tint = Red,
    )
  }
}
