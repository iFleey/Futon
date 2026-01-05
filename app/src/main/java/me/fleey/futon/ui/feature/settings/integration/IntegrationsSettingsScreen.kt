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
package me.fleey.futon.ui.feature.settings.integration

import android.Manifest
import android.content.ClipData
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.service.gateway.GatewayConfigData
import me.fleey.futon.service.gateway.models.BindingState
import me.fleey.futon.service.gateway.models.DiscoveryState
import me.fleey.futon.service.gateway.models.NetworkState
import me.fleey.futon.service.gateway.models.ServerState
import me.fleey.futon.service.gateway.models.TlsState
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSliderItem
import me.fleey.futon.ui.designsystem.component.settings.SettingsSwitchItem
import me.fleey.futon.ui.designsystem.components.NotificationPermissionState
import me.fleey.futon.ui.designsystem.components.NotificationPermissionWarningDialog
import me.fleey.futon.ui.designsystem.components.checkNotificationPermission
import me.fleey.futon.ui.designsystem.components.isNotificationPermissionRequired
import me.fleey.futon.ui.designsystem.components.openNotificationSettings
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel
import kotlin.time.Duration

@Composable
fun IntegrationsSettingsScreen(
  onBack: () -> Unit,
  viewModel: IntegrationsSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(uiState.isTokenCopied) {
    if (uiState.isTokenCopied) {
      snackbarHostState.showSnackbar(context.getString(R.string.integration_token_regenerated))
      viewModel.onEvent(IntegrationsUiEvent.DismissTokenCopied)
    }
  }

  LaunchedEffect(uiState.showCertImportNotAvailable) {
    if (uiState.showCertImportNotAvailable) {
      snackbarHostState.showSnackbar(context.getString(R.string.integration_import_certificate_not_available))
      viewModel.onEvent(IntegrationsUiEvent.DismissCertImportNotAvailable)
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_integrations),
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

      TaskerSection(
        taskerToken = uiState.taskerToken,
        onRegenerateToken = { viewModel.onEvent(IntegrationsUiEvent.RegenerateTaskerToken) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      LanServerSection(
        uiState = uiState,
        onServerEnabledChange = { viewModel.onEvent(IntegrationsUiEvent.SetServerEnabled(it)) },
        onPortChange = { viewModel.onEvent(IntegrationsUiEvent.SetServerPort(it)) },
        onRegenerateLanToken = { viewModel.onEvent(IntegrationsUiEvent.RegenerateLanToken) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      TrustedNetworksSection(
        trustedNetworks = uiState.trustedNetworks,
        currentSsid = uiState.currentSsid,
        isCurrentNetworkTrusted = uiState.isCurrentNetworkTrusted,
        availableWifiNetworks = uiState.availableWifiNetworks,
        isScanning = uiState.isScanning,
        onAddCurrentNetwork = { viewModel.onEvent(IntegrationsUiEvent.AddCurrentNetworkToTrusted) },
        onAddNetwork = { viewModel.onEvent(IntegrationsUiEvent.AddNetworkToTrusted(it)) },
        onRemoveNetwork = { viewModel.onEvent(IntegrationsUiEvent.RemoveTrustedNetwork(it)) },
        onRefreshNetwork = { viewModel.onEvent(IntegrationsUiEvent.RefreshNetworkState) },
        onScanNetworks = { viewModel.onEvent(IntegrationsUiEvent.ScanWifiNetworks) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      IdleTimeoutSection(
        idleTimeoutHours = uiState.config.idleTimeoutHours,
        idleTimeRemaining = uiState.idleTimeRemaining,
        onTimeoutChange = { viewModel.onEvent(IntegrationsUiEvent.SetIdleTimeoutHours(it)) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      ServiceDiscoverySection(
        enableMdns = uiState.config.enableMdns,
        discoveryState = uiState.discoveryState,
        onEnableMdnsChange = { viewModel.onEvent(IntegrationsUiEvent.SetEnableMdns(it)) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      TlsSection(
        enableTls = uiState.config.enableTls,
        tlsState = uiState.tlsState,
        onEnableTlsChange = { viewModel.onEvent(IntegrationsUiEvent.SetEnableTls(it)) },
        onImportCertificate = { viewModel.onEvent(IntegrationsUiEvent.ImportTlsCertificate) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      RateLimitSection(
        rateLimitQps = uiState.config.rateLimitQps,
        onRateLimitChange = { viewModel.onEvent(IntegrationsUiEvent.SetRateLimitQps(it)) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      TokenRotationSection(
        autoRotateToken = uiState.config.autoRotateTokenOnIpChange,
        tokenRotationIntervalDays = uiState.config.tokenRotationIntervalDays,
        onAutoRotateChange = { viewModel.onEvent(IntegrationsUiEvent.SetAutoRotateToken(it)) },
        onIntervalChange = { viewModel.onEvent(IntegrationsUiEvent.SetTokenRotationIntervalDays(it)) },
      )

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun TaskerSection(
  taskerToken: String,
  onRegenerateToken: () -> Unit,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  SettingsGroup(title = stringResource(R.string.integration_tasker)) {
    item {
      SettingsItem(
        title = stringResource(R.string.integration_tasker_description),
        leadingIcon = FutonIcons.Info,
      )
    }
    item {
      TokenDisplay(
        label = stringResource(R.string.integration_tasker_token),
        token = taskerToken,
        onCopy = {
          scope.launch {
            clipboard.setClipEntry(
              ClipEntry(
                ClipData.newPlainText(
                  "token",
                  taskerToken,
                ),
              ),
            )
          }
        },
        onRegenerate = onRegenerateToken,
      )
    }
  }
}

@Composable
private fun LanServerSection(
  uiState: IntegrationsUiState,
  onServerEnabledChange: (Boolean) -> Unit,
  onPortChange: (Int) -> Unit,
  onRegenerateLanToken: () -> Unit,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var showPortDialog by remember { mutableStateOf(false) }
  var showNotificationPermissionWarning by remember { mutableStateOf(false) }
  var notificationPermissionState by remember { mutableStateOf(checkNotificationPermission(context)) }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    notificationPermissionState = if (granted) {
      NotificationPermissionState.Granted
    } else {
      NotificationPermissionState.Denied
    }
    if (granted) {
      onServerEnabledChange(true)
    }
  }

  val handleServerEnabledChange: (Boolean) -> Unit = { enabled ->
    if (enabled && isNotificationPermissionRequired()) {
      notificationPermissionState = checkNotificationPermission(context)
      when (notificationPermissionState) {
        NotificationPermissionState.Granted,
        NotificationPermissionState.NotRequired,
          -> {
          onServerEnabledChange(true)
        }

        NotificationPermissionState.Denied -> {
          showNotificationPermissionWarning = true
        }

        NotificationPermissionState.Unknown -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          } else {
            onServerEnabledChange(true)
          }
        }
      }
    } else {
      onServerEnabledChange(enabled)
    }
  }

  if (showNotificationPermissionWarning) {
    NotificationPermissionWarningDialog(
      onDismiss = { showNotificationPermissionWarning = false },
      onOpenSettings = {
        showNotificationPermissionWarning = false
        openNotificationSettings(context)
      },
    )
  }

  SettingsGroup(title = stringResource(R.string.integration_lan_server)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.integration_server_enabled),
        description = stringResource(R.string.integration_server_enabled_description),
        checked = uiState.config.serverEnabled,
        onCheckedChange = handleServerEnabledChange,
        leadingIcon = FutonIcons.Server,
      )
    }

    if (uiState.config.serverEnabled &&
      isNotificationPermissionRequired() &&
      notificationPermissionState == NotificationPermissionState.Denied
    ) {
      item {
        SettingsItem(
          title = stringResource(R.string.notification_permission_warning),
          description = stringResource(R.string.notification_permission_required_message),
          leadingIcon = FutonIcons.Warning,
          onClick = { openNotificationSettings(context) },
          trailing = {
            TextButton(onClick = { openNotificationSettings(context) }) {
              Text(stringResource(R.string.notification_permission_settings))
            }
          },
        )
      }
    }

    if (uiState.config.serverEnabled) {
      item {
        ServerStatusItem(
          serverState = uiState.serverState,
          bindingState = uiState.bindingState,
          networkState = uiState.networkState,
        )
      }

      item {
        SettingsItem(
          title = stringResource(R.string.integration_server_port),
          description = stringResource(R.string.integration_server_port_description),
          leadingIcon = FutonIcons.Link,
          onClick = { showPortDialog = true },
          trailing = {
            Text(
              text = uiState.config.serverPort.toString(),
              style = MaterialTheme.typography.bodyMedium,
              color = FutonTheme.colors.textMuted,
            )
          },
        )
      }

      item {
        TokenDisplay(
          label = stringResource(R.string.integration_lan_token),
          token = uiState.lanToken,
          onCopy = {
            scope.launch {
              clipboard.setClipEntry(
                ClipEntry(
                  ClipData.newPlainText(
                    "token",
                    uiState.lanToken,
                  ),
                ),
              )
            }
          },
          onRegenerate = onRegenerateLanToken,
        )
      }
    }
  }

  if (showPortDialog) {
    PortInputDialog(
      currentPort = uiState.config.serverPort,
      onDismiss = { showPortDialog = false },
      onConfirm = { port ->
        onPortChange(port)
        showPortDialog = false
      },
    )
  }
}

@Composable
private fun ServerStatusItem(
  serverState: ServerState,
  bindingState: BindingState,
  networkState: NetworkState,
) {
  val (statusText, statusColor) = when (serverState) {
    is ServerState.Running -> Pair(
      stringResource(R.string.integration_server_running, serverState.ipAddress, serverState.port),
      FutonTheme.colors.statusPositive,
    )

    is ServerState.Stopped -> Pair(
      stringResource(R.string.integration_server_stopped),
      FutonTheme.colors.textMuted,
    )

    ServerState.Starting -> Pair(
      stringResource(R.string.integration_server_starting),
      FutonTheme.colors.statusWarning,
    )

    is ServerState.Error -> Pair(
      stringResource(R.string.integration_server_error),
      FutonTheme.colors.statusDanger,
    )
  }

  SettingsItem(
    title = stringResource(R.string.integration_server_status),
    description = statusText,
    leadingIcon = FutonIcons.Info,
    trailing = {
      Text(
        text = when (serverState) {
          is ServerState.Running -> stringResource(R.string.status_running)
          is ServerState.Stopped -> stringResource(R.string.integration_server_stopped)
          ServerState.Starting -> stringResource(R.string.daemon_status_starting)
          is ServerState.Error -> stringResource(R.string.daemon_status_failed)
        },
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
      )
    },
  )
}

@Composable
private fun TokenDisplay(
  label: String,
  token: String,
  onCopy: () -> Unit,
  onRegenerate: () -> Unit,
) {
  SettingsItem(
    title = label,
    description = if (token.length > 24) "${token.take(24)}..." else token,
    leadingIcon = FutonIcons.Key,
    trailing = {
      Row {
        IconButton(
          onClick = onCopy,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Copy,
            contentDescription = stringResource(R.string.action_copy),
            modifier = Modifier.size(18.dp),
            tint = FutonTheme.colors.textMuted,
          )
        }
        IconButton(
          onClick = onRegenerate,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Refresh,
            contentDescription = stringResource(R.string.integration_regenerate_token),
            modifier = Modifier.size(18.dp),
            tint = FutonTheme.colors.textMuted,
          )
        }
      }
    },
  )
}

@Composable
private fun TrustedNetworksSection(
  trustedNetworks: Set<String>,
  currentSsid: String?,
  isCurrentNetworkTrusted: Boolean,
  availableWifiNetworks: List<String>,
  isScanning: Boolean,
  onAddCurrentNetwork: () -> Unit,
  onAddNetwork: (String) -> Unit,
  onRemoveNetwork: (String) -> Unit,
  onRefreshNetwork: () -> Unit,
  onScanNetworks: () -> Unit,
) {
  val context = LocalContext.current

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      onRefreshNetwork()
      onScanNetworks()
    }
  }

  val untrustedNetworks = availableWifiNetworks.filter { it !in trustedNetworks }

  SettingsGroup(title = stringResource(R.string.integration_trusted_networks)) {
    item {
      SettingsItem(
        title = stringResource(R.string.integration_trusted_networks_explanation_detail),
        leadingIcon = FutonIcons.Security,
      )
    }

    if (trustedNetworks.isEmpty()) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_trust_all_wifi),
          description = stringResource(R.string.integration_trust_all_wifi_warning),
          leadingIcon = FutonIcons.Warning,
        )
      }
    }

    if (currentSsid != null && !isCurrentNetworkTrusted) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_add_current_network),
          description = stringResource(R.string.integration_add_network_hint, currentSsid),
          leadingIcon = FutonIcons.Add,
          onClick = onAddCurrentNetwork,
        )
      }
    }

    if (currentSsid != null) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_scan_wifi),
          description = if (isScanning) {
            stringResource(R.string.integration_scanning)
          } else {
            stringResource(R.string.integration_scan_wifi_description)
          },
          leadingIcon = FutonIcons.Search,
          onClick = { if (!isScanning) onScanNetworks() },
          trailing = {
            if (isScanning) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
              )
            }
          },
        )
      }
    }

    if (untrustedNetworks.isNotEmpty()) {
      item {
        Text(
          text = stringResource(R.string.integration_available_networks),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
      }
      untrustedNetworks.forEach { ssid ->
        item {
          SettingsItem(
            title = ssid,
            description = if (ssid == currentSsid) stringResource(R.string.integration_current_network_label) else null,
            leadingIcon = FutonIcons.Wifi,
            onClick = { onAddNetwork(ssid) },
            trailing = {
              IconButton(
                onClick = { onAddNetwork(ssid) },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(
                  imageVector = FutonIcons.Add,
                  contentDescription = stringResource(R.string.integration_add_network),
                  modifier = Modifier.size(18.dp),
                  tint = FutonTheme.colors.interactiveNormal,
                )
              }
            },
          )
        }
      }
    }

    if (currentSsid == null) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_location_service_required),
          description = stringResource(R.string.integration_location_service_description),
          leadingIcon = FutonIcons.Location,
          onClick = {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
          },
        )
      }
    }

    if (trustedNetworks.isNotEmpty()) {
      item {
        Text(
          text = stringResource(R.string.integration_trusted_networks_list),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
      }
    }

    trustedNetworks.forEach { ssid ->
      item {
        TrustedNetworkItem(
          ssid = ssid,
          isCurrent = ssid == currentSsid,
          onRemove = { onRemoveNetwork(ssid) },
        )
      }
    }
  }
}

