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
package me.fleey.futon.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.history.TaskHistoryRepository
import me.fleey.futon.data.history.models.TaskHistoryItem

import org.koin.android.annotation.KoinViewModel

data class DrawerUiState(
  val recentConversations: List<TaskHistoryItem> = emptyList(),
  val searchQuery: String = "",
  val searchResults: List<TaskHistoryItem> = emptyList(),
  val isSearchActive: Boolean = false,
)

@KoinViewModel
class DrawerViewModel(
  private val historyRepository: TaskHistoryRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(DrawerUiState())
  val uiState: StateFlow<DrawerUiState> = _uiState.asStateFlow()

  private var allHistory: List<TaskHistoryItem> = emptyList()

  init {
    observeHistory()
  }

  private fun observeHistory() {
    viewModelScope.launch {
      historyRepository.getHistoryFlow().collect { items ->
        allHistory = items
        _uiState.update { state ->
          state.copy(
            recentConversations = items.take(RECENT_LIMIT),
            searchResults = if (state.isSearchActive) {
              filterByQuery(items, state.searchQuery)
            } else {
              emptyList()
            },
          )
        }
      }
    }
  }

  fun onSearchQueryChange(query: String) {
    _uiState.update { state ->
      state.copy(
        searchQuery = query,
        isSearchActive = query.isNotBlank(),
        searchResults = if (query.isNotBlank()) {
          filterByQuery(allHistory, query)
        } else {
          emptyList()
        },
      )
    }
  }

  fun clearSearch() {
    _uiState.update {
      it.copy(
        searchQuery = "",
        isSearchActive = false,
        searchResults = emptyList(),
      )
    }
  }

  fun deleteConversation(id: String) {
    viewModelScope.launch {
      historyRepository.deleteByIds(setOf(id))
    }
  }

  private fun filterByQuery(items: List<TaskHistoryItem>, query: String): List<TaskHistoryItem> {
    if (query.isBlank()) return emptyList()
    val lowerQuery = query.lowercase()
    return items.filter { it.taskDescription.lowercase().contains(lowerQuery) }
  }

  companion object {
    private const val RECENT_LIMIT = 7
  }
}
