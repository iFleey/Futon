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

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.history.models.ExecutionLog

/**
 * Repository interface for managing execution logs.
 * Provides persistence, retrieval, and export functionality for automation execution logs.
 */
interface ExecutionLogRepository {
  /**
   * Get a Flow of all execution logs that emits whenever logs change.
   * @return Flow of execution logs, ordered by most recent first
   */
  fun getLogsFlow(): Flow<List<ExecutionLog>>

  /**
   * Save an execution log to persistent storage.
   * @param log The execution log to save
   */
  suspend fun saveLog(log: ExecutionLog)

  /**
   * Get a specific execution log by its ID.
   * @param id The unique identifier of the log
   * @return The execution log, or null if not found
   */
  suspend fun getLog(id: String): ExecutionLog?

  /**
   * Get the most recent execution logs.
   * @param limit Maximum number of logs to return (default: 50)
   * @return List of execution logs, ordered by most recent first
   */
  suspend fun getRecentLogs(limit: Int = 50): List<ExecutionLog>

  /**
   * Delete a specific execution log.
   * @param id The unique identifier of the log to delete
   */
  suspend fun deleteLog(id: String)

  /**
   * Remove old logs to maintain storage limits.
   * Keeps the most recent logs up to the specified count.
   * @param keepCount Number of logs to retain (default: 50)
   */
  suspend fun pruneOldLogs(keepCount: Int = 50)

  /**
   * Export an execution log as a JSON string.
   * @param id The unique identifier of the log to export
   * @return JSON string representation of the log, or null if not found
   */
  suspend fun exportAsJson(id: String): String?
}
