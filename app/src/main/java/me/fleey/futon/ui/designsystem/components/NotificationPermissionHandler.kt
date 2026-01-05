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
package me.fleey.futon.ui.designsystem.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import me.fleey.futon.R

sealed interface NotificationPermissionState {
  data object Granted : NotificationPermissionState
  data object Denied : NotificationPermissionState
  data object NotRequired : NotificationPermissionState
  data object Unknown : NotificationPermissionState
}

fun checkNotificationPermission(context: Context): NotificationPermissionState {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    when (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
      PackageManager.PERMISSION_GRANTED -> NotificationPermissionState.Granted
      else -> NotificationPermissionState.Denied
    }
  } else {
    NotificationPermissionState.NotRequired
  }
}

fun isNotificationPermissionRequired(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

fun openNotificationSettings(context: Context) {
  val intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
  context.startActivity(intent)
}

@Composable
fun NotificationPermissionHandler(
  onPermissionResult: (NotificationPermissionState) -> Unit = {},
  requestOnLaunch: Boolean = false,
  content: @Composable (
    permissionState: NotificationPermissionState,
    requestPermission: () -> Unit,
    openSettings: () -> Unit,
  ) -> Unit,
) {
  val context = LocalContext.current
  var permissionState by remember { mutableStateOf(checkNotificationPermission(context)) }
  var showRationaleDialog by remember { mutableStateOf(false) }
  var hasRequestedOnce by remember { mutableStateOf(false) }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { isGranted ->
    permissionState = if (isGranted) {
      NotificationPermissionState.Granted
    } else {
      NotificationPermissionState.Denied
    }
    onPermissionResult(permissionState)
  }

  val requestPermission: () -> Unit = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  val openSettings: () -> Unit = {
    openNotificationSettings(context)
  }

  LaunchedEffect(Unit) {
    if (requestOnLaunch && !hasRequestedOnce && permissionState == NotificationPermissionState.Denied) {
      hasRequestedOnce = true
      showRationaleDialog = true
    }
  }

  if (showRationaleDialog) {
    NotificationPermissionRationaleDialog(
      onDismiss = { showRationaleDialog = false },
      onRequestPermission = {
        showRationaleDialog = false
        requestPermission()
      },
      onOpenSettings = {
        showRationaleDialog = false
        openSettings()
      },
    )
  }

  content(permissionState, requestPermission, openSettings)
}

@Composable
fun NotificationPermissionRationaleDialog(
  onDismiss: () -> Unit,
  onRequestPermission: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.notification_permission_title)) },
    text = { Text(stringResource(R.string.notification_permission_rationale)) },
    confirmButton = {
      TextButton(onClick = onRequestPermission) {
        Text(stringResource(R.string.notification_permission_grant))
      }
    },
    dismissButton = {
      TextButton(onClick = onOpenSettings) {
        Text(stringResource(R.string.notification_permission_settings))
      }
    },
  )
}

@Composable
fun NotificationPermissionWarningDialog(
  onDismiss: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.notification_permission_required_title)) },
    text = { Text(stringResource(R.string.notification_permission_required_message)) },
    confirmButton = {
      TextButton(onClick = onOpenSettings) {
        Text(stringResource(R.string.notification_permission_settings))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
  val context = LocalContext.current
  return remember { checkNotificationPermission(context) }
}

