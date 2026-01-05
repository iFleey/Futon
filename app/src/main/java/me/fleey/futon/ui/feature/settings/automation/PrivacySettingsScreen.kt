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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.privacy.models.CaptureAuditEntry
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacySettingsScreen(
  onBack: () -> Unit,
  viewModel: ExecutionSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  var showClearDialog by remember { mutableStateOf(false) }

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

  if (showClearDialog) {
    AlertDialog(
      onDismissRequest = { showClearDialog = false },
      title = { Text(stringResource(R.string.privacy_clear_audit_log_title)) },
      text = { Text(stringResource(R.string.privacy_clear_audit_log_message)) },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.onEvent(ExecutionSettingsUiEvent.ClearAuditLog)
            showClearDialog = false
          },
        ) {
          Text(stringResource(R.string.action_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_privacy),
        onBackClick = onBack,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(8.dp))
      }

      item {
        SettingsGroup(title = stringResource(R.string.privacy_mode_title)) {
          item {
            SettingsRadioItem(
              title = stringResource(R.string.privacy_mode_strict),
              description = stringResource(R.string.privacy_mode_strict_description),
              selected = uiState.privacyMode == PrivacyMode.STRICT,
              onClick = { viewModel.onEvent(ExecutionSettingsUiEvent.PrivacyModeSelected(PrivacyMode.STRICT)) },
              leadingIcon = FutonIcons.VisibilityOff,
              recommended = true,
            )
          }
          item {
            SettingsRadioItem(
              title = stringResource(R.string.privacy_mode_consent),
              description = stringResource(R.string.privacy_mode_consent_description),
              selected = uiState.privacyMode == PrivacyMode.CONSENT,
              onClick = { viewModel.onEvent(ExecutionSettingsUiEvent.PrivacyModeSelected(PrivacyMode.CONSENT)) },
              leadingIcon = FutonIcons.Info,
            )
          }
          item {
            SettingsRadioItem(
              title = stringResource(R.string.privacy_mode_trusted),
              description = stringResource(R.string.privacy_mode_trusted_description),
              selected = uiState.privacyMode == PrivacyMode.TRUSTED,
              onClick = { viewModel.onEvent(ExecutionSettingsUiEvent.PrivacyModeSelected(PrivacyMode.TRUSTED)) },
              leadingIcon = FutonIcons.Warning,
            )
          }
        }
      }

      if (uiState.privacyMode == PrivacyMode.TRUSTED) {
        item {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.medium,
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                imageVector = FutonIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = FutonTheme.colors.statusWarning,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = stringResource(R.string.privacy_trusted_warning),
                style = MaterialTheme.typography.bodySmall,
                color = FutonTheme.colors.statusWarning,
              )
            }
          }
        }
      }

      // Audit Log Settings
      item {
        SettingsGroup(title = stringResource(R.string.privacy_audit_log_title)) {
          item {
            SettingsSwitchItem(
              title = stringResource(R.string.privacy_enable_audit_log),
              description = stringResource(R.string.privacy_enable_audit_log_description),
              checked = uiState.auditLogEnabled,
              onCheckedChange = { viewModel.onEvent(ExecutionSettingsUiEvent.AuditLogToggled(it)) },
              leadingIcon = FutonIcons.History,
            )
          }
        }
      }

      if (uiState.auditLogEnabled) {
        item {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.privacy_audit_log_entries, uiState.auditLog.size),
                style = MaterialTheme.typography.titleSmall,
                color = FutonTheme.colors.textMuted,
              )

              if (uiState.auditLog.isNotEmpty()) {
                FutonButton(
                  text = stringResource(R.string.action_clear),
                  onClick = { showClearDialog = true },
                  style = ButtonStyle.Secondary,
                  icon = FutonIcons.Clear,
                )
              }
            }

            if (uiState.auditLog.isEmpty()) {
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = FutonTheme.colors.backgroundSecondary,
                shape = MaterialTheme.shapes.medium,
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Icon(
                    imageVector = FutonIcons.EmptyHistory,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = FutonTheme.colors.textMuted,
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.privacy_no_audit_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FutonTheme.colors.textMuted,
                  )
                }
              }
            } else {
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = FutonTheme.colors.backgroundSecondary,
                shape = MaterialTheme.shapes.medium,
              ) {
                Column {
                  uiState.auditLog.take(20).forEachIndexed { index, entry ->
                    AuditLogEntryItem(entry = entry)
                    if (index < minOf(uiState.auditLog.size - 1, 19)) {
                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 16.dp)
                          .height(0.5.dp),
                        color = FutonTheme.colors.interactiveMuted,
                      ) {}
                    }
                  }
                }
              }
            }
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }
}

@Composable
private fun AuditLogEntryItem(entry: CaptureAuditEntry) {
  val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
  val formattedTime = remember(entry.timestamp) { dateFormat.format(Date(entry.timestamp)) }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = 12.dp,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (entry.wasAllowed) FutonIcons.Success else FutonIcons.Error,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = if (entry.wasAllowed) FutonTheme.colors.statusPositive else FutonTheme.colors.statusDanger,
    )

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = entry.packageName.substringAfterLast('.'),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = formattedTime,
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = entry.reason,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
