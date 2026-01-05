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
package me.fleey.futon.ui.feature.settings.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.daemon.models.InputDeviceEntry
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.MTProtocol
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.selection.FutonRadioButton
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExecutionSettingsScreen(
  onBack: () -> Unit,
  viewModel: ExecutionSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  var showSELinuxDialog by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      snackbarHostState.showSnackbar(context.getString(R.string.settings_saved))
    }
  }

  LaunchedEffect(uiState.errorMessage) {
    uiState.errorMessage?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.onEvent(ExecutionSettingsUiEvent.DismissError)
    }
  }

  uiState.selinuxFixResult?.let { result ->
    SELinuxResultDialog(
      result = result,
      onDismiss = { viewModel.onEvent(ExecutionSettingsUiEvent.DismissSELinuxResult) },
    )
  }

  if (showSELinuxDialog) {
    SELinuxFixDialog(
      selinuxMode = uiState.selinuxMode,
      isFixing = uiState.isFixingSELinux,
      onConfirm = {
        viewModel.onEvent(ExecutionSettingsUiEvent.FixSELinux)
      },
      onDismiss = { showSELinuxDialog = false },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_execution_layer),
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

      SettingsGroup(title = stringResource(R.string.settings_capability_status)) {
        item {
          DaemonStatusItem(
            daemonState = uiState.daemonState,
            isDaemonConnected = uiState.isDaemonConnected,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_input_method)) {
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_method_auto),
            description = stringResource(R.string.settings_method_auto_description),
            selected = uiState.preferredInputMethod == null,
            onClick = { viewModel.onEvent(ExecutionSettingsUiEvent.InputMethodSelected(null)) },
            recommended = true,
          )
        }
        item {
          InputMethodOptionWithFix(
            method = InputMethod.NATIVE_IOCTL,
            title = stringResource(R.string.settings_input_native),
            description = stringResource(R.string.settings_input_native_description),
            isAvailable = uiState.inputCapabilities.nativeAvailable,
            error = uiState.inputCapabilities.nativeError,
            isSelected = uiState.preferredInputMethod == InputMethod.NATIVE_IOCTL,
            isActive = uiState.activeInputMethod == InputMethod.NATIVE_IOCTL,
            onSelect = { viewModel.onEvent(ExecutionSettingsUiEvent.InputMethodSelected(InputMethod.NATIVE_IOCTL)) },
            showFixButton = !uiState.inputCapabilities.nativeAvailable,
            isFixing = uiState.isFixingSELinux,
            onFixClick = { showSELinuxDialog = true },
          )
        }
        item {
          InputMethodOption(
            method = InputMethod.ANDROID_INPUT,
            title = stringResource(R.string.settings_input_android),
            description = stringResource(R.string.settings_input_android_description),
            isAvailable = uiState.inputCapabilities.androidInputAvailable,
            error = uiState.inputCapabilities.androidInputError,
            isSelected = uiState.preferredInputMethod == InputMethod.ANDROID_INPUT,
            isActive = uiState.activeInputMethod == InputMethod.ANDROID_INPUT,
            onSelect = { viewModel.onEvent(ExecutionSettingsUiEvent.InputMethodSelected(InputMethod.ANDROID_INPUT)) },
          )
        }
        item {
          InputMethodOption(
            method = InputMethod.SHELL_SENDEVENT,
            title = stringResource(R.string.settings_input_shell),
            description = stringResource(R.string.settings_input_shell_description),
            isAvailable = uiState.inputCapabilities.shellAvailable,
            error = uiState.inputCapabilities.shellError,
            isSelected = uiState.preferredInputMethod == InputMethod.SHELL_SENDEVENT,
            isActive = uiState.activeInputMethod == InputMethod.SHELL_SENDEVENT,
            onSelect = { viewModel.onEvent(ExecutionSettingsUiEvent.InputMethodSelected(InputMethod.SHELL_SENDEVENT)) },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_capture_method)) {
        item {
          SettingsRadioItem(
            title = stringResource(R.string.settings_method_auto),
            description = stringResource(R.string.settings_method_auto_description),
            selected = uiState.preferredCaptureMethod == null,
            onClick = { viewModel.onEvent(ExecutionSettingsUiEvent.CaptureMethodSelected(null)) },
            recommended = true,
          )
        }
        item {
          CaptureMethodOption(
            method = CaptureMethod.ROOT_SCREENCAP,
            title = stringResource(R.string.settings_capture_root),
            description = stringResource(R.string.settings_capture_root_description),
            isAvailable = uiState.captureCapabilities?.rootAvailable == true,
            error = uiState.captureCapabilities?.rootError,
            isSelected = uiState.preferredCaptureMethod == CaptureMethod.ROOT_SCREENCAP,
            isActive = uiState.activeCaptureMethod == CaptureMethod.ROOT_SCREENCAP,
            onSelect = {
              viewModel.onEvent(
                ExecutionSettingsUiEvent.CaptureMethodSelected(
                  CaptureMethod.ROOT_SCREENCAP,
                ),
              )
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      InputDeviceSelectionSection(
        selectedPath = uiState.touchDevicePath,
        devices = uiState.inputDevices,
        isLoading = uiState.isLoadingDevices,
        onDeviceSelected = { viewModel.onEvent(ExecutionSettingsUiEvent.TouchDeviceChanged(it)) },
        onRefresh = { viewModel.onEvent(ExecutionSettingsUiEvent.RefreshInputDevices) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.settings_fallback)) {
        item {
          SettingsSwitchItem(
            title = stringResource(R.string.settings_enable_fallback),
            description = stringResource(R.string.settings_enable_fallback_description),
            checked = uiState.enableFallback,
            onCheckedChange = { viewModel.onEvent(ExecutionSettingsUiEvent.FallbackToggled(it)) },
            leadingIcon = FutonIcons.Hybrid,
          )
        }
        item {
          SettingsSwitchItem(
            title = stringResource(R.string.settings_show_warnings),
            description = stringResource(R.string.settings_show_warnings_description),
            checked = uiState.showCapabilityWarnings,
            onCheckedChange = {
              viewModel.onEvent(
                ExecutionSettingsUiEvent.CapabilityWarningsToggled(
                  it,
                ),
              )
            },
            leadingIcon = FutonIcons.Warning,
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun InputMethodOption(
  method: InputMethod,
  title: String,
  description: String,
  isAvailable: Boolean,
  error: String?,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
) {
  MethodOptionItem(
    title = title,
    description = description,
    isAvailable = isAvailable,
    error = error,
    isSelected = isSelected,
    isActive = isActive,
    onSelect = onSelect,
  )
}

@Composable
private fun InputMethodOptionWithFix(
  method: InputMethod,
  title: String,
  description: String,
  isAvailable: Boolean,
  error: String?,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
  showFixButton: Boolean,
  isFixing: Boolean,
  onFixClick: () -> Unit,
) {
  MethodOptionItemWithFix(
    title = title,
    description = description,
    isAvailable = isAvailable,
    error = error,
    isSelected = isSelected,
    isActive = isActive,
    onSelect = onSelect,
    showFixButton = showFixButton,
    isFixing = isFixing,
    onFixClick = onFixClick,
  )
}

@Composable
private fun CaptureMethodOption(
  method: CaptureMethod,
  title: String,
  description: String,
  isAvailable: Boolean,
  error: String?,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
) {
  MethodOptionItem(
    title = title,
    description = description,
    isAvailable = isAvailable,
    error = error,
    isSelected = isSelected,
    isActive = isActive,
    onSelect = onSelect,
  )
}

@Composable
private fun MethodOptionItem(
  title: String,
  description: String,
  isAvailable: Boolean,
  error: String?,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
) {
  val statusText = when {
    isActive -> stringResource(R.string.settings_method_active)
    isAvailable -> stringResource(R.string.settings_method_available)
    else -> error ?: stringResource(R.string.settings_method_unavailable)
  }

  val statusColor = when {
    isActive -> FutonTheme.colors.statusPositive
    isAvailable -> FutonTheme.colors.textMuted
    else -> FutonTheme.colors.statusDanger
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    onClick = { if (isAvailable) onSelect() },
    color = FutonTheme.colors.backgroundSecondary,
    enabled = isAvailable,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isAvailable) FutonTheme.colors.textNormal else FutonTheme.colors.textMuted,
          )
          if (isActive) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = stringResource(R.string.settings_method_active),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusPositive,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted.copy(alpha = if (isAvailable) 1f else 0.6f),
        )
        if (!isAvailable && error != null) {
          Spacer(modifier = Modifier.height(4.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = FutonIcons.Warning,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
              tint = FutonTheme.colors.statusDanger,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = error,
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusDanger,
            )
          }
        }
      }

      FutonRadioButton(
        selected = isSelected,
        onClick = { if (isAvailable) onSelect() },
        enabled = isAvailable,
      )
    }
  }
}


@Composable
private fun MethodOptionItemWithFix(
  title: String,
  description: String,
  isAvailable: Boolean,
  error: String?,
  isSelected: Boolean,
  isActive: Boolean,
  onSelect: () -> Unit,
  showFixButton: Boolean,
  isFixing: Boolean,
  onFixClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    onClick = { if (isAvailable) onSelect() },
    color = FutonTheme.colors.backgroundSecondary,
    enabled = isAvailable,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isAvailable) FutonTheme.colors.textNormal else FutonTheme.colors.textMuted,
          )
          if (isActive) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = stringResource(R.string.settings_method_active),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusPositive,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted.copy(alpha = if (isAvailable) 1f else 0.6f),
        )
        if (!isAvailable && error != null) {
          Spacer(modifier = Modifier.height(4.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = FutonIcons.Warning,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
              tint = FutonTheme.colors.statusDanger,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = error,
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusDanger,
            )
          }

          if (showFixButton) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
              onClick = onFixClick,
              enabled = !isFixing,
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
              ),
            ) {
              if (isFixing) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
              }
              Icon(
                imageVector = FutonIcons.Security,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = stringResource(R.string.selinux_fix_button),
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
        }
      }

      FutonRadioButton(
        selected = isSelected,
        onClick = { if (isAvailable) onSelect() },
        enabled = isAvailable,
      )
    }
  }
}

