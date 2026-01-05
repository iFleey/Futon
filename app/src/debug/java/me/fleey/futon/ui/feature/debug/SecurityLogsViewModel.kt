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
package me.fleey.futon.ui.feature.debug

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.daemon.SecurityAuditLogger
import me.fleey.futon.data.daemon.SecurityEventType
import me.fleey.futon.data.daemon.SecurityLogEntry

data class SecurityLogsUiState(
  val allLogs: List<SecurityLogEntry> = emptyList(),
  val filteredLogs: List<SecurityLogEntry> = emptyList(),
  val searchQuery: String = "",
  val selectedFilters: Set<SecurityEventCategory> = emptySet(),
  val isLoading: Boolean = false,
  @StringRes val message: Int? = null,
)

sealed interface SecurityLogsUiEvent {
  data class UpdateSearchQuery(val query: String) : SecurityLogsUiEvent
  data class ToggleFilter(val category: SecurityEventCategory) : SecurityLogsUiEvent
  data object Refresh : SecurityLogsUiEvent
  data object ClearLogs : SecurityLogsUiEvent
  data object DismissMessage : SecurityLogsUiEvent
}

enum class SecurityEventCategory(
  val displayName: String,
  val color: Color,
) {
  AUTH("Auth", Color(0xFF4CAF50)),
  SESSION("Session", Color(0xFF2196F3)),
  SECURITY("Security", Color(0xFFFF9800)),
  LIFECYCLE("Lifecycle", Color(0xFF9C27B0)),
  OTHER("Other", Color(0xFF607D8B));

  companion object {
    fun fromEventType(eventType: SecurityEventType): SecurityEventCategory {
      return when (eventType) {
        is SecurityEventType.AuthAttempt,
        is SecurityEventType.AuthSuccess,
        is SecurityEventType.AuthFailed,
          -> AUTH

        is SecurityEventType.SessionCreated,
        is SecurityEventType.SessionExpired,
        is SecurityEventType.SessionDestroyed,
          -> SESSION

        is SecurityEventType.CallRejected,
        is SecurityEventType.KeyDeployed,
        is SecurityEventType.KeyRotated,
          -> SECURITY

        is SecurityEventType.DaemonStarted,
        is SecurityEventType.DaemonStopped,
          -> LIFECYCLE

        is SecurityEventType.Custom -> OTHER
      }
    }
  }
}

