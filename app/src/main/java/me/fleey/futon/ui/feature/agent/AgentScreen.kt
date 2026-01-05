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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.fleey.futon.R
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.chat.ChatAttachment
import me.fleey.futon.ui.designsystem.component.chat.ChatInputBar
import me.fleey.futon.ui.designsystem.component.chat.ChatMessageList
import me.fleey.futon.ui.designsystem.component.chat.SettingsRequiredCard
import me.fleey.futon.ui.designsystem.component.chat.SlashCommand
import me.fleey.futon.ui.designsystem.component.chat.TypingIndicator
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.components.MinimalHeader
import me.fleey.futon.ui.designsystem.components.WelcomeContent
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.agent.components.AutomationProgressCard
import me.fleey.futon.ui.feature.agent.models.ChatMessage
import org.koin.androidx.compose.koinViewModel

@Composable
fun AgentScreen(
  onOpenDrawer: () -> Unit,
  onNavigateToSettings: () -> Unit,
  onNavigateToDaemonSettings: () -> Unit,
  onNavigateToLogDetail: (String) -> Unit,
  prefillTask: String? = null,
  daemonState: DaemonState = DaemonState.Stopped,
  onRetryConnection: (() -> Unit)? = null,
  viewModel: AgentViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val automationMode by viewModel.automationMode.collectAsStateWithLifecycle()
  val aiDecisionMode by viewModel.aiDecisionMode.collectAsStateWithLifecycle()
  val currentDaemonState by viewModel.daemonState.collectAsStateWithLifecycle()
  val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()

  val onEvent: (AgentUiEvent) -> Unit = remember(viewModel) {
    { viewModel.onEvent(it) }
  }

  LaunchedEffect(prefillTask) {
    prefillTask?.let { onEvent(AgentUiEvent.TaskInputChanged(it)) }
  }

  val imagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    uri?.let {
      onEvent(AgentUiEvent.AddAttachment(ChatAttachment.Image(uri = it)))
    }
  }

  FutonLoadingOverlay(loading = uiState.isCheckingSettings) {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .imePadding(),
      containerColor = FutonTheme.colors.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        AgentBottomBar(
          taskInput = uiState.taskInput,
          automationState = uiState.automationState,
          daemonState = currentDaemonState,
          isSettingsConfigured = uiState.isSettingsConfigured,
          attachments = uiState.attachments,
          automationMode = automationMode,
          aiDecisionMode = aiDecisionMode,
          slashCommands = slashCommands,
          onEvent = onEvent,
          onNavigateToDaemonSettings = onNavigateToDaemonSettings,
          onPickImage = {
            imagePickerLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
          },
        )
      },
    ) { paddingValues ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(paddingValues),
      ) {
        AgentScreenContent(
          messages = uiState.messages,
          showWelcome = uiState.showWelcome,
          isSettingsConfigured = uiState.isSettingsConfigured,
          isDaemonReady = currentDaemonState is DaemonState.Ready,
          topBarHeight = FutonSizes.MinimalHeaderHeight,
          onMessageLongPress = remember(onEvent) {
            { msg -> onEvent(AgentUiEvent.CopyMessage(extractContent(msg))) }
          },
          onRetry = { onEvent(AgentUiEvent.RetryLastStep) },
          onSuggestionClick = { onEvent(AgentUiEvent.UseExampleTask(it)) },
          onSettingsClick = onNavigateToSettings,
          onViewLog = { uiState.currentExecutionLogId?.let(onNavigateToLogDetail) },
        )

        MinimalHeader(
          onMenuClick = onOpenDrawer,
          onNewConversationClick = { viewModel.onEvent(AgentUiEvent.ClearMessages) },
        )
      }
    }
  }
}

private fun extractContent(message: ChatMessage): String = when (message) {
  is ChatMessage.UserTask -> message.taskDescription
  is ChatMessage.AIResponse -> message.content
  is ChatMessage.SystemMessage -> message.content
}

