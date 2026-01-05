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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.models.DaemonPerformanceMetrics
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme


private const val LATENCY_WARNING_THRESHOLD_MS = 50f
private const val TARGET_FPS = 30f

/**
 * Card displaying real-time daemon performance metrics.
 *
 * Shows FPS, capture latency, inference latency, and total latency.
 * Highlights warnings when latency exceeds threshold.
 * Expandable to show detailed metrics panel.
 */
@Composable
fun DaemonMetricsCard(
  metrics: DaemonPerformanceMetrics,
  modifier: Modifier = Modifier,
  latencyThresholdMs: Float = LATENCY_WARNING_THRESHOLD_MS,
) {
  var expanded by remember { mutableStateOf(false) }
  val isWarning =
    metrics.isAboveLatencyThreshold || metrics.rollingAverageTotalMs > latencyThresholdMs

  val warningColor by animateColorAsState(
    targetValue = if (isWarning) FutonTheme.colors.statusWarning else FutonTheme.colors.statusPositive,
    label = "warningColor",
  )

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clickable { expanded = !expanded },
    shape = FutonShapes.CardShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(modifier = Modifier.padding(FutonSizes.CardPadding)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Speed,
            contentDescription = null,
            tint = warningColor,
            modifier = Modifier.size(FutonSizes.IconSize),
          )
          Spacer(modifier = Modifier.width(FutonSizes.CardElementSpacing))
          Column {
            Text(
              text = stringResource(R.string.daemon_metrics_title),
              style = MaterialTheme.typography.titleMedium,
            )
            if (isWarning) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = null,
                  tint = FutonTheme.colors.statusWarning,
                  modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = stringResource(R.string.daemon_metrics_latency_warning),
                  style = MaterialTheme.typography.labelSmall,
                  color = FutonTheme.colors.statusWarning,
                )
              }
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          MetricBadge(
            label = stringResource(R.string.daemon_metrics_fps),
            value = stringResource(R.string.daemon_metrics_fps_value, metrics.rollingAverageFps),
            isWarning = metrics.rollingAverageFps < TARGET_FPS * 0.8f,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) {
              stringResource(R.string.daemon_metrics_hide_details)
            } else {
              stringResource(R.string.daemon_metrics_show_details)
            },
            tint = FutonTheme.colors.textMuted,
            modifier = Modifier.size(FutonSizes.IconSize),
          )
        }
      }

      Spacer(modifier = Modifier.height(FutonSizes.CardElementSpacing))

      MetricsOverview(metrics = metrics, latencyThresholdMs = latencyThresholdMs)

      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        DetailedMetricsPanel(metrics = metrics, latencyThresholdMs = latencyThresholdMs)
      }
    }
  }
}

@Composable
private fun MetricBadge(
  label: String,
  value: String,
  isWarning: Boolean = false,
) {
  val backgroundColor = if (isWarning) {
    FutonTheme.colors.statusWarning.copy(alpha = 0.15f)
  } else {
    MaterialTheme.colorScheme.primaryContainer
  }
  val textColor = if (isWarning) {
    FutonTheme.colors.statusWarning
  } else {
    MaterialTheme.colorScheme.onPrimaryContainer
  }

  Surface(
    shape = FutonShapes.ChipShape,
    color = backgroundColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = textColor.copy(alpha = 0.7f),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = textColor,
      )
    }
  }
}

