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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.download.DownloadProgress
import me.fleey.futon.data.localmodel.download.DownloadState
import me.fleey.futon.data.localmodel.models.DownloadedModel
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.component.buttons.FutonIconButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun CompactModelCard(
  model: ModelInfo,
  downloadedModel: DownloadedModel?,
  downloadProgress: DownloadProgress?,
  isActive: Boolean,
  isExpanded: Boolean,
  isRecommended: Boolean,
  onExpandToggle: () -> Unit,
  onDownloadClick: () -> Unit,
  onPauseClick: () -> Unit,
  onResumeClick: () -> Unit,
  onCancelClick: () -> Unit,
  onEnableClick: () -> Unit,
  onDisableClick: () -> Unit,
  onDeleteClick: () -> Unit,
  onQuantizationSelect: (QuantizationInfo) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val isDownloaded = downloadedModel != null
  val isDownloading = downloadProgress != null

  val expandIconRotation by animateFloatAsState(
    targetValue = if (isExpanded) 180f else 0f,
    animationSpec = tween(durationMillis = 200),
    label = "expandIconRotation",
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(durationMillis = 200))
      .clickable(onClick = onExpandToggle)
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
  ) {
    CompactModelHeader(
      model = model,
      isActive = isActive,
      isRecommended = isRecommended,
      isExpanded = isExpanded,
      expandIconRotation = expandIconRotation,
    )

    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
      exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
    ) {
      Column {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = model.description,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isDownloading) {
          CompactDownloadProgress(
            progress = downloadProgress,
            onPauseClick = onPauseClick,
            onResumeClick = onResumeClick,
            onCancelClick = onCancelClick,
          )
        } else if (isDownloaded) {
          DownloadedModelActions(
            downloadedModel = downloadedModel,
            isActive = isActive,
            onEnableClick = onEnableClick,
            onDisableClick = onDisableClick,
            onDeleteClick = onDeleteClick,
          )
        } else {
          QuantizationOptions(
            quantizations = model.quantizations,
            onQuantizationSelect = onQuantizationSelect,
            onDownloadClick = onDownloadClick,
          )
        }
      }
    }
  }
}