@Composable
private fun SELinuxFixDialog(
  selinuxMode: String,
  isFixing: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = { if (!isFixing) onDismiss() },
    title = {
      Text(
        text = stringResource(R.string.selinux_fix_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Security,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = FutonTheme.colors.textMuted,
          )
          Text(
            text = stringResource(R.string.selinux_current_mode, selinuxMode),
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        Text(
          text = stringResource(R.string.selinux_fix_explanation),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textMuted,
        )

        Surface(
          color = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
          shape = MaterialTheme.shapes.small,
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = FutonIcons.Warning,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
              tint = FutonTheme.colors.statusWarning,
            )
            Text(
              text = stringResource(R.string.selinux_fix_warning),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.statusWarning,
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onConfirm()
          onDismiss()
        },
        enabled = !isFixing,
      ) {
        if (isFixing) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(stringResource(R.string.selinux_fix_confirm))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isFixing,
      ) {
        Text(stringResource(R.string.common_cancel))
      }
    },
  )
}

@Composable
private fun SELinuxResultDialog(
  result: SELinuxFixResultUi,
  onDismiss: () -> Unit,
) {
  val isSuccess = result is SELinuxFixResultUi.Success

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = if (isSuccess) FutonIcons.CheckCircle else FutonIcons.Warning,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = if (isSuccess) FutonTheme.colors.statusPositive else FutonTheme.colors.statusDanger,
      )
    },
    title = {
      Text(
        text = stringResource(
          if (isSuccess) R.string.selinux_fix_success_title
          else R.string.selinux_fix_failed_title,
        ),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = stringResource(
            if (isSuccess) R.string.selinux_fix_success_message
            else R.string.selinux_fix_failed_message,
          ),
          style = MaterialTheme.typography.bodyMedium,
        )

        if (result is SELinuxFixResultUi.Failed) {
          Text(
            text = result.message,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.statusDanger,
          )
          result.suggestion?.let { suggestion ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = suggestion,
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = onDismiss) {
        Text(stringResource(R.string.common_ok))
      }
    },
  )
}