class SecurityLogsViewModel(
  private val securityAuditLogger: SecurityAuditLogger,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SecurityLogsUiState())
  val uiState: StateFlow<SecurityLogsUiState> = _uiState.asStateFlow()

  init {
    observeLogs()
    loadLogs()
  }

  fun onEvent(event: SecurityLogsUiEvent) {
    when (event) {
      is SecurityLogsUiEvent.UpdateSearchQuery -> updateSearchQuery(event.query)
      is SecurityLogsUiEvent.ToggleFilter -> toggleFilter(event.category)
      is SecurityLogsUiEvent.Refresh -> loadLogs()
      is SecurityLogsUiEvent.ClearLogs -> clearLogs()
      is SecurityLogsUiEvent.DismissMessage -> dismissMessage()
    }
  }

  private fun observeLogs() {
    viewModelScope.launch {
      securityAuditLogger.recentLogs.collect { logs ->
        _uiState.update { state ->
          state.copy(
            allLogs = logs,
            filteredLogs = filterLogs(logs, state.searchQuery, state.selectedFilters),
          )
        }
      }
    }
  }

  private fun loadLogs() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }

      val result = securityAuditLogger.getLogContent()
      result.onSuccess { content ->
        val parsedLogs = parseLogContent(content)
        _uiState.update { state ->
          state.copy(
            allLogs = parsedLogs,
            filteredLogs = filterLogs(parsedLogs, state.searchQuery, state.selectedFilters),
            isLoading = false,
          )
        }
      }.onFailure {
        _uiState.update { it.copy(isLoading = false, message = R.string.debug_load_logs_failed) }
      }
    }
  }

  private fun parseLogContent(content: String): List<SecurityLogEntry> {
    if (content.isBlank()) return emptyList()

    return content.lines()
      .filter { it.isNotBlank() }
      .mapNotNull { line -> parseLogLine(line) }
      .sortedByDescending { it.timestamp }
  }

  private fun parseLogLine(line: String): SecurityLogEntry? {
    val regex = """\[(.+?)\] \[(.+?)\] \[uid=(\d+)\] \[pid=(\d+)\] (.*)""".toRegex()
    val match = regex.find(line) ?: return null

    val (timestampStr, eventCode, uidStr, pidStr, details) = match.destructured

    val timestamp = try {
      val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
      format.parse(timestampStr)?.time ?: System.currentTimeMillis()
    } catch (_: Exception) {
      System.currentTimeMillis()
    }

    val eventType = parseEventType(eventCode)

    return SecurityLogEntry(
      timestamp = timestamp,
      eventType = eventType,
      uid = uidStr.toIntOrNull() ?: 0,
      pid = pidStr.toIntOrNull() ?: 0,
      details = details,
    )
  }

  private fun parseEventType(code: String): SecurityEventType {
    return when (code) {
      "AUTH_ATTEMPT" -> SecurityEventType.AuthAttempt
      "AUTH_SUCCESS" -> SecurityEventType.AuthSuccess
      "AUTH_FAILED" -> SecurityEventType.AuthFailed
      "SESSION_CREATED" -> SecurityEventType.SessionCreated
      "SESSION_EXPIRED" -> SecurityEventType.SessionExpired
      "SESSION_DESTROYED" -> SecurityEventType.SessionDestroyed
      "CALL_REJECTED" -> SecurityEventType.CallRejected
      "KEY_DEPLOYED" -> SecurityEventType.KeyDeployed
      "KEY_ROTATED" -> SecurityEventType.KeyRotated
      "DAEMON_STARTED" -> SecurityEventType.DaemonStarted
      "DAEMON_STOPPED" -> SecurityEventType.DaemonStopped
      else -> SecurityEventType.Custom(code)
    }
  }

  private fun updateSearchQuery(query: String) {
    _uiState.update { state ->
      state.copy(
        searchQuery = query,
        filteredLogs = filterLogs(state.allLogs, query, state.selectedFilters),
      )
    }
  }

  private fun toggleFilter(category: SecurityEventCategory) {
    _uiState.update { state ->
      val newFilters = if (category in state.selectedFilters) {
        state.selectedFilters - category
      } else {
        state.selectedFilters + category
      }
      state.copy(
        selectedFilters = newFilters,
        filteredLogs = filterLogs(state.allLogs, state.searchQuery, newFilters),
      )
    }
  }

  private fun filterLogs(
    logs: List<SecurityLogEntry>,
    query: String,
    filters: Set<SecurityEventCategory>,
  ): List<SecurityLogEntry> {
    return logs.filter { entry ->
      val matchesQuery = query.isEmpty() ||
        entry.eventType.code.contains(query, ignoreCase = true) ||
        entry.details.contains(query, ignoreCase = true)

      val matchesFilter = filters.isEmpty() ||
        SecurityEventCategory.fromEventType(entry.eventType) in filters

      matchesQuery && matchesFilter
    }
  }

  private fun clearLogs() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }

      val result = securityAuditLogger.clearLogs()
      result.onSuccess {
        _uiState.update {
          it.copy(
            allLogs = emptyList(),
            filteredLogs = emptyList(),
            isLoading = false,
            message = R.string.debug_logs_cleared,
          )
        }
      }.onFailure {
        _uiState.update { it.copy(isLoading = false, message = R.string.debug_clear_logs_failed) }
      }
    }
  }

  private fun dismissMessage() {
    _uiState.update { it.copy(message = null) }
  }
}
