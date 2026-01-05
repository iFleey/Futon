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
package me.fleey.futon.ui.feature.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.models.DebugDetection
import me.fleey.futon.data.daemon.models.DebugStreamState
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun DebugDashboardScreen(
  onBack: () -> Unit,
  viewModel: DebugDashboardViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.debug_dashboard_title),
        onBackClick = onBack,
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

      ConnectionStatusCard(
        streamState = uiState.streamState,
        onConnect = { viewModel.onEvent(DebugDashboardUiEvent.Connect) },
        onDisconnect = { viewModel.onEvent(DebugDashboardUiEvent.Disconnect) },
      )

      Spacer(modifier = Modifier.height(12.dp))

      PerformanceMetricsCard(
        fps = uiState.currentFps,
        latencyMs = uiState.currentLatencyMs,
        frameCount = uiState.frameCount,
        fpsHistory = uiState.fpsHistory,
        latencyHistory = uiState.latencyHistory,
      )

      Spacer(modifier = Modifier.height(12.dp))

      DelegateInfoCard(
        delegateType = uiState.activeDelegate,
      )

      Spacer(modifier = Modifier.height(12.dp))

      DetectionResultsCard(
        detections = uiState.latestDetections,
        screenWidth = uiState.screenWidth,
        screenHeight = uiState.screenHeight,
      )

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun ConnectionStatusCard(
  streamState: DebugStreamState,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Wifi,
          contentDescription = null,
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.debug_stream_status),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        val (statusText, statusColor) = when (streamState) {
          is DebugStreamState.Disconnected -> Pair(
            stringResource(R.string.debug_stream_disconnected),
            FutonTheme.colors.textMuted,
          )

          is DebugStreamState.Connecting -> Pair(
            stringResource(
              R.string.debug_stream_connecting,
              streamState.attempt,
              streamState.maxAttempts,
            ),
            FutonTheme.colors.statusWarning,
          )

          is DebugStreamState.Connected -> Pair(
            stringResource(R.string.debug_stream_connected, streamState.port),
            FutonTheme.colors.statusPositive,
          )

          is DebugStreamState.Error -> Pair(
            stringResource(R.string.debug_stream_error),
            FutonTheme.colors.statusDanger,
          )
        }

        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(statusColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = statusText,
          style = MaterialTheme.typography.bodyMedium,
          color = statusColor,
          modifier = Modifier.weight(1f),
        )

        if (streamState.isConnected) {
          OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(R.string.debug_stream_disconnect))
          }
        } else {
          Button(onClick = onConnect) {
            Text(stringResource(R.string.debug_stream_connect))
          }
        }
      }

      if (streamState is DebugStreamState.Error) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = streamState.message,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.statusDanger,
        )
      }
    }
  }
}

@Composable
private fun PerformanceMetricsCard(
  fps: Float,
  latencyMs: Float,
  frameCount: Long,
  fpsHistory: List<Float>,
  latencyHistory: List<Float>,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Speed,
          contentDescription = null,
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.debug_performance_metrics),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        MetricItem(
          label = stringResource(R.string.debug_fps),
          value = String.format("%.1f", fps),
          unit = "fps",
          color = when {
            fps >= 25f -> FutonTheme.colors.statusPositive
            fps >= 15f -> FutonTheme.colors.statusWarning
            else -> FutonTheme.colors.statusDanger
          },
        )
        MetricItem(
          label = stringResource(R.string.debug_latency),
          value = String.format("%.1f", latencyMs),
          unit = "ms",
          color = when {
            latencyMs <= 30f -> FutonTheme.colors.statusPositive
            latencyMs <= 50f -> FutonTheme.colors.statusWarning
            else -> FutonTheme.colors.statusDanger
          },
        )
        MetricItem(
          label = stringResource(R.string.debug_frames),
          value = frameCount.toString(),
          unit = "",
          color = FutonTheme.colors.textNormal,
        )
      }

      if (fpsHistory.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.debug_fps_chart),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LineChart(
          data = fpsHistory,
          color = FutonTheme.colors.statusPositive,
          modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        )
      }

      if (latencyHistory.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.debug_latency_chart),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LineChart(
          data = latencyHistory,
          color = FutonTheme.colors.statusWarning,
          modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        )
      }
    }
  }
}

