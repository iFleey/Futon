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
package me.fleey.futon.ui.feature.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.models.ActionParameters
import me.fleey.futon.data.history.ExecutionLogRepository
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.ErrorLogEntry
import me.fleey.futon.data.history.models.ExecutionLog
import me.fleey.futon.domain.automation.models.ActionResult
import org.koin.android.annotation.KoinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class LogDetailUiState(
  val isLoading: Boolean = true,
  val error: String? = null,
  val header: LogHeaderUiModel? = null,
  val steps: List<MergedStepUiModel> = emptyList(),
  val errors: List<ErrorLogEntry> = emptyList(),
)

@Immutable
data class LogHeaderUiModel(
  val taskDescription: String,
  val result: AutomationResultType,
  val startTime: String,
  val duration: String,
  val stepCount: Int,
  val errorCount: Int,
)

@Immutable
data class MergedStepUiModel(
  val index: Int,
  val actionName: String,
  val result: ActionResult,
  val durationText: String,
  val aiResponseTimeMs: Long?,
  val reasoning: String?,
  val formattedParams: String?,
)

sealed interface LogDetailEffect {
  data class ShareLog(val json: String) : LogDetailEffect
  data class ShowError(val message: String) : LogDetailEffect
}

sealed interface ExecutionLogDetailEvent {
  data object ExportLog : ExecutionLogDetailEvent
}

@KoinViewModel
class ExecutionLogDetailViewModel(
  private val executionLogRepository: ExecutionLogRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(LogDetailUiState())
  val uiState: StateFlow<LogDetailUiState> = _uiState.asStateFlow()

  private val _effect = Channel<LogDetailEffect>()
  val effect = _effect.receiveAsFlow()

  private var currentLogId: String? = null

  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

  fun loadLog(logId: String) {
    if (logId == currentLogId && _uiState.value.header != null) return

    currentLogId = logId
    _uiState.update { it.copy(isLoading = true, error = null) }

    viewModelScope.launch {
      try {
        val log = executionLogRepository.getLog(logId)
        if (log == null) {
          _uiState.update { it.copy(isLoading = false, error = "Log not found") }
          return@launch
        }

        val header = mapToHeader(log)
        val steps = mapToSteps(log)

        _uiState.update {
          it.copy(
            isLoading = false,
            header = header,
            steps = steps,
            errors = log.errors,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, error = e.message ?: "Failed to load log")
        }
      }
    }
  }

  fun onEvent(event: ExecutionLogDetailEvent) {
    when (event) {
      is ExecutionLogDetailEvent.ExportLog -> exportLog()
    }
  }

  private fun exportLog() {
    val logId = currentLogId ?: return
    viewModelScope.launch {
      try {
        val json = executionLogRepository.exportAsJson(logId)
        if (json != null) {
          _effect.send(LogDetailEffect.ShareLog(json))
        } else {
          _effect.send(LogDetailEffect.ShowError("Log data is empty"))
        }
      } catch (e: Exception) {
        _effect.send(LogDetailEffect.ShowError("Export failed: ${e.message}"))
      }
    }
  }

  private fun mapToHeader(log: ExecutionLog): LogHeaderUiModel {
    return LogHeaderUiModel(
      taskDescription = log.taskDescription,
      result = log.result,
      startTime = dateFormatter.format(Instant.ofEpochMilli(log.startTimeMs)),
      duration = formatDuration(log.totalDurationMs),
      stepCount = log.stepCount,
      errorCount = log.errors.size,
    )
  }

  private fun mapToSteps(log: ExecutionLog): List<MergedStepUiModel> {
    val aiResponseMap = log.aiResponses.associateBy { it.step }

    return log.actions.map { action ->
      val aiResponse = aiResponseMap[action.step]
      MergedStepUiModel(
        index = action.step,
        actionName = action.action.name,
        result = action.result,
        durationText = "${action.durationMs}ms",
        aiResponseTimeMs = aiResponse?.responseTimeMs,
        reasoning = aiResponse?.reasoning ?: action.reasoning,
        formattedParams = formatParameters(action.parameters),
      )
    }
  }

  private fun formatParameters(params: ActionParameters?): String? {
    if (params == null) return null
    return buildString {
      if (params.x != null && params.y != null) {
        append("(${params.x}, ${params.y})")
      }
      if (params.x1 != null && params.y1 != null && params.x2 != null && params.y2 != null) {
        if (isNotEmpty()) append(" ")
        append("(${params.x1}, ${params.y1})  (${params.x2}, ${params.y2})")
      }
      params.text?.let { if (isNotEmpty()) append(" "); append("\"$it\"") }
      params.duration?.let { if (isNotEmpty()) append(" "); append("${it}ms") }
      params.message?.let { if (isNotEmpty()) append(" "); append("\"$it\"") }
    }.ifEmpty { null }
  }

  private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60000) % 60
    val hours = ms / 3600000
    return when {
      hours > 0 -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
      minutes > 0 -> String.format(Locale.US, "%d:%02d", minutes, seconds)
      else -> String.format(Locale.US, "0:%02d", seconds)
    }
  }
}
