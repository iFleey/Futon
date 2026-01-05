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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Compact download source selector using dropdown menu.
 * */
@Composable
fun IntegratedSourceSelector(
  selectedSource: DownloadSource,
  onSourceSelected: (DownloadSource) -> Unit,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  val (icon, labelRes) = when (selectedSource) {
    DownloadSource.HUGGING_FACE -> Icons.Default.Public to R.string.local_model_source_huggingface
    DownloadSource.HF_MIRROR -> Icons.Default.Cloud to R.string.local_model_source_hf_mirror
  }

  Surface(
    onClick = { if (enabled) expanded = true },
    enabled = enabled,
    color = FutonTheme.colors.backgroundTertiary,
    shape = RoundedCornerShape(8.dp),
    modifier = modifier.height(32.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = if (enabled) FutonTheme.colors.textNormal else FutonTheme.colors.textMuted,
      )
      Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) FutonTheme.colors.textNormal else FutonTheme.colors.textMuted,
      )
      // Show badge for HF-Mirror
      if (selectedSource == DownloadSource.HF_MIRROR) {
        Surface(
          color = FutonTheme.colors.statusPositive.copy(alpha = 0.15f),
          shape = RoundedCornerShape(4.dp),
        ) {
          Text(
            text = "CN",
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.statusPositive,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }
      }
      Icon(
        imageVector = FutonIcons.ExpandMore,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = FutonTheme.colors.textMuted,
      )
    }
  }

  DropdownMenu(
    expanded = expanded,
    onDismissRequest = { expanded = false },
  ) {
    DropdownMenuItem(
      text = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(stringResource(R.string.local_model_source_huggingface))
        }
      },
      onClick = {
        onSourceSelected(DownloadSource.HUGGING_FACE)
        expanded = false
      },
      leadingIcon = if (selectedSource == DownloadSource.HUGGING_FACE) {
        {
          Icon(
            imageVector = FutonIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      } else null,
    )
    DropdownMenuItem(
      text = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(stringResource(R.string.local_model_source_hf_mirror))
          Spacer(modifier = Modifier.width(4.dp))
          Surface(
            color = FutonTheme.colors.statusPositive.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
          ) {
            Text(
              text = stringResource(R.string.local_model_china_mirror),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusPositive,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }
      },
      onClick = {
        onSourceSelected(DownloadSource.HF_MIRROR)
        expanded = false
      },
      leadingIcon = if (selectedSource == DownloadSource.HF_MIRROR) {
        {
          Icon(
            imageVector = FutonIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      } else null,
    )
  }
}