@Composable
private fun TrustedNetworkItem(
  ssid: String,
  isCurrent: Boolean,
  onRemove: () -> Unit,
) {
  SettingsItem(
    title = ssid,
    description = if (isCurrent) stringResource(
      R.string.integration_current_network,
      ssid,
    ) else null,
    leadingIcon = FutonIcons.Link,
    trailing = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isCurrent) {
          Text(
            text = stringResource(R.string.status_connected),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.statusPositive,
            modifier = Modifier.padding(end = 8.dp),
          )
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Delete,
            contentDescription = stringResource(R.string.action_delete),
            modifier = Modifier.size(18.dp),
            tint = FutonTheme.colors.statusDanger,
          )
        }
      }
    },
  )
}

@Composable
private fun IdleTimeoutSection(
  idleTimeoutHours: Int,
  idleTimeRemaining: Duration?,
  onTimeoutChange: (Int) -> Unit,
) {
  val context = LocalContext.current
  val neverText = stringResource(R.string.integration_idle_timeout_never)

  SettingsGroup(title = stringResource(R.string.integration_idle_timeout)) {
    item {
      SettingsSliderItem(
        title = stringResource(R.string.integration_idle_timeout_title),
        description = if (idleTimeoutHours == 0) {
          stringResource(R.string.integration_idle_timeout_disabled)
        } else {
          stringResource(R.string.integration_idle_timeout_description)
        },
        value = idleTimeoutHours.toFloat(),
        onValueChange = { onTimeoutChange(it.toInt()) },
        valueRange = 0f..24f,
        steps = 23,
        leadingIcon = FutonIcons.Timer,
        valueFormatter = { hours ->
          if (hours.toInt() == 0) {
            neverText
          } else {
            context.getString(R.string.integration_idle_timeout_value, hours.toInt())
          }
        },
      )
    }

    if (idleTimeRemaining != null && idleTimeoutHours > 0) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_time_remaining),
          description = idleTimeRemaining.toString(),
          leadingIcon = FutonIcons.Timer,
        )
      }
    }
  }
}