@Composable
private fun AgentBottomBar(
  taskInput: String,
  automationState: AutomationState,
  daemonState: DaemonState,
  isSettingsConfigured: Boolean,
  attachments: List<ChatAttachment>,
  automationMode: AutomationMode,
  aiDecisionMode: AIDecisionMode,
  slashCommands: List<SlashCommand>,
  onEvent: (AgentUiEvent) -> Unit,
  onNavigateToDaemonSettings: () -> Unit,
  onPickImage: () -> Unit,
) {
  val isRunning = automationState is AutomationState.Running
  val runningState = automationState as? AutomationState.Running
  val isDaemonReady = daemonState is DaemonState.Ready

  Column(
    modifier = Modifier.navigationBarsPadding(),
  ) {
    AnimatedVisibility(
      visible = isRunning,
      enter = slideInVertically { it } + fadeIn(),
      exit = slideOutVertically { it } + fadeOut(),
    ) {
      if (runningState != null) {
        Column {
          AutomationProgressCard(
            currentStep = runningState.currentStep,
            maxSteps = runningState.maxSteps,
            automationMode = automationMode,
            aiDecisionMode = aiDecisionMode,
            hotPathHits = 0,
            aiCalls = runningState.actionHistory.size,
            loopDetected = false,
            loopCount = 0,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
          TypingIndicator(phase = runningState.phase)
        }
      }
    }

    AnimatedVisibility(
      visible = !isDaemonReady && !isRunning,
      enter = slideInVertically { it } + fadeIn(),
      exit = slideOutVertically { it } + fadeOut(),
    ) {
      DaemonUnavailableCard(
        daemonState = daemonState,
        onClick = onNavigateToDaemonSettings,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    ChatInputBar(
      value = taskInput,
      onValueChange = { onEvent(AgentUiEvent.TaskInputChanged(it)) },
      onSend = { onEvent(AgentUiEvent.StartTask) },
      onStop = { onEvent(AgentUiEvent.StopTask) },
      isRunning = isRunning,
      isEnabled = isSettingsConfigured && isDaemonReady,
      attachments = attachments,
      onAttachmentClick = onPickImage,
      onRemoveAttachment = { onEvent(AgentUiEvent.RemoveAttachment(it)) },
      slashCommands = slashCommands,
      onSlashCommandSelected = { onEvent(AgentUiEvent.SlashCommandSelected(it)) },
    )
  }
}

@Composable
private fun AgentScreenContent(
  messages: List<ChatMessage>,
  showWelcome: Boolean,
  isSettingsConfigured: Boolean,
  isDaemonReady: Boolean,
  topBarHeight: Dp,
  onMessageLongPress: (ChatMessage) -> Unit,
  onRetry: () -> Unit,
  onSuggestionClick: (String) -> Unit,
  onSettingsClick: () -> Unit,
  onViewLog: () -> Unit,
) {
  when {
    !isSettingsConfigured -> {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        SettingsRequiredCard(
          message = stringResource(R.string.settings_required_message),
          onSettingsClick = onSettingsClick,
        )
      }
    }

    messages.isEmpty() && showWelcome -> {
      WelcomeContent(onSuggestionClick = onSuggestionClick)
    }

    else -> {
      ChatMessageList(
        messages = messages,
        topPadding = topBarHeight,
        onMessageLongPress = onMessageLongPress,
        onRetry = onRetry,
        onExampleTaskClick = onSuggestionClick,
        onSettingsClick = onSettingsClick,
        onViewLog = onViewLog,
      )
    }
  }
}

@Composable
private fun DaemonUnavailableCard(
  daemonState: DaemonState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    shape = FutonShapes.CardShape,
    color = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.daemon_unavailable_title),
          style = MaterialTheme.typography.titleSmall,
          color = FutonTheme.colors.statusWarning,
        )
        Text(
          text = getDaemonStateMessage(daemonState),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
      Icon(
        imageVector = FutonIcons.ChevronRight,
        contentDescription = null,
        tint = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun getDaemonStateMessage(state: DaemonState): String {
  return when (state) {
    is DaemonState.Stopped -> stringResource(R.string.daemon_state_stopped)
    is DaemonState.Starting -> stringResource(R.string.daemon_state_starting)
    is DaemonState.Connecting -> stringResource(R.string.daemon_state_connecting)
    is DaemonState.Authenticating -> stringResource(R.string.daemon_state_authenticating)
    is DaemonState.Reconciling -> stringResource(R.string.daemon_state_reconciling)
    is DaemonState.Ready -> stringResource(R.string.daemon_state_ready)
    is DaemonState.Error -> state.message
  }
}
