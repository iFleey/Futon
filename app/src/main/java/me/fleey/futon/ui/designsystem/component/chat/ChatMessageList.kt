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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.fleey.futon.ui.feature.agent.models.ChatMessage

@Composable
fun ChatMessageList(
  messages: List<ChatMessage>,
  onMessageLongPress: (ChatMessage) -> Unit,
  onRetry: () -> Unit,
  onExampleTaskClick: (String) -> Unit,
  onSettingsClick: () -> Unit,
  onViewLog: () -> Unit,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
  topPadding: Dp = 0.dp,
) {
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      coroutineScope.launch {
        listState.animateScrollToItem(messages.lastIndex)
      }
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(
      start = 16.dp,
      end = 16.dp,
      top = topPadding + 12.dp,
      bottom = 12.dp,
    ),
  ) {
    itemsIndexed(
      items = messages,
      key = { _, message -> message.id },
    ) { index, message ->

      val position = remember(messages, index) {
        val prev = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        determineMessagePosition(prev, message, next)
      }

      val isGroupStart = position == MessagePosition.SINGLE || position == MessagePosition.FIRST

      if (index > 0) {
        Spacer(modifier = Modifier.height(if (isGroupStart) 8.dp else 2.dp))
      }

      ChatMessageItem(
        message = message,
        position = position,
        onLongPress = { onMessageLongPress(message) },
        onRetry = onRetry,
        onExampleTaskClick = onExampleTaskClick,
        onSettingsClick = onSettingsClick,
        onViewLog = onViewLog,
      )
    }
  }
}

private fun determineMessagePosition(
  prev: ChatMessage?,
  current: ChatMessage,
  next: ChatMessage?,
): MessagePosition {
  val prevSameGroup = prev != null && isSameGroup(prev, current)
  val nextSameGroup = next != null && isSameGroup(current, next)

  return when {
    !prevSameGroup && !nextSameGroup -> MessagePosition.SINGLE
    !prevSameGroup && nextSameGroup -> MessagePosition.FIRST
    prevSameGroup && nextSameGroup -> MessagePosition.MIDDLE
    else -> MessagePosition.LAST
  }
}

private fun isSameGroup(a: ChatMessage, b: ChatMessage): Boolean {
  return when (a) {
    is ChatMessage.UserTask if b is ChatMessage.UserTask -> true
    is ChatMessage.AIResponse if b is ChatMessage.AIResponse -> true
    is ChatMessage.SystemMessage if b is ChatMessage.SystemMessage -> true
    else -> false
  }
}

@Composable
private fun ChatMessageItem(
  message: ChatMessage,
  position: MessagePosition,
  onLongPress: () -> Unit,
  onRetry: () -> Unit,
  onExampleTaskClick: (String) -> Unit,
  onSettingsClick: () -> Unit,
  onViewLog: () -> Unit,
) {
  when (message) {
    is ChatMessage.UserTask -> {
      UserMessageBubble(
        taskDescription = message.taskDescription,
        timestamp = message.timestamp,
        position = position,
        onLongPress = onLongPress,
      )
    }

    is ChatMessage.AIResponse -> {
      AIMessageBubble(
        type = message.type,
        content = message.content,
        metadata = message.metadata,
        timestamp = message.timestamp,
        position = position,
        onRetry = onRetry,
        onLongPress = onLongPress,
        onViewLog = onViewLog,
      )
    }

    is ChatMessage.SystemMessage -> {
      SystemMessageBubble(
        type = message.type,
        content = message.content,
        onActionClick = onSettingsClick,
        onExampleClick = onExampleTaskClick,
      )
    }
  }
}
