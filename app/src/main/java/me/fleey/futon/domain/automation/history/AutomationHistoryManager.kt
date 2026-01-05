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
package me.fleey.futon.domain.automation.history

import me.fleey.futon.config.AutomationConfig
import me.fleey.futon.data.history.ExecutionLogRepository
import me.fleey.futon.data.history.TaskHistoryRepository
import me.fleey.futon.data.history.models.AIResponseLog
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.ErrorLogEntry
import me.fleey.futon.data.history.models.ExecutionLog
import me.fleey.futon.data.history.models.TaskHistoryItem
import me.fleey.futon.domain.automation.models.ActionLogEntry
import org.koin.core.annotation.Single

/**
 * Manages automation history persistence.
 */
interface AutomationHistoryManager {
  suspend fun saveExecutionLog(
    task: String,
    startTimeMs: Long,
    result: AutomationResultType,
    stepCount: Int,
    actionHistory: List<ActionLogEntry>,
    aiResponses: List<AIResponseLog>,
    errors: List<ErrorLogEntry>,
  ): String

  suspend fun saveTaskHistory(
    task: String,
    result: AutomationResultType,
    stepCount: Int,
    executionLogId: String?,
  )
}

@Single(binds = [AutomationHistoryManager::class])
class AutomationHistoryManagerImpl(
  private val historyRepository: TaskHistoryRepository,
  private val executionLogRepository: ExecutionLogRepository,
) : AutomationHistoryManager {

  override suspend fun saveExecutionLog(
    task: String,
    startTimeMs: Long,
    result: AutomationResultType,
    stepCount: Int,
    actionHistory: List<ActionLogEntry>,
    aiResponses: List<AIResponseLog>,
    errors: List<ErrorLogEntry>,
  ): String {
    val endTimeMs = System.currentTimeMillis()
    val executionLog = ExecutionLog(
      taskDescription = task,
      startTimeMs = startTimeMs,
      endTimeMs = endTimeMs,
      totalDurationMs = endTimeMs - startTimeMs,
      result = result,
      stepCount = stepCount,
      actions = actionHistory,
      aiResponses = aiResponses,
      errors = errors,
    )

    executionLogRepository.saveLog(executionLog)
    executionLogRepository.pruneOldLogs(keepCount = AutomationConfig.Logging.MAX_EXECUTION_LOGS)

    return executionLog.id
  }

  override suspend fun saveTaskHistory(
    task: String,
    result: AutomationResultType,
    stepCount: Int,
    executionLogId: String?,
  ) {
    historyRepository.addTask(
      TaskHistoryItem(
        taskDescription = task,
        timestamp = System.currentTimeMillis(),
        result = result,
        stepCount = stepCount,
        executionLogId = executionLogId,
      ),
    )
  }
}