@Composable
private fun MetricItem(
  label: String,
  value: String,
  unit: String,
  color: Color,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
      verticalAlignment = Alignment.Bottom,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = color,
      )
      if (unit.isNotEmpty()) {
        Spacer(modifier = Modifier.width(2.dp))
        Text(
          text = unit,
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

@Composable
private fun LineChart(
  data: List<Float>,
  color: Color,
  modifier: Modifier = Modifier,
) {
  if (data.isEmpty()) return

  val maxValue = remember(data) { data.maxOrNull() ?: 1f }
  val minValue = remember(data) { data.minOrNull() ?: 0f }
  val range = (maxValue - minValue).coerceAtLeast(1f)

  Canvas(modifier = modifier) {
    val width = size.width
    val height = size.height
    val stepX = width / (data.size - 1).coerceAtLeast(1)

    val path = Path()
    data.forEachIndexed { index, value ->
      val x = index * stepX
      val normalizedValue = (value - minValue) / range
      val y = height - (normalizedValue * height)

      if (index == 0) {
        path.moveTo(x, y)
      } else {
        path.lineTo(x, y)
      }
    }

    drawPath(
      path = path,
      color = color,
      style = Stroke(width = 2.dp.toPx()),
    )
  }
}

@Composable
private fun DelegateInfoCard(
  delegateType: DelegateType,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Cache,
          contentDescription = null,
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.debug_delegate_info),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      val (delegateName, delegateColor) = when (delegateType) {
        DelegateType.NONE -> Pair(
          stringResource(R.string.debug_delegate_none),
          FutonTheme.colors.textMuted,
        )

        DelegateType.GPU -> Pair(
          stringResource(R.string.debug_delegate_gpu),
          FutonTheme.colors.statusPositive,
        )

        DelegateType.NNAPI -> Pair(
          stringResource(R.string.debug_delegate_nnapi),
          FutonTheme.colors.statusPositive,
        )

        DelegateType.HEXAGON_DSP -> Pair(
          stringResource(R.string.debug_delegate_hexagon),
          FutonTheme.colors.statusPositive,
        )

        DelegateType.XNNPACK -> Pair(
          stringResource(R.string.debug_delegate_xnnpack),
          FutonTheme.colors.statusWarning,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.debug_active_delegate),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textMuted,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = delegateName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = delegateColor,
        )
      }
    }
  }
}

@Composable
private fun DetectionResultsCard(
  detections: List<DebugDetection>,
  screenWidth: Int,
  screenHeight: Int,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Perception,
          contentDescription = null,
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.debug_detection_results),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = stringResource(R.string.debug_detection_count, detections.size),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (detections.isEmpty()) {
        Text(
          text = stringResource(R.string.debug_no_detections),
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textMuted,
        )
      } else {
        DetectionOverlay(
          detections = detections,
          screenWidth = screenWidth,
          screenHeight = screenHeight,
          modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        detections.take(5).forEach { detection ->
          DetectionItem(detection = detection)
          Spacer(modifier = Modifier.height(8.dp))
        }

        if (detections.size > 5) {
          Text(
            text = stringResource(R.string.debug_more_detections, detections.size - 5),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
  }
}

@Composable
private fun DetectionOverlay(
  detections: List<DebugDetection>,
  screenWidth: Int,
  screenHeight: Int,
  modifier: Modifier = Modifier,
) {
  val colors = listOf(
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFFFF9800),
    Color(0xFFE91E63),
    Color(0xFF9C27B0),
  )

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(FutonTheme.colors.backgroundTertiary)
      .border(1.dp, FutonTheme.colors.interactiveMuted, RoundedCornerShape(8.dp)),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scaleX = size.width / screenWidth.coerceAtLeast(1)
      val scaleY = size.height / screenHeight.coerceAtLeast(1)

      detections.forEachIndexed { index, detection ->
        val color = colors[index % colors.size]
        val bbox = detection.boundingBox

        val left = bbox.x * scaleX
        val top = bbox.y * scaleY
        val width = bbox.width * scaleX
        val height = bbox.height * scaleY

        drawRect(
          color = color,
          topLeft = Offset(left, top),
          size = Size(width, height),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
    }
  }
}

@Composable
private fun DetectionItem(
  detection: DebugDetection,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(FutonTheme.colors.backgroundTertiary)
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.debug_class_id, detection.classId),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textNormal,
      )
      Text(
        text = stringResource(
          R.string.debug_bbox_info,
          detection.boundingBox.x,
          detection.boundingBox.y,
          detection.boundingBox.width,
          detection.boundingBox.height,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      if (detection.hasText) {
        Text(
          text = stringResource(R.string.debug_ocr_text, detection.text ?: ""),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
    Text(
      text = String.format("%.1f%%", detection.confidence * 100),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = when {
        detection.confidence >= 0.8f -> FutonTheme.colors.statusPositive
        detection.confidence >= 0.5f -> FutonTheme.colors.statusWarning
        else -> FutonTheme.colors.statusDanger
      },
    )
  }
}