@Composable
private fun CompactModelHeader(
  model: ModelInfo,
  isActive: Boolean,
  isRecommended: Boolean,
  isExpanded: Boolean,
  expandIconRotation: Float,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = FutonIcons.Model,
      contentDescription = null,
      modifier = Modifier.size(FutonSizes.IconSize),
      tint = if (isActive) MaterialTheme.colorScheme.primary else FutonTheme.colors.interactiveNormal,
    )

    Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))

    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = model.name,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )

        if (isActive) {
          CompactBadge(
            text = stringResource(R.string.local_model_enabled),
            backgroundColor = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
            textColor = FutonTheme.colors.statusPositive,
          )
        }

        CompactBadge(
          text = if (model.isVisionLanguageModel) "VLM" else "LLM",
          backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
          textColor = MaterialTheme.colorScheme.primary,
        )

        if (isRecommended) {
          CompactBadge(
            text = stringResource(R.string.local_model_quantization_recommended),
            backgroundColor = FutonTheme.colors.statusPositive.copy(alpha = 0.15f),
            textColor = FutonTheme.colors.statusPositive,
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      if (!isExpanded) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = model.provider,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
          Text(
            text = "·",
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
          Text(
            text = model.description,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Text(
          text = model.provider,
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }

    Icon(
      imageVector = FutonIcons.ExpandMore,
      contentDescription = if (isExpanded) {
        stringResource(R.string.local_model_collapse)
      } else {
        stringResource(R.string.local_model_expand)
      },
      modifier = Modifier
        .size(24.dp)
        .rotate(expandIconRotation),
      tint = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun CompactBadge(
  text: String,
  backgroundColor: androidx.compose.ui.graphics.Color,
  textColor: androidx.compose.ui.graphics.Color,
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(backgroundColor)
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
    )
  }
}

@Composable
private fun QuantizationOptions(
  quantizations: List<QuantizationInfo>,
  onQuantizationSelect: (QuantizationInfo) -> Unit,
  onDownloadClick: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(R.string.local_model_available_quantizations),
      style = MaterialTheme.typography.labelMedium,
      color = FutonTheme.colors.textNormal,
    )

    quantizations.forEach { quant ->
      QuantizationOptionItem(
        quantization = quant,
        onSelect = { onQuantizationSelect(quant) },
      )
    }

    Spacer(modifier = Modifier.height(4.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      FutonButton(
        text = stringResource(R.string.local_model_download),
        onClick = onDownloadClick,
        icon = FutonIcons.Add,
      )
    }
  }
}

@Composable
private fun QuantizationOptionItem(
  quantization: QuantizationInfo,
  onSelect: () -> Unit,
) {
  Surface(
    onClick = onSelect,
    color = FutonTheme.colors.backgroundTertiary,
    shape = RoundedCornerShape(8.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = quantization.type.name,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
        )
        Text(
          text = stringResource(R.string.local_model_ram_required, quantization.minRamFormatted),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
      Text(
        text = quantization.totalSizeFormatted,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun CompactDownloadProgress(
  progress: DownloadProgress,
  onPauseClick: () -> Unit,
  onResumeClick: () -> Unit,
  onCancelClick: () -> Unit,
) {
  val isPaused = progress.state is DownloadState.Paused
  val isFailed = progress.state is DownloadState.Failed

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    LinearProgressIndicator(
      progress = { progress.overallProgress },
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(RoundedCornerShape(2.dp)),
      color = when {
        isFailed -> FutonTheme.colors.statusDanger
        isPaused -> FutonTheme.colors.statusWarning
        else -> MaterialTheme.colorScheme.primary
      },
      trackColor = FutonTheme.colors.interactiveMuted,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = when {
            isFailed -> stringResource(R.string.local_model_download_failed)
            isPaused -> stringResource(
              R.string.local_model_download_paused,
              progress.overallProgressPercent,
            )

            else -> stringResource(
              R.string.local_model_downloading,
              progress.overallProgressPercent,
            )
          },
          style = MaterialTheme.typography.bodySmall,
          color = when {
            isFailed -> FutonTheme.colors.statusDanger
            else -> FutonTheme.colors.textMuted
          },
        )
        if (progress.files.isNotEmpty()) {
          val currentFile = progress.files.find { !it.isComplete }
          currentFile?.let {
            Text(
              text = "${it.downloadedFormatted} / ${it.totalFormatted}",
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
            )
          }
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isPaused || isFailed) {
          FutonIconButton(
            icon = FutonIcons.Play,
            onClick = onResumeClick,
            contentDescription = stringResource(R.string.local_model_resume_download),
          )
        } else {
          FutonIconButton(
            icon = FutonIcons.Stop,
            onClick = onPauseClick,
            contentDescription = stringResource(R.string.local_model_pause_download),
          )
        }
        FutonIconButton(
          icon = FutonIcons.Close,
          onClick = onCancelClick,
          contentDescription = stringResource(R.string.local_model_cancel_download),
          tint = FutonTheme.colors.statusDanger,
        )
      }
    }
  }
}

@Composable
private fun DownloadedModelActions(
  downloadedModel: DownloadedModel,
  isActive: Boolean,
  onEnableClick: () -> Unit,
  onDisableClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.local_model_quantization, downloadedModel.quantization.name),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Text(
        text = stringResource(R.string.local_model_size, downloadedModel.sizeFormatted),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FutonIconButton(
        icon = FutonIcons.Delete,
        onClick = onDeleteClick,
        contentDescription = stringResource(R.string.local_model_delete),
        tint = FutonTheme.colors.statusDanger,
      )
      Spacer(modifier = Modifier.width(8.dp))
      if (isActive) {
        FutonButton(
          text = stringResource(R.string.local_model_disable),
          onClick = onDisableClick,
          style = ButtonStyle.Secondary,
        )
      } else {
        FutonButton(
          text = stringResource(R.string.local_model_enable),
          onClick = onEnableClick,
          style = ButtonStyle.Primary,
        )
      }
    }
  }
}
