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
package me.fleey.futon.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.data.history.models.ExecutionLog

/**
 * DataStore-based implementation of ExecutionLogRepository.
 * Stores execution logs as JSON in DataStore Preferences.
 */
class ExecutionLogRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
) : ExecutionLogRepository {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  override fun getLogsFlow(): Flow<List<ExecutionLog>> = dataStore.data.map { prefs ->
    val logsJson = prefs[EXECUTION_LOGS_KEY] ?: "[]"
    try {
      json.decodeFromString<List<ExecutionLog>>(logsJson)
    } catch (e: Exception) {
      emptyList()
    }
  }

  override suspend fun saveLog(log: ExecutionLog) {
    dataStore.edit { prefs ->
      val currentLogs = getCurrentLogs(prefs).toMutableList()
      // Add new log at the beginning (most recent first)
      currentLogs.add(0, log)
      prefs[EXECUTION_LOGS_KEY] = json.encodeToString(currentLogs)
    }
  }

  override suspend fun getLog(id: String): ExecutionLog? {
    return getLogsFlow().first().find { it.id == id }
  }

  override suspend fun getRecentLogs(limit: Int): List<ExecutionLog> {
    return getLogsFlow().first().take(limit)
  }

  override suspend fun deleteLog(id: String) {
    dataStore.edit { prefs ->
      val currentLogs = getCurrentLogs(prefs).toMutableList()
      currentLogs.removeAll { it.id == id }
      prefs[EXECUTION_LOGS_KEY] = json.encodeToString(currentLogs)
    }
  }

  override suspend fun pruneOldLogs(keepCount: Int) {
    dataStore.edit { prefs ->
      val currentLogs = getCurrentLogs(prefs)
      if (currentLogs.size > keepCount) {
        val prunedLogs = currentLogs.take(keepCount)
        prefs[EXECUTION_LOGS_KEY] = json.encodeToString(prunedLogs)
      }
    }
  }

  override suspend fun exportAsJson(id: String): String? {
    val log = getLog(id) ?: return null
    return json.encodeToString(log)
  }

  private fun getCurrentLogs(prefs: Preferences): List<ExecutionLog> {
    val logsJson = prefs[EXECUTION_LOGS_KEY] ?: "[]"
    return try {
      json.decodeFromString<List<ExecutionLog>>(logsJson)
    } catch (e: Exception) {
      emptyList()
    }
  }

  companion object {
    private val EXECUTION_LOGS_KEY = stringPreferencesKey("execution_logs")
  }
}
