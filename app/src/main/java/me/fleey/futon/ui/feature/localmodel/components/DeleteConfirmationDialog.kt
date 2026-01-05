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
package me.fleey.futon.ui.feature.localmodel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun DeleteConfirmationDialog(
  modelName: String,
  modelSize: String,
  isActive: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = FutonIcons.Delete,
        contentDescription = null,
        tint = FutonTheme.colors.statusDanger,
      )
    },
    title = {
      Text(
        text = stringResource(R.string.local_model_delete_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column {
        Text(
          text = stringResource(R.string.local_model_delete_confirm, modelName),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
        )

        if (modelSize.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.local_model_delete_storage_info, modelSize),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }

        if (isActive) {
          Spacer(modifier = Modifier.height(12.dp))
          ActiveModelWarning()
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.local_model_delete_irreversible),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.statusDanger,
        )
      }
    },
    confirmButton = {
      FutonButton(
        text = stringResource(R.string.action_delete),
        onClick = onConfirm,
        style = ButtonStyle.Danger,
      )
    },
    dismissButton = {
      FutonButton(
        text = stringResource(R.string.action_cancel),
        onClick = onDismiss,
        style = ButtonStyle.Secondary,
      )
    },
    containerColor = MaterialTheme.colorScheme.surface,
  )
}

@Composable
private fun ActiveModelWarning() {
  Surface(
    color = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
    shape = MaterialTheme.shapes.small,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = FutonTheme.colors.statusWarning,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = stringResource(R.string.local_model_delete_active_warning),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.statusWarning,
      )
    }
  }
}
