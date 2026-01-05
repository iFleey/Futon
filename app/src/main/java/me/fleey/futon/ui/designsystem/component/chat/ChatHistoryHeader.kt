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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.FutonIconButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Card-based header for the chat history screen.
 * Displays record count and clear history action.
 *
 * @param itemCount Number of history items
 * @param onClearHistory Callback when clear history is clicked
 * @param modifier Modifier for the outer container
 */
@Composable
fun ChatHistoryHeader(
  itemCount: Int,
  onClearHistory: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.history_header_title),
      style = MaterialTheme.typography.titleMedium,
      color = FutonTheme.colors.textMuted,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(FutonTheme.colors.backgroundSecondary)
        .padding(horizontal = FutonSizes.ListItemHorizontalPadding, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.history_record_count, itemCount),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )

      FutonIconButton(
        icon = FutonIcons.Clear,
        onClick = onClearHistory,
        contentDescription = stringResource(R.string.clear_history),
        tint = FutonTheme.colors.statusDanger,
      )
    }
  }
}
