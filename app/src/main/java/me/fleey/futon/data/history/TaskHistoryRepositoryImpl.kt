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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.data.history.models.TaskHistoryItem

class TaskHistoryRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
) : TaskHistoryRepository {

  private val json = Json { ignoreUnknownKeys = true }

  override fun getHistoryFlow(): Flow<List<TaskHistoryItem>> = dataStore.data.map { prefs ->
    val historyJson = prefs[HISTORY_KEY] ?: "[]"
    try {
      json.decodeFromString<List<TaskHistoryItem>>(historyJson)
    } catch (e: Exception) {
      emptyList()
    }
  }

  override suspend fun addTask(item: TaskHistoryItem) {
    dataStore.edit { prefs ->
      val currentJson = prefs[HISTORY_KEY] ?: "[]"
      val currentList = try {
        json.decodeFromString<List<TaskHistoryItem>>(currentJson).toMutableList()
      } catch (e: Exception) {
        mutableListOf()
      }

      currentList.add(0, item)

      val trimmedList = currentList.take(MAX_HISTORY_SIZE)
      prefs[HISTORY_KEY] = json.encodeToString(trimmedList)
    }
  }

  override suspend fun clearHistory() {
    dataStore.edit { prefs ->
      prefs.remove(HISTORY_KEY)
    }
  }

  override suspend fun deleteByIds(ids: Set<String>) {
    dataStore.edit { prefs ->
      val currentJson = prefs[HISTORY_KEY] ?: "[]"
      val currentList = try {
        json.decodeFromString<List<TaskHistoryItem>>(currentJson)
      } catch (e: Exception) {
        emptyList()
      }

      val filteredList = currentList.filter { it.id !in ids }
      prefs[HISTORY_KEY] = json.encodeToString(filteredList)
    }
  }

  companion object {
    private val HISTORY_KEY = stringPreferencesKey("task_history")
    private const val MAX_HISTORY_SIZE = 50
  }
}
