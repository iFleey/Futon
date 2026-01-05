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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import me.fleey.futon.ui.designsystem.components.ChatBubbleShapes

/**
 * Chat bubble for displaying user task descriptions.
 * Aligned to the right side with asymmetric corner rounding.
 *
 * @param taskDescription The task description text to display
 * @param modifier Modifier for the outer container
 */
@Composable
fun TaskBubble(
  taskDescription: String,
  modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  val maxBubbleWidth = (configuration.screenWidthDp * 0.85f).dp

  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Box(
      modifier = Modifier
        .widthIn(max = maxBubbleWidth)
        .clip(ChatBubbleShapes.TaskBubbleShape)
        .background(MaterialTheme.colorScheme.primary)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Text(
        text = taskDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
  }
}
