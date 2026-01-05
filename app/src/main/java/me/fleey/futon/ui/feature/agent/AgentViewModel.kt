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
package me.fleey.futon.ui.feature.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.ai.routing.InferenceSource
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.history.ExecutionLogRepository
import me.fleey.futon.data.history.TaskHistoryRepository
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.domain.automation.AutomationEngine
import me.fleey.futon.domain.automation.models.ActionLogEntry
import me.fleey.futon.domain.automation.models.ActionResult
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.domain.automation.models.ExecutionPhase
import me.fleey.futon.ui.ConversationEvent
import me.fleey.futon.ui.ConversationManager
import me.fleey.futon.ui.designsystem.component.chat.ChatAttachment
import me.fleey.futon.ui.designsystem.component.chat.SlashCommand
import me.fleey.futon.ui.feature.agent.models.AIResponseMetadata
import me.fleey.futon.ui.feature.agent.models.AIResponseType
import me.fleey.futon.ui.feature.agent.models.ChatMessage
import org.koin.android.annotation.KoinViewModel

data class AgentUiState(
  val taskInput: String = "",
  val automationState: AutomationState = AutomationState.Idle,
  val isSettingsConfigured: Boolean = false,
  val isCheckingSettings: Boolean = true,
  val messages: List<ChatMessage> = emptyList(),
  val showWelcome: Boolean = true,
  val currentExecutionLogId: String? = null,
  val selectedSourceOverride: InferenceSource? = null,
  val attachments: List<ChatAttachment> = emptyList(),
)

sealed interface AgentUiEvent {
  data class TaskInputChanged(val value: String) : AgentUiEvent
  data object StartTask : AgentUiEvent
  data object StopTask : AgentUiEvent
  data object ResetState : AgentUiEvent
  data object RetryLastStep : AgentUiEvent
  data object ClearMessages : AgentUiEvent
  data class CopyMessage(val content: String) : AgentUiEvent
  data class UseExampleTask(val task: String) : AgentUiEvent
  data object ViewExecutionLog : AgentUiEvent
  data class SetSourceOverride(val source: InferenceSource?) : AgentUiEvent
  data class AddAttachment(val attachment: ChatAttachment) : AgentUiEvent
  data class RemoveAttachment(val id: String) : AgentUiEvent
  data object RequestAttachment : AgentUiEvent
  data class SlashCommandSelected(val command: SlashCommand) : AgentUiEvent
}

