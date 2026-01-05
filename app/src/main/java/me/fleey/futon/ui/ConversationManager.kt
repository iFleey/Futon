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
package me.fleey.futon.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.fleey.futon.data.history.models.TaskHistoryItem
import org.koin.core.annotation.Single

sealed interface ConversationEvent {
  data class LoadConversation(val item: TaskHistoryItem) : ConversationEvent
  data object ClearConversation : ConversationEvent
}

@Single
class ConversationManager {
  private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 1)
  val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

  fun loadConversation(item: TaskHistoryItem) {
    _events.tryEmit(ConversationEvent.LoadConversation(item))
  }

  fun clearConversation() {
    _events.tryEmit(ConversationEvent.ClearConversation)
  }
}