@Composable
private fun ServiceDiscoverySection(
  enableMdns: Boolean,
  discoveryState: DiscoveryState,
  onEnableMdnsChange: (Boolean) -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.integration_service_discovery)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.integration_enable_mdns),
        description = stringResource(R.string.integration_mdns_description),
        checked = enableMdns,
        onCheckedChange = onEnableMdnsChange,
        leadingIcon = FutonIcons.Search,
      )
    }

    if (enableMdns && discoveryState is DiscoveryState.Registered) {
      item {
        SettingsItem(
          title = stringResource(R.string.integration_service_name),
          description = discoveryState.serviceName,
          leadingIcon = FutonIcons.Info,
        )
      }
    }
  }
}

@Composable
private fun TlsSection(
  enableTls: Boolean,
  tlsState: TlsState,
  onEnableTlsChange: (Boolean) -> Unit,
  onImportCertificate: () -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.integration_tls)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.integration_enable_tls),
        description = stringResource(R.string.integration_tls_description),
        checked = enableTls,
        onCheckedChange = onEnableTlsChange,
        leadingIcon = FutonIcons.Security,
      )
    }

    if (enableTls) {
      item {
        val (statusText, statusColor) = when (tlsState) {
          is TlsState.Enabled -> Pair(
            stringResource(R.string.integration_tls_enabled),
            FutonTheme.colors.statusPositive,
          )

          TlsState.Disabled -> Pair(
            stringResource(R.string.integration_tls_disabled),
            FutonTheme.colors.textMuted,
          )

          is TlsState.CertExpired -> Pair(
            stringResource(R.string.integration_tls_cert_expired),
            FutonTheme.colors.statusDanger,
          )

          is TlsState.CertInvalid -> Pair(
            stringResource(R.string.integration_tls_cert_invalid),
            FutonTheme.colors.statusDanger,
          )
        }

        SettingsItem(
          title = stringResource(R.string.integration_tls_status),
          description = statusText,
          leadingIcon = FutonIcons.Security,
          trailing = {
            TextButton(onClick = onImportCertificate) {
              Text(stringResource(R.string.integration_import_certificate))
            }
          },
        )
      }
    }
  }
}

