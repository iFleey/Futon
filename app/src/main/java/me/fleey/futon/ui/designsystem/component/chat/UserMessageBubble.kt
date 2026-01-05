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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sv.lib.squircleshape.SquircleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LARGE_RADIUS = 20.dp
private val SMALL_RADIUS = 4.dp

private fun getUserBubbleShape(position: MessagePosition): Shape {
  return when (position) {
    MessagePosition.SINGLE -> SquircleShape(LARGE_RADIUS, LARGE_RADIUS, LARGE_RADIUS, SMALL_RADIUS)
    MessagePosition.FIRST -> SquircleShape(LARGE_RADIUS, LARGE_RADIUS, LARGE_RADIUS, SMALL_RADIUS)
    MessagePosition.MIDDLE -> SquircleShape(LARGE_RADIUS, SMALL_RADIUS, LARGE_RADIUS, SMALL_RADIUS)
    MessagePosition.LAST -> SquircleShape(LARGE_RADIUS, SMALL_RADIUS, LARGE_RADIUS, LARGE_RADIUS)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
  taskDescription: String,
  timestamp: Long,
  position: MessagePosition = MessagePosition.SINGLE,
  onLongPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  val maxBubbleWidth = (configuration.screenWidthDp * 0.8f).dp
  val hapticFeedback = LocalHapticFeedback.current
  val timeText = remember(timestamp) { formatMessageTime(timestamp) }
  val shape = remember(position) { getUserBubbleShape(position) }

  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Box(
      modifier = Modifier
        .widthIn(max = maxBubbleWidth)
        .clip(shape)
        .background(MaterialTheme.colorScheme.primary)
        .combinedClickable(
          onClick = {},
          onLongClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
          },
          role = Role.Button,
        )
        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
      ) {
        Text(
          text = taskDescription,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = timeText,
          style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
          ),
          color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
          textAlign = TextAlign.End,
          modifier = Modifier.alignByBaseline(),
        )
      }
    }
  }
}

internal fun formatMessageTime(timestamp: Long): String {
  val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
  return formatter.format(Date(timestamp))
}
