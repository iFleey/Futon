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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.platform.capability.models.SELinuxMode
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.MTProtocol
import me.fleey.futon.platform.root.RootType
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.settings.automation.ExecutionSettingsUiEvent
import me.fleey.futon.ui.feature.settings.automation.ExecutionSettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun CapabilityStatusScreen(
  onBack: () -> Unit,
  viewModel: ExecutionSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val pullToRefreshState = rememberPullToRefreshState()

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_capability_status),
        onBackClick = onBack,
      )
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    PullToRefreshBox(
      isRefreshing = uiState.isRefreshing,
      onRefresh = { viewModel.onEvent(ExecutionSettingsUiEvent.RefreshCapabilities) },
      state = pullToRefreshState,
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
      ) {
        Spacer(modifier = Modifier.height(8.dp))

        SettingsGroup(title = stringResource(R.string.capability_active_methods)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Automation,
              title = stringResource(R.string.capability_active_input),
              value = uiState.activeInputMethod?.let { getInputMethodName(it) }
                ?: stringResource(R.string.capability_none),
              status = if (uiState.activeInputMethod != null) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Screenshot,
              title = stringResource(R.string.capability_active_capture),
              value = uiState.activeCaptureMethod?.let { getCaptureMethodName(it) }
                ?: stringResource(R.string.capability_none),
              status = if (uiState.activeCaptureMethod != null) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val rootStatus = uiState.deviceCapabilities?.rootStatus
        SettingsGroup(title = stringResource(R.string.capability_root_status)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Root,
              title = stringResource(R.string.capability_root_available),
              value = if (rootStatus?.isAvailable == true)
                stringResource(R.string.capability_yes)
              else
                stringResource(R.string.capability_no),
              status = if (rootStatus?.isAvailable == true) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          if (rootStatus?.isAvailable == true) {
            item {
              CapabilityStatusItem(
                icon = FutonIcons.Info,
                title = stringResource(R.string.capability_root_type),
                value = getRootTypeName(rootStatus.rootType),
                status = CapabilityStatus.INFO,
              )
            }
            rootStatus.version?.let { version ->
              item {
                CapabilityStatusItem(
                  icon = FutonIcons.Info,
                  title = stringResource(R.string.capability_root_version),
                  value = version,
                  status = CapabilityStatus.INFO,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val seLinuxStatus = uiState.deviceCapabilities?.seLinuxStatus
        SettingsGroup(title = stringResource(R.string.capability_selinux_status)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Root,
              title = stringResource(R.string.capability_selinux_mode),
              value = getSELinuxModeName(seLinuxStatus?.mode ?: SELinuxMode.UNKNOWN),
              status = when (seLinuxStatus?.mode) {
                SELinuxMode.PERMISSIVE, SELinuxMode.DISABLED -> CapabilityStatus.AVAILABLE
                SELinuxMode.ENFORCING -> if (seLinuxStatus.inputAccessAllowed) CapabilityStatus.AVAILABLE else CapabilityStatus.WARNING
                else -> CapabilityStatus.UNAVAILABLE
              },
            )
          }
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Automation,
              title = stringResource(R.string.capability_input_access),
              value = if (seLinuxStatus?.inputAccessAllowed == true)
                stringResource(R.string.capability_allowed)
              else
                stringResource(R.string.capability_blocked),
              status = if (seLinuxStatus?.inputAccessAllowed == true) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          seLinuxStatus?.suggestedPolicy?.let { policy ->
            item {
              Column(
                modifier = Modifier.padding(
                  horizontal = FutonSizes.ListItemHorizontalPadding,
                  vertical = 8.dp,
                ),
              ) {
                Text(
                  text = stringResource(R.string.capability_suggested_policy),
                  style = MaterialTheme.typography.labelSmall,
                  color = FutonTheme.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                  modifier = Modifier.fillMaxWidth(),
                  color = FutonTheme.colors.backgroundTertiary,
                  shape = MaterialTheme.shapes.small,
                ) {
                  Text(
                    text = policy,
                    style = MaterialTheme.typography.bodySmall,
                    color = FutonTheme.colors.textNormal,
                    modifier = Modifier.padding(12.dp),
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val inputAccess = uiState.deviceCapabilities?.inputDeviceAccess
        val inputCaps = uiState.inputCapabilities
        SettingsGroup(title = stringResource(R.string.capability_input_device)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Automation,
              title = stringResource(R.string.capability_dev_input_access),
              value = if (inputAccess?.canAccessDevInput == true)
                stringResource(R.string.capability_accessible)
              else
                inputAccess?.error ?: stringResource(R.string.capability_not_accessible),
              status = if (inputAccess?.canAccessDevInput == true) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          inputCaps.detectedDevicePath?.let { path ->
            item {
              CapabilityStatusItem(
                icon = FutonIcons.Info,
                title = stringResource(R.string.capability_device_path),
                value = path,
                status = CapabilityStatus.INFO,
              )
            }
          }
          inputCaps.mtProtocol?.let { protocol ->
            item {
              CapabilityStatusItem(
                icon = FutonIcons.Info,
                title = stringResource(R.string.capability_mt_protocol),
                value = getMTProtocolName(protocol),
                status = CapabilityStatus.INFO,
              )
            }
          }
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Info,
              title = stringResource(R.string.capability_max_touch_points),
              value = inputCaps.maxTouchPoints.toString(),
              status = CapabilityStatus.INFO,
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsGroup(title = stringResource(R.string.capability_input_methods)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Speed,
              title = stringResource(R.string.settings_input_native),
              value = if (inputCaps.nativeAvailable)
                stringResource(R.string.capability_available)
              else
                inputCaps.nativeError ?: stringResource(R.string.capability_unavailable),
              status = if (inputCaps.nativeAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Root,
              title = stringResource(R.string.settings_input_android),
              value = if (inputCaps.androidInputAvailable)
                stringResource(R.string.capability_available)
              else
                inputCaps.androidInputError ?: stringResource(R.string.capability_unavailable),
              status = if (inputCaps.androidInputAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Root,
              title = stringResource(R.string.settings_input_shell),
              value = if (inputCaps.shellAvailable)
                stringResource(R.string.capability_available)
              else
                inputCaps.shellError ?: stringResource(R.string.capability_unavailable),
              status = if (inputCaps.shellAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val captureCaps = uiState.captureCapabilities
        SettingsGroup(title = stringResource(R.string.capability_capture_methods)) {
          item {
            CapabilityStatusItem(
              icon = FutonIcons.Root,
              title = stringResource(R.string.settings_capture_root),
              value = if (captureCaps?.rootAvailable == true)
                stringResource(R.string.capability_available)
              else
                captureCaps?.rootError ?: stringResource(R.string.capability_unavailable),
              status = if (captureCaps?.rootAvailable == true) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            )
          }
        }

        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }
}

private enum class CapabilityStatus {
  AVAILABLE,
  UNAVAILABLE,
  WARNING,
  INFO
}

@Composable
private fun CapabilityStatusItem(
  icon: ImageVector,
  title: String,
  value: String,
  status: CapabilityStatus,
) {
  val statusColor = when (status) {
    CapabilityStatus.AVAILABLE -> FutonTheme.colors.statusPositive
    CapabilityStatus.UNAVAILABLE -> FutonTheme.colors.statusDanger
    CapabilityStatus.WARNING -> FutonTheme.colors.statusWarning
    CapabilityStatus.INFO -> FutonTheme.colors.textMuted
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
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(FutonSizes.IconSize),
      tint = FutonTheme.colors.interactiveNormal,
    )

    Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textNormal,
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      if (status != CapabilityStatus.INFO) {
        Surface(
          modifier = Modifier.size(8.dp),
          shape = MaterialTheme.shapes.small,
          color = statusColor,
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
      }
      Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
      )
    }
  }
}

@Composable
private fun getInputMethodName(method: InputMethod): String = when (method) {
  InputMethod.NATIVE_IOCTL -> stringResource(R.string.settings_input_native)
  InputMethod.ANDROID_INPUT -> stringResource(R.string.settings_input_android)
  InputMethod.SHELL_SENDEVENT -> stringResource(R.string.settings_input_shell)
}

@Composable
private fun getCaptureMethodName(method: CaptureMethod): String = when (method) {
  CaptureMethod.ROOT_SCREENCAP -> stringResource(R.string.settings_capture_root)
  CaptureMethod.MEDIA_PROJECTION -> stringResource(R.string.settings_capture_media_projection)
}

@Composable
private fun getRootTypeName(type: RootType): String = when (type) {
  RootType.KSU -> stringResource(R.string.root_type_ksu)
  RootType.KSU_NEXT -> stringResource(R.string.root_type_ksu_next)
  RootType.SUKISU_ULTRA -> stringResource(R.string.root_type_sukisu_ultra)
  RootType.MAGISK -> stringResource(R.string.root_type_magisk)
  RootType.SUPERSU -> stringResource(R.string.root_type_supersu)
  RootType.APATCH -> stringResource(R.string.root_type_apatch)
  RootType.OTHER -> stringResource(R.string.root_type_other)
  RootType.NONE -> stringResource(R.string.root_type_none)
}

@Composable
private fun getSELinuxModeName(mode: SELinuxMode): String = when (mode) {
  SELinuxMode.ENFORCING -> stringResource(R.string.selinux_enforcing)
  SELinuxMode.PERMISSIVE -> stringResource(R.string.selinux_permissive)
  SELinuxMode.DISABLED -> stringResource(R.string.selinux_disabled)
  SELinuxMode.UNKNOWN -> stringResource(R.string.selinux_unknown)
}

@Composable
private fun getMTProtocolName(protocol: MTProtocol): String = when (protocol) {
  MTProtocol.PROTOCOL_A -> stringResource(R.string.mt_protocol_a)
  MTProtocol.PROTOCOL_B -> stringResource(R.string.mt_protocol_b)
  MTProtocol.SINGLE_TOUCH -> stringResource(R.string.mt_single_touch)
}