@Composable
private fun RateLimitSection(
  rateLimitQps: Int,
  onRateLimitChange: (Int) -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.integration_rate_limit)) {
    item {
      SettingsSliderItem(
        title = stringResource(R.string.integration_rate_limit_qps),
        description = stringResource(R.string.integration_rate_limit_description),
        value = rateLimitQps.toFloat(),
        onValueChange = { onRateLimitChange(it.toInt()) },
        valueRange = GatewayConfigData.MIN_RATE_LIMIT.toFloat()..20f,
        steps = 18,
        leadingIcon = FutonIcons.Speed,
        valueFormatter = { "${it.toInt()} QPS" },
      )
    }
  }
}

@Composable
private fun TokenRotationSection(
  autoRotateToken: Boolean,
  tokenRotationIntervalDays: Int,
  onAutoRotateChange: (Boolean) -> Unit,
  onIntervalChange: (Int) -> Unit,
) {
  val context = LocalContext.current

  SettingsGroup(title = stringResource(R.string.integration_token_rotation)) {
    item {
      SettingsSwitchItem(
        title = stringResource(R.string.integration_auto_rotate_token),
        description = stringResource(R.string.integration_auto_rotate_description),
        checked = autoRotateToken,
        onCheckedChange = onAutoRotateChange,
        leadingIcon = FutonIcons.Refresh,
      )
    }

    if (autoRotateToken) {
      item {
        SettingsSliderItem(
          title = stringResource(R.string.integration_rotation_interval),
          description = stringResource(R.string.integration_rotation_interval_description),
          value = tokenRotationIntervalDays.toFloat(),
          onValueChange = { onIntervalChange(it.toInt()) },
          valueRange = GatewayConfigData.MIN_TOKEN_ROTATION_DAYS.toFloat()..GatewayConfigData.MAX_TOKEN_ROTATION_DAYS.toFloat(),
          steps = GatewayConfigData.MAX_TOKEN_ROTATION_DAYS - GatewayConfigData.MIN_TOKEN_ROTATION_DAYS - 1,
          leadingIcon = FutonIcons.Timer,
          valueFormatter = { days ->
            context.getString(R.string.integration_rotation_interval_value, days.toInt())
          },
        )
      }
    }
  }
}

