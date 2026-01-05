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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.models.InferenceConfig
import me.fleey.futon.ui.designsystem.component.selection.FutonSlider
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.localmodel.InferencePreset

@Composable
fun InferenceConfigSection(
  config: InferenceConfig,
  onContextLengthChange: (Int) -> Unit,
  onThreadCountChange: (Int) -> Unit,
  onPresetSelected: (InferencePreset) -> Unit,
  onUseNnapiChange: (Boolean) -> Unit,
  supportsNnapi: Boolean,
  modifier: Modifier = Modifier,
) {
  SettingsGroup(
    title = stringResource(R.string.inference_preset_config),
    modifier = modifier,
  ) {
    item {
      PresetButtonRow(
        currentConfig = config,
        onPresetSelected = onPresetSelected,
        modifier = Modifier.padding(16.dp),
      )
    }

    item {
      ConfigSlider(
        title = stringResource(R.string.inference_context_length),
        description = stringResource(R.string.inference_context_length_desc),
        value = config.contextLength.toFloat(),
        onValueChange = { onContextLengthChange(it.toInt()) },
        valueRange = 512f..8192f,
        steps = 14,
        valueFormatter = { stringResource(R.string.inference_context_length_value, it.toInt()) },
        modifier = Modifier.padding(16.dp),
      )
    }

    item {
      ConfigSlider(
        title = stringResource(R.string.inference_thread_count),
        description = stringResource(R.string.inference_thread_count_desc),
        value = config.numThreads.toFloat(),
        onValueChange = { onThreadCountChange(it.toInt()) },
        valueRange = 1f..8f,
        steps = 6,
        valueFormatter = { stringResource(R.string.inference_thread_count_value, it.toInt()) },
        modifier = Modifier.padding(16.dp),
      )
    }

    item {
      NnapiToggle(
        enabled = config.useNnapi,
        onEnabledChange = onUseNnapiChange,
        supportsNnapi = supportsNnapi,
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}

@Composable
private fun PresetButtonRow(
  currentConfig: InferenceConfig,
  onPresetSelected: (InferencePreset) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.inference_preset_config),
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textNormal,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PresetButton(
        text = stringResource(R.string.inference_preset_fast),
        description = stringResource(R.string.inference_preset_fast_desc),
        isSelected = isConfigMatchingPreset(currentConfig, InferencePreset.FAST),
        onClick = { onPresetSelected(InferencePreset.FAST) },
        modifier = Modifier.weight(1f),
      )
      PresetButton(
        text = stringResource(R.string.inference_preset_balanced),
        description = stringResource(R.string.inference_preset_balanced_desc),
        isSelected = isConfigMatchingPreset(currentConfig, InferencePreset.DEFAULT),
        onClick = { onPresetSelected(InferencePreset.DEFAULT) },
        modifier = Modifier.weight(1f),
      )
      PresetButton(
        text = stringResource(R.string.inference_preset_quality),
        description = stringResource(R.string.inference_preset_quality_desc),
        isSelected = isConfigMatchingPreset(currentConfig, InferencePreset.QUALITY),
        onClick = { onPresetSelected(InferencePreset.QUALITY) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun PresetButton(
  text: String,
  description: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    onClick = onClick,
    color = if (isSelected) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
      FutonTheme.colors.backgroundTertiary
    },
    shape = MaterialTheme.shapes.small,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) {
          MaterialTheme.colorScheme.primary
        } else {
          FutonTheme.colors.textNormal
        },
      )
      Text(
        text = description,
        style = MaterialTheme.typography.labelSmall,
        color = if (isSelected) {
          MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        } else {
          FutonTheme.colors.textMuted
        },
      )
    }
  }
}

@Composable
private fun ConfigSlider(
  title: String,
  description: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  valueFormatter: @Composable (Float) -> String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
      Text(
        text = valueFormatter(value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    FutonSlider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      steps = steps,
    )
  }
}

@Composable
private fun NnapiToggle(
  enabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  supportsNnapi: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.inference_nnapi),
          style = MaterialTheme.typography.bodyMedium,
          color = if (supportsNnapi) {
            FutonTheme.colors.textNormal
          } else {
            FutonTheme.colors.textMuted
          },
        )
        if (!supportsNnapi) {
          Spacer(modifier = Modifier.width(8.dp))
          Surface(
            color = FutonTheme.colors.statusWarning.copy(alpha = 0.2f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = stringResource(R.string.inference_nnapi_unsupported),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusWarning,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }
      Text(
        text = stringResource(R.string.inference_nnapi_description),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
    FutonSwitch(
      checked = enabled,
      onCheckedChange = onEnabledChange,
      enabled = supportsNnapi,
    )
  }
}

/**
 * Check if the current config matches a preset.
 */
private fun isConfigMatchingPreset(config: InferenceConfig, preset: InferencePreset): Boolean {
  val presetConfig = when (preset) {
    InferencePreset.FAST -> InferenceConfig.FAST
    InferencePreset.QUALITY -> InferenceConfig.QUALITY
    InferencePreset.DEFAULT -> InferenceConfig()
  }
  return config.contextLength == presetConfig.contextLength &&
    config.numThreads == presetConfig.numThreads
}