@Composable
private fun DaemonStatusItem(
  daemonState: DaemonState,
  isDaemonConnected: Boolean,
) {
  val statusText = when (daemonState) {
    is DaemonState.Ready -> stringResource(R.string.capability_daemon_binder_ipc)
    is DaemonState.Connecting -> stringResource(R.string.capability_daemon_connecting)
    is DaemonState.Authenticating -> stringResource(R.string.capability_daemon_authenticating)
    is DaemonState.Reconciling -> stringResource(R.string.capability_daemon_reconciling)
    is DaemonState.Starting -> stringResource(R.string.capability_daemon_starting)
    is DaemonState.Error -> formatDaemonError(daemonState)
    is DaemonState.Stopped -> stringResource(R.string.capability_daemon_not_running)
  }

  val statusColor = when {
    isDaemonConnected -> FutonTheme.colors.statusPositive
    daemonState is DaemonState.Error -> FutonTheme.colors.statusDanger
    daemonState is DaemonState.Stopped -> FutonTheme.colors.statusDanger
    else -> FutonTheme.colors.statusWarning
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = FutonIcons.Link,
      contentDescription = null,
      tint = FutonTheme.colors.interactiveNormal,
      modifier = Modifier.size(FutonSizes.IconSize),
    )
    Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.capability_daemon_connection),
        style = MaterialTheme.typography.bodyMedium,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          color = statusColor.copy(alpha = 0.2f),
          shape = MaterialTheme.shapes.small,
        ) {
          Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
      if (daemonState is DaemonState.Error && isAuthenticationError(daemonState)) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = getAuthErrorSuggestion(daemonState),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

/**
 * Formats daemon error message for display.
 * Extracts specific error details from authentication failures.
 */
@Composable
private fun formatDaemonError(errorState: DaemonState.Error): String {
  return when {
    isAuthenticationError(errorState) -> stringResource(
      R.string.capability_auth_failed,
      errorState.message,
    )

    else -> stringResource(R.string.capability_daemon_error, errorState.message)
  }
}

/**
 * Checks if the error is an authentication-related error.
 */
private fun isAuthenticationError(errorState: DaemonState.Error): Boolean {
  return errorState.code.code in 200..299
}

/**
 * Returns a suggestion for resolving authentication errors.
 */
@Composable
private fun getAuthErrorSuggestion(errorState: DaemonState.Error): String {
  return when (errorState.code) {
    ErrorCode.AUTH_FAILED,
    ErrorCode.AUTH_CHALLENGE_FAILED,
      ->
      "Try restarting the daemon or reinstalling the app"

    ErrorCode.AUTH_SIGNATURE_INVALID,
    ErrorCode.AUTH_KEY_NOT_FOUND,
      ->
      "App signature mismatch - reinstall may be required"

    ErrorCode.AUTH_SESSION_EXPIRED ->
      "Session expired - reconnecting automatically"

    ErrorCode.AUTH_SESSION_CONFLICT ->
      "Another instance may be connected"

    ErrorCode.AUTH_ATTESTATION_FAILED,
    ErrorCode.AUTH_ATTESTATION_MISMATCH,
      ->
      "Device integrity check failed"

    ErrorCode.AUTH_KEY_CORRUPTED ->
      "Regenerating authentication key..."

    else -> "Check daemon logs for details"
  }
}

@Composable
private fun InputDeviceSelectionSection(
  selectedPath: String,
  devices: List<InputDeviceEntry>,
  isLoading: Boolean,
  onDeviceSelected: (String) -> Unit,
  onRefresh: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 4.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.dsp_input_device),
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textMuted,
      )
      IconButton(onClick = onRefresh, enabled = !isLoading) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Icon(
            imageVector = FutonIcons.Refresh,
            contentDescription = stringResource(R.string.dsp_input_device_refresh),
            tint = FutonTheme.colors.interactiveNormal,
          )
        }
      }
    }

    val sortedDevices = remember(devices) {
      devices.sortedByDescending { it.touchscreenProbability }
    }

    SettingsGroup {
      item {
        SettingsRadioItem(
          title = stringResource(R.string.dsp_input_device_auto),
          description = stringResource(R.string.dsp_input_device_auto_description),
          selected = selectedPath.isEmpty(),
          onClick = { onDeviceSelected("") },
          leadingIcon = FutonIcons.Automation,
          recommended = true,
        )
      }

      if (isLoading && sortedDevices.isEmpty()) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(modifier = Modifier.size(24.dp))
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = stringResource(R.string.dsp_input_device_loading),
                style = MaterialTheme.typography.bodySmall,
                color = FutonTheme.colors.textMuted,
              )
            }
          }
        }
      } else if (sortedDevices.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.dsp_input_device_none),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
            modifier = Modifier.padding(16.dp),
          )
        }
      } else {
        sortedDevices.forEach { device ->
          item {
            InputDeviceItem(
              device = device,
              isSelected = selectedPath == device.path,
              onSelect = { onDeviceSelected(device.path) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InputDeviceItem(
  device: InputDeviceEntry,
  isSelected: Boolean,
  onSelect: () -> Unit,
) {
  val protocolName = when (device.mtProtocol) {
    MTProtocol.PROTOCOL_A -> stringResource(R.string.mt_protocol_a)
    MTProtocol.PROTOCOL_B -> stringResource(R.string.mt_protocol_b)
    MTProtocol.SINGLE_TOUCH -> stringResource(R.string.mt_single_touch)
  }

  val description = buildString {
    append(stringResource(R.string.dsp_input_device_probability, device.touchscreenProbability))
    if (device.maxX > 0 && device.maxY > 0) {
      append(" · ")
      append(stringResource(R.string.dsp_input_device_resolution, device.resolutionString))
    }
    if (device.maxTouchPoints > 0) {
      append(" · ")
      append(stringResource(R.string.dsp_input_device_touch_points, device.maxTouchPoints))
    }
  }

  SettingsRadioItem(
    title = device.shortDisplayName,
    description = description,
    selected = isSelected,
    onClick = onSelect,
    leadingIcon = FutonIcons.Touch,
    recommended = device.isRecommended,
  )
}
