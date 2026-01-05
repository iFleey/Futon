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
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun QuantizationPickerDialog(
  model: ModelInfo,
  recommendedQuantization: QuantizationType,
  availableStorage: Long,
  onQuantizationSelected: (QuantizationType) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.local_model_quantization_select),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = model.name,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))

        model.quantizations.forEach { quantization ->
          val isRecommended = quantization.type == recommendedQuantization
          val hasEnoughStorage = availableStorage >= quantization.totalSize + 500 * 1024 * 1024

          QuantizationOption(
            quantization = quantization,
            isRecommended = isRecommended,
            hasEnoughStorage = hasEnoughStorage,
            onClick = {
              if (hasEnoughStorage) {
                onQuantizationSelected(quantization.type)
              }
            },
          )
        }
      }
    },
    confirmButton = {},
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
private fun QuantizationOption(
  quantization: QuantizationInfo,
  isRecommended: Boolean,
  hasEnoughStorage: Boolean,
  onClick: () -> Unit,
) {
  val enabled = hasEnoughStorage

  Surface(
    modifier = Modifier.fillMaxWidth(),
    onClick = onClick,
    enabled = enabled,
    color = if (enabled) {
      FutonTheme.colors.backgroundTertiary
    } else {
      FutonTheme.colors.backgroundTertiary.copy(alpha = 0.5f)
    },
    shape = MaterialTheme.shapes.small,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = quantization.type.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
              FutonTheme.colors.textNormal
            } else {
              FutonTheme.colors.textMuted
            },
          )
          if (isRecommended) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = stringResource(R.string.local_model_quantization_recommended),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusPositive,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(
            R.string.local_model_quantization_size_ram,
            quantization.totalSizeFormatted,
            quantization.minRamFormatted,
          ),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
        if (!hasEnoughStorage) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = stringResource(R.string.local_model_storage_insufficient),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.statusDanger,
          )
        }
      }
      Icon(
        imageVector = FutonIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = if (enabled) {
          FutonTheme.colors.textMuted
        } else {
          FutonTheme.colors.interactiveMuted
        },
      )
    }
  }
}
