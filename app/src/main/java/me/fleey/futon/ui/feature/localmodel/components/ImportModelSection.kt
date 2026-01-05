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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.localmodel.ImportUiState

@Composable
fun ImportModelSection(
  importProgress: ImportUiState?,
  onImportClick: () -> Unit,
  onCancelImport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsGroup(
    title = stringResource(R.string.local_model_import),
    modifier = modifier,
  ) {
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      ) {
        Text(
          text = stringResource(R.string.local_model_import_description),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (importProgress != null) {
          ImportProgressCard(
            progress = importProgress,
            onCancel = onCancelImport,
          )
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            FutonButton(
              text = stringResource(R.string.local_model_select_file),
              onClick = onImportClick,
              icon = FutonIcons.Add,
            )
          }
        }
      }
    }

    item {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        color = FutonTheme.colors.backgroundTertiary,
        shape = MaterialTheme.shapes.small,
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Icon(
            imageVector = FutonIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = FutonTheme.colors.textMuted,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = stringResource(R.string.local_model_supported_formats),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textNormal,
            )
            Text(
              text = stringResource(R.string.local_model_supported_formats_description),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ImportProgressCard(
  progress: ImportUiState,
  onCancel: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = FutonTheme.colors.backgroundTertiary,
    shape = MaterialTheme.shapes.small,
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (progress.isImporting) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
          } else if (progress.error != null) {
            Icon(
              imageVector = FutonIcons.Error,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = FutonTheme.colors.statusDanger,
            )
            Spacer(modifier = Modifier.width(8.dp))
          } else if (progress.requiresMmproj) {
            Icon(
              imageVector = FutonIcons.Warning,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = FutonTheme.colors.statusWarning,
            )
            Spacer(modifier = Modifier.width(8.dp))
          }

          Text(
            text = progress.stageRes?.let { stringResource(it) } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
              progress.error != null -> FutonTheme.colors.statusDanger
              progress.requiresMmproj -> FutonTheme.colors.statusWarning
              else -> FutonTheme.colors.textNormal
            },
          )
        }

        FutonButton(
          text = stringResource(R.string.action_cancel),
          onClick = onCancel,
          style = ButtonStyle.Secondary,
        )
      }

      if (progress.isImporting && progress.progress > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { progress.progress / 100f },
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.primary,
          trackColor = FutonTheme.colors.interactiveMuted,
        )
      }

      progress.error?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.statusDanger,
        )
      }

      progress.modelPath?.let { path ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = path.substringAfterLast("/"),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

@Composable
fun MmprojPickerDialog(
  onSelectFile: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        tint = FutonTheme.colors.statusWarning,
      )
    },
    title = {
      Text(
        text = stringResource(R.string.local_model_mmproj_required),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column {
        Text(
          text = stringResource(R.string.local_model_mmproj_description),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.local_model_mmproj_hint),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    },
    confirmButton = {
      FutonButton(
        text = stringResource(R.string.local_model_select_mmproj),
        onClick = onSelectFile,
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
