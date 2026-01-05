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
package me.fleey.futon.ui.feature.settings.daemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.deployment.RootState
import me.fleey.futon.data.daemon.models.DaemonLifecycleState
import me.fleey.futon.data.daemon.models.StartupPhase
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSliderItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun DaemonSettingsScreen(
  onBack: () -> Unit,
  viewModel: DaemonSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(uiState.error) {
    uiState.error?.let { error ->
      snackbarHostState.showSnackbar(context.getString(error.messageRes))
      viewModel.onEvent(DaemonSettingsUiEvent.DismissError)
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_daemon),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      DaemonStatusSection(uiState)

      Spacer(modifier = Modifier.height(12.dp))

      DaemonControlSection(
        uiState = uiState,
        onStart = { viewModel.onEvent(DaemonSettingsUiEvent.StartDaemon) },
        onStop = { viewModel.onEvent(DaemonSettingsUiEvent.StopDaemon) },
        onRestart = { viewModel.onEvent(DaemonSettingsUiEvent.RestartDaemon) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      LifecycleSettingsSection(
        uiState = uiState,
        onKeepAliveChange = { viewModel.onEvent(DaemonSettingsUiEvent.SetKeepAliveSeconds(it)) },
        onAutoStopChange = { viewModel.onEvent(DaemonSettingsUiEvent.SetAutoStopEnabled(it)) },
      )

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun DaemonStatusSection(uiState: DaemonSettingsUiState) {
  SettingsGroup(title = stringResource(R.string.daemon_status)) {
    item {
      DaemonStatusItem(uiState.lifecycleState)
    }
    item {
      RootStatusItem(uiState.rootState)
    }
  }

  val logs = when (val state = uiState.lifecycleState) {
    is DaemonLifecycleState.Starting -> state.logs
    is DaemonLifecycleState.Failed -> state.logs
    else -> emptyList()
  }

  if (logs.isNotEmpty()) {
    Spacer(modifier = Modifier.height(12.dp))
    StartupLogsSection(
      logs = logs,
      elapsedMs = (uiState.lifecycleState as? DaemonLifecycleState.Starting)?.elapsedMs,
    )
  }
}

@Composable
private fun DaemonStatusItem(state: DaemonLifecycleState) {
  val (statusText, statusColor, icon) = when (state) {
    is DaemonLifecycleState.Stopped -> Triple(
      stringResource(R.string.daemon_status_stopped),
      FutonTheme.colors.textMuted,
      FutonIcons.Stop,
    )

    is DaemonLifecycleState.Starting -> Triple(
      stringResource(R.string.daemon_status_starting),
      FutonTheme.colors.statusWarning,
      FutonIcons.Refresh,
    )

    is DaemonLifecycleState.Running -> Triple(
      stringResource(R.string.daemon_status_running),
      FutonTheme.colors.statusPositive,
      FutonIcons.CheckCircle,
    )

    is DaemonLifecycleState.Stopping -> Triple(
      stringResource(R.string.daemon_status_stopping),
      FutonTheme.colors.statusWarning,
      FutonIcons.Refresh,
    )

    is DaemonLifecycleState.Failed -> Triple(
      stringResource(R.string.daemon_status_failed),
      FutonTheme.colors.statusDanger,
      FutonIcons.Error,
    )
  }

  val phaseText = if (state is DaemonLifecycleState.Starting) {
    when (state.phase) {
      StartupPhase.Initializing -> stringResource(R.string.daemon_phase_initializing)
      StartupPhase.CheckingRoot -> stringResource(R.string.daemon_phase_checking_root)
      StartupPhase.CheckingBinary -> stringResource(R.string.daemon_phase_checking_binary)
      StartupPhase.Deploying -> stringResource(R.string.daemon_phase_deploying)
      StartupPhase.ExecutingStart -> stringResource(R.string.daemon_phase_executing)
      StartupPhase.WaitingForBinder -> stringResource(R.string.daemon_phase_waiting_binder)
      StartupPhase.VerifyingVersion -> stringResource(R.string.daemon_phase_verifying)
      StartupPhase.Connecting -> stringResource(R.string.daemon_phase_connecting)
    }
  } else null

  val description = if (phaseText != null) {
    "$statusText - $phaseText"
  } else {
    statusText
  }

  SettingsItem(
    title = stringResource(R.string.daemon_lifecycle_state),
    description = description,
    leadingIcon = icon,
    trailing = {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = statusColor,
        modifier = Modifier.size(20.dp),
      )
    },
  )
}

@Composable
private fun RootStatusItem(state: RootState) {
  val (statusText, statusColor) = when (state) {
    is RootState.NotChecked -> Pair(
      stringResource(R.string.daemon_root_checking),
      FutonTheme.colors.textMuted,
    )

    is RootState.Available -> Pair(
      stringResource(R.string.daemon_root_available),
      FutonTheme.colors.statusPositive,
    )

    is RootState.Unavailable -> Pair(
      stringResource(R.string.daemon_root_unavailable),
      FutonTheme.colors.statusDanger,
    )

    is RootState.SELinuxBlocked -> Pair(
      stringResource(R.string.daemon_root_selinux_blocked),
      FutonTheme.colors.statusWarning,
    )
  }

  SettingsItem(
    title = stringResource(R.string.daemon_root_status),
    description = statusText,
    leadingIcon = FutonIcons.Root,
    trailing = {
      Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
      )
    },
  )
}

@Composable
private fun DaemonControlSection(
  uiState: DaemonSettingsUiState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onRestart: () -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.daemon_control)) {
    if (uiState.isRunning) {
      item {
        SettingsItem(
          title = stringResource(R.string.daemon_stop),
          description = stringResource(R.string.daemon_stop_description),
          leadingIcon = FutonIcons.Stop,
          onClick = if (uiState.canStop) onStop else null,
          trailing = {
            if (uiState.isStopping) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
              )
            }
          },
        )
      }
      item {
        SettingsItem(
          title = stringResource(R.string.daemon_restart),
          description = stringResource(R.string.daemon_restart_description),
          leadingIcon = FutonIcons.Refresh,
          onClick = if (!uiState.isStarting && !uiState.isStopping) onRestart else null,
        )
      }
    } else {
      item {
        SettingsItem(
          title = stringResource(R.string.daemon_start),
          description = stringResource(R.string.daemon_start_description),
          leadingIcon = FutonIcons.Play,
          onClick = if (uiState.canStart) onStart else null,
          trailing = {
            if (uiState.isStarting) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Icon(
                imageVector = FutonIcons.ChevronRight,
                contentDescription = null,
                tint = FutonTheme.colors.statusPositive,
                modifier = Modifier.size(20.dp),
              )
            }
          },
        )
      }
    }
  }
}

