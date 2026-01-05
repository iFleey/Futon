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
package me.fleey.futon.ui.feature.settings.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun InferenceMetricsScreen(
  onBack: () -> Unit,
  viewModel: InferenceMetricsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  var showResetDialog by remember { mutableStateOf(false) }

  if (showResetDialog) {
    ResetConfirmDialog(
      onConfirm = {
        viewModel.onEvent(InferenceMetricsUiEvent.ResetStatistics)
        showResetDialog = false
      },
      onDismiss = { showResetDialog = false },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_inference_metrics),
        onBackClick = onBack,
        actions = {
          IconButton(
            onClick = { showResetDialog = true },
            enabled = uiState.totalRequests > 0,
          ) {
            Icon(
              imageVector = FutonIcons.Refresh,
              contentDescription = stringResource(R.string.metrics_reset_statistics),
              tint = if (uiState.totalRequests > 0) {
                FutonTheme.colors.textNormal
              } else {
                FutonTheme.colors.textMuted
              },
            )
          }
        },
      )
    },
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

      UsageDistributionSection(uiState.modelMetrics, uiState.totalRequests)

      Spacer(modifier = Modifier.height(16.dp))

      TokenUsageSection(uiState)

      Spacer(modifier = Modifier.height(16.dp))

      LatencySection(uiState.modelMetrics)

      Spacer(modifier = Modifier.height(16.dp))

      CostSection(uiState.modelMetrics, uiState.totalCost)

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun ResetConfirmDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.metrics_reset_title)) },
    text = { Text(stringResource(R.string.metrics_reset_confirm_message)) },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(
          text = stringResource(R.string.metrics_reset_statistics),
          color = FutonTheme.colors.statusDanger,
        )
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
private fun UsageDistributionSection(metrics: List<ModelMetricsUi>, totalRequests: Int) {
  val distribution = remember(metrics, totalRequests) {
    metrics.map { m ->
      m to if (totalRequests > 0) m.requestCount.toFloat() / totalRequests else 0f
    }
  }

  SettingsGroup(title = stringResource(R.string.metrics_usage_distribution)) {
    item {
      if (totalRequests > 0) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(24.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PieChart(
            data = distribution.map { it.second },
            modifier = Modifier.size(120.dp),
          )
          PieLegend(
            distribution = distribution,
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.metrics_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textMuted,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
private fun PieChart(
  data: List<Float>,
  modifier: Modifier = Modifier,
) {
  val colors = remember {
    listOf(
      Color(0xFF6366F1),
      Color(0xFF22C55E),
      Color(0xFFF59E0B),
      Color(0xFFEF4444),
      Color(0xFF8B5CF6),
      Color(0xFF06B6D4),
      Color(0xFFEC4899),
      Color(0xFF84CC16),
    )
  }

  Canvas(modifier = modifier) {
    val strokeWidth = 32.dp.toPx()
    val radius = (size.minDimension - strokeWidth) / 2
    val center = Offset(size.width / 2, size.height / 2)

    var startAngle = -90f
    data.forEachIndexed { index, percentage ->
      if (percentage > 0) {
        val sweepAngle = percentage * 360f
        drawArc(
          color = colors[index % colors.size],
          startAngle = startAngle,
          sweepAngle = sweepAngle,
          useCenter = false,
          topLeft = Offset(center.x - radius, center.y - radius),
          size = Size(radius * 2, radius * 2),
          style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
        startAngle += sweepAngle
      }
    }
  }
}

@Composable
private fun PieLegend(
  distribution: List<Pair<ModelMetricsUi, Float>>,
  modifier: Modifier = Modifier,
) {
  val colors = listOf(
    Color(0xFF6366F1),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFF06B6D4),
    Color(0xFFEC4899),
    Color(0xFF84CC16),
  )

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    distribution.forEachIndexed { index, (model, percentage) ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(colors[index % colors.size]),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = model.modelId,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = model.providerName,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = stringResource(R.string.metrics_percentage, (percentage * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = colors[index % colors.size],
          )
          Text(
            text = stringResource(R.string.metrics_requests_short, model.requestCount),
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
  }
}

@Composable
private fun TokenUsageSection(uiState: InferenceMetricsUiState) {
  SettingsGroup(title = stringResource(R.string.metrics_token_usage)) {
    item {
      if (uiState.modelMetrics.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.metrics_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textMuted,
          )
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          TokenRow(
            label = stringResource(R.string.metrics_input_tokens),
            value = formatTokenCount(uiState.totalInputTokens),
          )
          TokenRow(
            label = stringResource(R.string.metrics_output_tokens),
            value = formatTokenCount(uiState.totalOutputTokens),
          )
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FutonTheme.colors.interactiveMuted,
            shape = RoundedCornerShape(8.dp),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.metrics_total_tokens),
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = formatTokenCount(uiState.totalInputTokens + uiState.totalOutputTokens),
                style = MaterialTheme.typography.titleSmall,
                color = FutonTheme.colors.interactiveNormal,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TokenRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun LatencySection(metrics: List<ModelMetricsUi>) {
  SettingsGroup(title = stringResource(R.string.metrics_latency)) {
    item {
      if (metrics.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.metrics_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textMuted,
          )
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          metrics.forEach { model ->
            LatencyItem(model)
          }
        }
      }
    }
  }
}

@Composable
private fun LatencyItem(model: ModelMetricsUi) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = model.modelId,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = model.providerName,
        style = MaterialTheme.typography.labelSmall,
        color = FutonTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(modifier = Modifier.width(16.dp))
    Text(
      text = stringResource(R.string.metrics_avg_latency, model.avgLatencyMs),
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun CostSection(metrics: List<ModelMetricsUi>, totalCost: Double) {
  SettingsGroup(title = stringResource(R.string.metrics_cost_estimation)) {
    item {
      if (metrics.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.metrics_no_cost_data),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textMuted,
          )
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          metrics.filter { it.totalCost > 0 }.forEach { model ->
            CostItem(model)
          }

          if (totalCost > 0) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = FutonTheme.colors.interactiveMuted,
              shape = RoundedCornerShape(8.dp),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.metrics_total_cost),
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = stringResource(R.string.metrics_cost_value, totalCost),
                  style = MaterialTheme.typography.titleSmall,
                  color = FutonTheme.colors.statusWarning,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CostItem(model: ModelMetricsUi) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = model.modelId,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = model.providerName,
        style = MaterialTheme.typography.labelSmall,
        color = FutonTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(modifier = Modifier.width(16.dp))
    Text(
      text = stringResource(R.string.metrics_cost_value, model.totalCost),
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
  }
}

private fun formatTokenCount(count: Long): String = when {
  count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
  count >= 1_000 -> "%.1fK".format(count / 1_000.0)
  else -> count.toString()
}