@Composable
private fun PortInputDialog(
  currentPort: Int,
  onDismiss: () -> Unit,
  onConfirm: (Int) -> Unit,
) {
  var portText by remember { mutableStateOf(currentPort.toString()) }
  var isError by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.integration_server_port)) },
    text = {
      Column {
        OutlinedTextField(
          value = portText,
          onValueChange = { value ->
            portText = value.filter { it.isDigit() }
            val port = portText.toIntOrNull()
            isError =
              port == null || port !in GatewayConfigData.MIN_PORT..GatewayConfigData.MAX_PORT
          },
          label = { Text(stringResource(R.string.integration_port_label)) },
          supportingText = {
            if (isError) {
              Text(
                stringResource(
                  R.string.integration_port_error,
                  GatewayConfigData.MIN_PORT,
                  GatewayConfigData.MAX_PORT,
                ),
                color = MaterialTheme.colorScheme.error,
              )
            } else {
              Text(
                stringResource(
                  R.string.integration_port_hint,
                  GatewayConfigData.MIN_PORT,
                  GatewayConfigData.MAX_PORT,
                ),
              )
            }
          },
          isError = isError,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val port = portText.toIntOrNull()
          if (port != null && port in GatewayConfigData.MIN_PORT..GatewayConfigData.MAX_PORT) {
            onConfirm(port)
          }
        },
        enabled = !isError && portText.isNotEmpty(),
      ) {
        Text(stringResource(R.string.action_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