@Composable
private fun MetricsOverview(
  metrics: DaemonPerformanceMetrics,
  latencyThresholdMs: Float,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    LatencyMetric(
      label = stringResource(R.string.daemon_metrics_capture_latency),
      valueMs = metrics.rollingAverageCaptureMs,
      thresholdMs = latencyThresholdMs / 3,
      modifier = Modifier.weight(1f),
    )
    LatencyMetric(
      label = stringResource(R.string.daemon_metrics_inference_latency),
      valueMs = metrics.rollingAverageInferenceMs,
      thresholdMs = latencyThresholdMs * 0.6f,
      modifier = Modifier.weight(1f),
    )
    LatencyMetric(
      label = stringResource(R.string.daemon_metrics_total_latency),
      valueMs = metrics.rollingAverageTotalMs,
      thresholdMs = latencyThresholdMs,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun LatencyMetric(
  label: String,
  valueMs: Float,
  thresholdMs: Float,
  modifier: Modifier = Modifier,
) {
  val isWarning = valueMs > thresholdMs
  val color by animateColorAsState(
    targetValue = if (isWarning) FutonTheme.colors.statusWarning else FutonTheme.colors.textNormal,
    label = "latencyColor",
  )

  Column(
    modifier = modifier.padding(horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
    Text(
      text = stringResource(R.string.daemon_metrics_ms, valueMs),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = color,
    )
  }
}

@Composable
private fun DetailedMetricsPanel(
  metrics: DaemonPerformanceMetrics,
  latencyThresholdMs: Float,
) {
  Column(modifier = Modifier.padding(top = FutonSizes.CardElementSpacing)) {
    Surface(
      shape = FutonShapes.CardShape,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        DetailRow(
          label = stringResource(R.string.daemon_metrics_delegate),
          value = metrics.activeDelegate.toDisplayName(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(
          label = stringResource(R.string.daemon_metrics_frame_count),
          value = metrics.frameCount.toString(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(
          label = stringResource(R.string.daemon_metrics_fps),
          value = stringResource(R.string.daemon_metrics_fps_value, metrics.fps),
          subValue = "avg: ${String.format("%.1f", metrics.rollingAverageFps)}",
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "Latency Breakdown",
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))

        LatencyBar(
          label = stringResource(R.string.daemon_metrics_capture_latency),
          currentMs = metrics.captureLatencyMs,
          averageMs = metrics.rollingAverageCaptureMs,
          maxMs = latencyThresholdMs,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LatencyBar(
          label = stringResource(R.string.daemon_metrics_inference_latency),
          currentMs = metrics.inferenceLatencyMs,
          averageMs = metrics.rollingAverageInferenceMs,
          maxMs = latencyThresholdMs,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LatencyBar(
          label = stringResource(R.string.daemon_metrics_total_latency),
          currentMs = metrics.totalLatencyMs,
          averageMs = metrics.rollingAverageTotalMs,
          maxMs = latencyThresholdMs,
        )
      }
    }
  }
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  subValue: String? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = FutonTheme.colors.textNormal,
      )
      subValue?.let {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = it,
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

@Composable
private fun LatencyBar(
  label: String,
  currentMs: Float,
  averageMs: Float,
  maxMs: Float,
) {
  val progress by animateFloatAsState(
    targetValue = (averageMs / maxMs).coerceIn(0f, 1f),
    label = "latencyProgress",
  )
  val isWarning = averageMs > maxMs
  val barColor =
    if (isWarning) FutonTheme.colors.statusWarning else MaterialTheme.colorScheme.primary

  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = FutonTheme.colors.textMuted,
      )
      Text(
        text = stringResource(R.string.daemon_metrics_ms, averageMs),
        style = MaterialTheme.typography.labelSmall,
        color = if (isWarning) FutonTheme.colors.statusWarning else FutonTheme.colors.textNormal,
      )
    }
    Spacer(modifier = Modifier.height(2.dp))
    LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(FutonShapes.ProgressBarShape),
      color = barColor,
      trackColor = FutonTheme.colors.interactiveMuted,
    )
  }
}

private fun DelegateType.toDisplayName(): String = when (this) {
  DelegateType.HEXAGON_DSP -> "Hexagon DSP"
  DelegateType.GPU -> "GPU"
  DelegateType.NNAPI -> "NNAPI"
  DelegateType.XNNPACK -> "XNNPACK"
  DelegateType.NONE -> "None"
}