@Composable
private fun LifecycleSettingsSection(
  uiState: DaemonSettingsUiState,
  onKeepAliveChange: (Int) -> Unit,
  onAutoStopChange: (Boolean) -> Unit,
) {
  val context = LocalContext.current

  SettingsGroup(title = stringResource(R.string.daemon_lifecycle_settings)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.daemon_auto_stop),
        description = stringResource(R.string.daemon_auto_stop_description),
        checked = uiState.autoStopEnabled,
        onCheckedChange = onAutoStopChange,
        leadingIcon = FutonIcons.Timer,
      )
    }
    if (uiState.autoStopEnabled) {
      item {
        SettingsSliderItem(
          title = stringResource(R.string.daemon_keep_alive),
          description = stringResource(R.string.daemon_keep_alive_description),
          value = uiState.keepAliveSeconds.toFloat(),
          onValueChange = { onKeepAliveChange(it.toInt()) },
          valueRange = 10f..300f,
          steps = 28,
          leadingIcon = FutonIcons.Timer,
          valueFormatter = { context.getString(R.string.settings_timeout_seconds, it.toInt()) },
        )
      }
    }
  }
}

@Composable
private fun StartupLogsSection(
  logs: List<String>,
  elapsedMs: Long?,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.daemon_startup_logs),
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textMuted,
      )
      if (elapsedMs != null) {
        Text(
          text = stringResource(R.string.daemon_elapsed_time, elapsedMs / 1000.0),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
      color = FutonTheme.colors.backgroundSecondary,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
      ) {
        logs.takeLast(10).forEach { log ->
          Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 2.dp),
          )
        }
      }
    }
  }
}