@KoinViewModel
class AgentViewModel(
  private val automationEngine: AutomationEngine,
  private val settingsRepository: SettingsRepository,
  private val executionLogRepository: ExecutionLogRepository,
  private val taskHistoryRepository: TaskHistoryRepository,
  private val conversationManager: ConversationManager,
  private val daemonRepository: DaemonRepository,
  private val promptRepository: PromptRepository,
  private val providerRepository: ProviderRepository,
  private val context: Context,
) : ViewModel() {

  private val _uiState = MutableStateFlow(AgentUiState())
  val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

  val automationMode: StateFlow<AutomationMode> = automationEngine.automationMode
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = AutomationMode.HYBRID,
    )

  val aiDecisionMode: StateFlow<AIDecisionMode> = automationEngine.aiDecisionMode
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = AIDecisionMode.IDLE,
    )

  val daemonState: StateFlow<DaemonState> = daemonRepository.daemonState
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = DaemonState.Stopped,
    )

  private val _slashCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
  val slashCommands: StateFlow<List<SlashCommand>> = _slashCommands.asStateFlow()

  private var previousState: AutomationState = AutomationState.Idle
  private var lastProcessedActionCount = 0
  private var lastProcessedReasoning: String? = null

  init {
    observeSettings()
    observeAutomationState()
    observeConversationEvents()
    observeQuickPhrases()
  }

  private fun observeSettings() {
    viewModelScope.launch {
      providerRepository.getProvidersFlow().collect { providers ->
        val hasConfiguredProvider = providers.any { it.isConfigured() && it.enabled }
        _uiState.update {
          it.copy(
            isSettingsConfigured = hasConfiguredProvider,
            isCheckingSettings = false,
          )
        }
      }
    }
  }

  private fun observeAutomationState() {
    viewModelScope.launch {
      automationEngine.state.collect { state ->
        processStateTransition(previousState, state)
        previousState = state
        _uiState.update { it.copy(automationState = state) }
      }
    }
  }

  private fun observeConversationEvents() {
    viewModelScope.launch {
      conversationManager.events.collect { event ->
        when (event) {
          is ConversationEvent.LoadConversation -> {
            loadConversationFromHistory(event.item.taskDescription, event.item.executionLogId)
          }

          ConversationEvent.ClearConversation -> {
            clearMessages()
          }
        }
      }
    }
  }

  private fun loadConversationFromHistory(taskDescription: String, executionLogId: String?) {
    viewModelScope.launch {
      val messages = mutableListOf<ChatMessage>()
      messages.add(ChatMessage.UserTask(taskDescription = taskDescription))

      if (executionLogId != null) {
        val log = executionLogRepository.getLog(executionLogId)
        if (log != null) {
          log.aiResponses.forEachIndexed { index, response ->
            response.reasoning?.let { reasoning ->
              messages.add(
                ChatMessage.AIResponse(
                  type = AIResponseType.REASONING,
                  content = reasoning,
                  metadata = AIResponseMetadata(
                    stepNumber = response.step,
                    reasoning = reasoning,
                  ),
                ),
              )
            }

            val actionName = response.action.name
            messages.add(
              ChatMessage.AIResponse(
                type = AIResponseType.ACTION,
                content = actionName,
                metadata = AIResponseMetadata(
                  actionType = actionName,
                  stepNumber = response.step,
                  maxSteps = log.stepCount,
                  durationMs = response.responseTimeMs,
                ),
              ),
            )
          }

          log.errors.forEach { error ->
            messages.add(
              ChatMessage.AIResponse(
                type = AIResponseType.ERROR,
                content = error.message,
                metadata = AIResponseMetadata(stepNumber = error.step),
              ),
            )
          }

          val resultType = when (log.result) {
            AutomationResultType.SUCCESS -> AIResponseType.RESULT_SUCCESS
            AutomationResultType.FAILURE -> AIResponseType.RESULT_FAILURE
            AutomationResultType.CANCELLED, AutomationResultType.TIMEOUT -> AIResponseType.ERROR
          }
          val resultContent = when (log.result) {
            AutomationResultType.SUCCESS -> context.getString(R.string.chat_task_completed)
            AutomationResultType.FAILURE -> {
              val failureReason = log.errors.lastOrNull()?.message ?: ""
              context.getString(R.string.chat_task_failed, failureReason)
            }

            AutomationResultType.CANCELLED -> context.getString(R.string.chat_task_cancelled)
            AutomationResultType.TIMEOUT -> context.getString(R.string.chat_task_timeout)
          }
          messages.add(
            ChatMessage.AIResponse(
              type = resultType,
              content = resultContent,
              metadata = AIResponseMetadata(
                durationMs = log.totalDurationMs,
              ),
            ),
          )
        }
      }

      _uiState.update { state ->
        state.copy(
          messages = messages,
          showWelcome = false,
        )
      }
    }
  }

  private fun processStateTransition(oldState: AutomationState, newState: AutomationState) {
    when {
      oldState is AutomationState.Idle && newState is AutomationState.Running -> {
        handleTaskStarted(newState)
      }

      oldState is AutomationState.Running && newState is AutomationState.Running -> {
        handleRunningStateUpdate(oldState, newState)
      }

      newState is AutomationState.Completed -> {
        handleTaskCompleted(newState.result)
      }
    }
  }

  private fun handleTaskStarted(state: AutomationState.Running) {
    lastProcessedActionCount = 0
    lastProcessedReasoning = null
    addAIMessage(
      type = AIResponseType.STEP_PROGRESS,
      content = context.getString(R.string.chat_task_started),
      metadata = AIResponseMetadata(
        stepNumber = state.currentStep,
        maxSteps = state.maxSteps,
      ),
    )
  }

  private fun handleRunningStateUpdate(
    oldState: AutomationState.Running,
    newState: AutomationState.Running,
  ) {
    if (oldState.phase != newState.phase) {
      handlePhaseChange(newState)
    }

    if (newState.currentReasoning != null && newState.currentReasoning != lastProcessedReasoning) {
      lastProcessedReasoning = newState.currentReasoning
      addAIMessage(
        type = AIResponseType.REASONING,
        content = newState.currentReasoning,
        metadata = AIResponseMetadata(
          stepNumber = newState.currentStep,
          maxSteps = newState.maxSteps,
          reasoning = newState.currentReasoning,
        ),
      )
    }

    val newActions = newState.actionHistory.drop(lastProcessedActionCount)
    newActions.forEach { action ->
      handleNewAction(action, newState.maxSteps)
    }
    lastProcessedActionCount = newState.actionHistory.size

    if (oldState.currentStep != newState.currentStep && newState.currentStep > 1) {
      addAIMessage(
        type = AIResponseType.STEP_PROGRESS,
        content = context.getString(
          R.string.step_progress,
          newState.currentStep,
          newState.maxSteps,
        ),
        metadata = AIResponseMetadata(
          stepNumber = newState.currentStep,
          maxSteps = newState.maxSteps,
        ),
      )
    }
  }

  private fun handlePhaseChange(state: AutomationState.Running) {
    val content = when (state.phase) {
      ExecutionPhase.CAPTURING_SCREENSHOT -> context.getString(R.string.chat_phase_capturing)
      ExecutionPhase.ANALYZING_WITH_AI -> context.getString(R.string.chat_phase_analyzing)
      ExecutionPhase.EXECUTING_ACTION -> context.getString(R.string.chat_phase_executing)
      ExecutionPhase.WAITING -> context.getString(R.string.chat_phase_waiting)
      ExecutionPhase.RETRYING -> state.retryReason?.let {
        context.getString(R.string.chat_phase_retrying_reason, it)
      } ?: context.getString(R.string.chat_phase_retrying)
    }

    addAIMessage(
      type = AIResponseType.THINKING,
      content = content,
      metadata = AIResponseMetadata(
        stepNumber = state.currentStep,
        maxSteps = state.maxSteps,
      ),
    )
  }

  private fun handleNewAction(action: ActionLogEntry, maxSteps: Int) {
    val isSuccess = action.result is ActionResult.Success
    val actionParams = action.parameters?.let { params ->
      when (action.action.name) {
        "TAP" -> context.getString(R.string.chat_action_tap_position, params.x, params.y)
        "SWIPE" -> context.getString(
          R.string.chat_action_swipe_from_to,
          params.x1,
          params.y1,
          params.x2,
          params.y2,
        )

        "INPUT" -> context.getString(R.string.chat_action_input_text, params.text)
        "WAIT" -> context.getString(R.string.chat_action_wait_duration, params.duration)
        else -> params.toString()
      }
    }

    val content = buildString {
      append(action.action.name)
      if (actionParams != null) {
        append(" - $actionParams")
      }
    }

    addAIMessage(
      type = AIResponseType.ACTION,
      content = content,
      metadata = AIResponseMetadata(
        actionType = action.action.name,
        actionParams = actionParams,
        isSuccess = isSuccess,
        durationMs = action.durationMs,
        stepNumber = action.step,
        maxSteps = maxSteps,
        reasoning = action.reasoning,
      ),
    )
  }

  private fun handleTaskCompleted(result: AutomationResult) {
    lastProcessedActionCount = 0
    lastProcessedReasoning = null

    viewModelScope.launch {
      val history = taskHistoryRepository.getHistoryFlow().first()
      val latestTask = history.firstOrNull()
      _uiState.update { it.copy(currentExecutionLogId = latestTask?.executionLogId) }
    }

    when (result) {
      is AutomationResult.Success -> {
        addAIMessage(
          type = AIResponseType.RESULT_SUCCESS,
          content = context.getString(R.string.chat_task_completed),
        )
      }

      is AutomationResult.Failure -> {
        addAIMessage(
          type = AIResponseType.RESULT_FAILURE,
          content = context.getString(R.string.chat_task_failed, result.reason),
          metadata = AIResponseMetadata(
            suggestions = listOf(
              context.getString(R.string.suggestion_check_screen),
              context.getString(R.string.suggestion_retry_task),
              context.getString(R.string.suggestion_adjust_description),
            ),
          ),
        )
      }

      is AutomationResult.Cancelled -> {
        addAIMessage(
          type = AIResponseType.ERROR,
          content = context.getString(R.string.chat_task_cancelled),
        )
      }

      is AutomationResult.Timeout -> {
        addAIMessage(
          type = AIResponseType.ERROR,
          content = context.getString(R.string.chat_task_timeout),
          metadata = AIResponseMetadata(
            suggestions = listOf(
              context.getString(R.string.suggestion_increase_max_steps),
              context.getString(R.string.suggestion_simplify_task),
              context.getString(R.string.suggestion_check_network),
            ),
          ),
        )
      }
    }
  }

  private fun addAIMessage(
    type: AIResponseType,
    content: String,
    metadata: AIResponseMetadata? = null,
  ) {
    val message = ChatMessage.AIResponse(
      type = type,
      content = content,
      metadata = metadata,
    )
    addMessage(message)
  }

  fun onEvent(event: AgentUiEvent) {
    when (event) {
      is AgentUiEvent.TaskInputChanged -> _uiState.update { it.copy(taskInput = event.value) }
      AgentUiEvent.StartTask -> startTask()
      AgentUiEvent.StopTask -> stopTask()
      AgentUiEvent.ResetState -> resetState()
      AgentUiEvent.RetryLastStep -> retryLastStep()
      AgentUiEvent.ClearMessages -> clearMessages()
      is AgentUiEvent.CopyMessage -> copyToClipboard(event.content)
      is AgentUiEvent.UseExampleTask -> useExampleTask(event.task)
      AgentUiEvent.ViewExecutionLog -> {}
      is AgentUiEvent.SetSourceOverride -> setSourceOverride(event.source)
      is AgentUiEvent.AddAttachment -> addAttachment(event.attachment)
      is AgentUiEvent.RemoveAttachment -> removeAttachment(event.id)
      AgentUiEvent.RequestAttachment -> {}
      is AgentUiEvent.SlashCommandSelected -> applySlashCommand(event.command)
    }
  }

  private fun observeQuickPhrases() {
    viewModelScope.launch {
      promptRepository.getPromptSettings().collect { settings ->
        if (settings.enableQuickPhrases) {
          _slashCommands.value = settings.quickPhrases
            .filter { it.isEnabled }
            .map { phrase ->
              SlashCommand(
                id = phrase.id,
                trigger = phrase.trigger,
                expansion = phrase.expansion,
                description = phrase.description,
              )
            }
        } else {
          _slashCommands.value = emptyList()
        }
      }
    }
  }

  private fun applySlashCommand(command: SlashCommand) {
    _uiState.update { it.copy(taskInput = command.expansion) }
  }

  private fun addAttachment(attachment: ChatAttachment) {
    _uiState.update { state ->
      if (state.attachments.size < 5) {
        state.copy(attachments = state.attachments + attachment)
      } else {
        state
      }
    }
  }

  private fun removeAttachment(id: String) {
    _uiState.update { state ->
      state.copy(attachments = state.attachments.filter { it.id != id })
    }
  }

  private fun setSourceOverride(source: InferenceSource?) {
    _uiState.update { it.copy(selectedSourceOverride = source) }
  }

  fun isDaemonReady(): Boolean {
    return daemonState.value is DaemonState.Ready
  }

  fun setTaskInput(task: String) {
    _uiState.update { it.copy(taskInput = task) }
  }

  fun addMessage(message: ChatMessage) {
    _uiState.update { state ->
      state.copy(
        messages = state.messages + message,
        showWelcome = false,
      )
    }
  }

  private fun startTask() {
    val task = _uiState.value.taskInput.trim()
    if (task.isBlank()) return

    lastProcessedActionCount = 0
    lastProcessedReasoning = null

    val userMessage = ChatMessage.UserTask(taskDescription = task)
    addMessage(userMessage)

    me.fleey.futon.service.AutomationForegroundService.startTask(context, task)
  }

  private fun stopTask() {
    me.fleey.futon.service.AutomationForegroundService.stopTask(context)
    automationEngine.stopTask()
  }

  private fun resetState() {
    _uiState.update { it.copy(automationState = AutomationState.Idle) }
  }

  private fun retryLastStep() {
    val task = _uiState.value.messages
      .filterIsInstance<ChatMessage.UserTask>()
      .lastOrNull()
      ?.taskDescription
      ?.trim()
      ?: _uiState.value.taskInput.trim()

    if (task.isBlank()) return

    me.fleey.futon.service.AutomationForegroundService.startTask(context, task)
  }

  private fun clearMessages() {
    _uiState.update { state ->
      state.copy(
        messages = emptyList(),
        showWelcome = true,
      )
    }
  }

  private fun copyToClipboard(content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label), content)
    clipboard.setPrimaryClip(clip)
  }

  private fun useExampleTask(task: String) {
    _uiState.update { it.copy(taskInput = task) }
  }
}
