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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.debug.RecoveryMetrics
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChaosTestingScreen(
  onBack: () -> Unit,
  viewModel: ChaosTestingViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.debug_chaos_testing_title),
        onBackClick = onBack,
        actions = {
          IconButton(onClick = { viewModel.onEvent(ChaosTestingUiEvent.ClearEvents) }) {
            Icon(
              imageVector = FutonIcons.Clear,
              contentDescription = stringResource(R.string.action_clear),
              tint = FutonTheme.colors.textMuted,
            )
          }
        },
      )
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(8.dp))
      }

      item {
        ChaosControlCard(
          isEnabled = uiState.isEnabled,
          onToggle = { viewModel.onEvent(ChaosTestingUiEvent.ToggleEnabled) },
        )
      }

      item {
        ChaosActionsCard(
          isEnabled = uiState.isEnabled,
          networkDelayMs = uiState.networkDelayMs,
          memoryPressureLevel = uiState.memoryPressureLevel,
          onSimulateCrash = { viewModel.onEvent(ChaosTestingUiEvent.SimulateCrash) },
          onNetworkDelayChange = { viewModel.onEvent(ChaosTestingUiEvent.UpdateNetworkDelay(it)) },
          onSimulateNetworkDelay = { viewModel.onEvent(ChaosTestingUiEvent.SimulateNetworkDelay) },
          onMemoryPressureLevelChange = {
            viewModel.onEvent(
              ChaosTestingUiEvent.UpdateMemoryPressureLevel(
                it,
              ),
            )
          },
          onSimulateMemoryPressure = { viewModel.onEvent(ChaosTestingUiEvent.SimulateMemoryPressure) },
          onSimulateAuthFailure = { viewModel.onEvent(ChaosTestingUiEvent.SimulateAuthFailure) },
        )
      }

      item {
        RecoveryMetricsCard(
          metrics = uiState.recoveryMetrics,
          onReset = { viewModel.onEvent(ChaosTestingUiEvent.ResetMetrics) },
        )
      }

      item {
        Text(
          text = stringResource(R.string.debug_chaos_event_history),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }

      if (uiState.recentEvents.isEmpty()) {
        item {
          EmptyEventsState()
        }
      } else {
        items(
          items = uiState.recentEvents,
          key = { "${it.timestamp}_${it.type}" },
        ) { event ->
          ChaosEventItem(event = event)
        }
      }

      item {
        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }
}

@Composable
private fun ChaosControlCard(
  isEnabled: Boolean,
  onToggle: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        tint = if (isEnabled) FutonTheme.colors.statusDanger else FutonTheme.colors.textMuted,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.debug_chaos_enable),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
        Text(
          text = stringResource(R.string.debug_chaos_enable_description),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
      Switch(
        checked = isEnabled,
        onCheckedChange = { onToggle() },
        colors = SwitchDefaults.colors(
          checkedThumbColor = FutonTheme.colors.statusDanger,
          checkedTrackColor = FutonTheme.colors.statusDanger.copy(alpha = 0.3f),
        ),
      )
    }
  }
}

@Composable
private fun ChaosActionsCard(
  isEnabled: Boolean,
  networkDelayMs: Long,
  memoryPressureLevel: Int,
  onSimulateCrash: () -> Unit,
  onNetworkDelayChange: (Long) -> Unit,
  onSimulateNetworkDelay: () -> Unit,
  onMemoryPressureLevelChange: (Int) -> Unit,
  onSimulateMemoryPressure: () -> Unit,
  onSimulateAuthFailure: () -> Unit,
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
      Text(
        text = stringResource(R.string.debug_chaos_actions),
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textNormal,
      )

      Spacer(modifier = Modifier.height(16.dp))

      ChaosActionButton(
        title = stringResource(R.string.debug_chaos_simulate_crash),
        description = stringResource(R.string.debug_chaos_simulate_crash_description),
        enabled = isEnabled,
        isDanger = true,
        onClick = onSimulateCrash,
      )

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = stringResource(R.string.debug_chaos_network_delay),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textNormal,
      )
      Text(
        text = stringResource(R.string.debug_chaos_network_delay_description),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Slider(
          value = networkDelayMs.toFloat(),
          onValueChange = { onNetworkDelayChange(it.toLong()) },
          valueRange = 100f..5000f,
          steps = 48,
          enabled = isEnabled,
          modifier = Modifier.weight(1f),
          colors = SliderDefaults.colors(
            thumbColor = FutonTheme.colors.statusWarning,
            activeTrackColor = FutonTheme.colors.statusWarning,
          ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "${networkDelayMs}ms",
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.width(60.dp),
        )
      }
      OutlinedButton(
        onClick = onSimulateNetworkDelay,
        enabled = isEnabled,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.debug_chaos_apply_network_delay))
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = stringResource(R.string.debug_chaos_memory_pressure),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textNormal,
      )
      Text(
        text = stringResource(R.string.debug_chaos_memory_pressure_description),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Slider(
          value = memoryPressureLevel.toFloat(),
          onValueChange = { onMemoryPressureLevelChange(it.toInt()) },
          valueRange = 1f..5f,
          steps = 3,
          enabled = isEnabled,
          modifier = Modifier.weight(1f),
          colors = SliderDefaults.colors(
            thumbColor = FutonTheme.colors.statusWarning,
            activeTrackColor = FutonTheme.colors.statusWarning,
          ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.debug_chaos_level, memoryPressureLevel),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.width(60.dp),
        )
      }
      OutlinedButton(
        onClick = onSimulateMemoryPressure,
        enabled = isEnabled,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.debug_chaos_apply_memory_pressure))
      }

      Spacer(modifier = Modifier.height(16.dp))

      ChaosActionButton(
        title = stringResource(R.string.debug_chaos_simulate_auth_failure),
        description = stringResource(R.string.debug_chaos_simulate_auth_failure_description),
        enabled = isEnabled,
        isDanger = false,
        onClick = onSimulateAuthFailure,
      )
    }
  }
}

@Composable
private fun ChaosActionButton(
  title: String,
  description: String,
  enabled: Boolean,
  isDanger: Boolean,
  onClick: () -> Unit,
) {
  Column {
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
    Spacer(modifier = Modifier.height(8.dp))
    Button(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
      colors = if (isDanger) {
        ButtonDefaults.buttonColors(
          containerColor = FutonTheme.colors.statusDanger,
          contentColor = Color.White,
        )
      } else {
        ButtonDefaults.buttonColors()
      },
    ) {
      Text(title)
    }
  }
}

@Composable
private fun RecoveryMetricsCard(
  metrics: RecoveryMetrics,
  onReset: () -> Unit,
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
          text = stringResource(R.string.debug_chaos_recovery_metrics),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textNormal,
        )
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onReset) {
          Text(stringResource(R.string.debug_chaos_reset_metrics))
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        MetricItem(
          label = stringResource(R.string.debug_chaos_detection_time),
          value = "${metrics.lastCrashDetectionTimeMs}",
          unit = "ms",
        )
        MetricItem(
          label = stringResource(R.string.debug_chaos_reconciliation_time),
          value = "${metrics.lastReconciliationTimeMs}",
          unit = "ms",
        )
        MetricItem(
          label = stringResource(R.string.debug_chaos_total_recovery_time),
          value = "${metrics.lastTotalRecoveryTimeMs}",
          unit = "ms",
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        MetricItem(
          label = stringResource(R.string.debug_chaos_successful_recoveries),
          value = "${metrics.successfulRecoveries}",
          unit = "",
          color = FutonTheme.colors.statusPositive,
        )
        MetricItem(
          label = stringResource(R.string.debug_chaos_failed_recoveries),
          value = "${metrics.failedRecoveries}",
          unit = "",
          color = FutonTheme.colors.statusDanger,
        )
        MetricItem(
          label = stringResource(R.string.debug_chaos_success_rate),
          value = String.format("%.0f", metrics.recoverySuccessRate * 100),
          unit = "%",
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
  color: Color = FutonTheme.colors.textNormal,
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
        style = MaterialTheme.typography.titleLarge,
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
private fun ChaosEventItem(
  event: ChaosEventDisplay,
) {
  val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
  val eventColor = when (event.type) {
    "CRASH", "FAILED" -> FutonTheme.colors.statusDanger
    "RECOVERED", "DETECTED" -> FutonTheme.colors.statusPositive
    "NETWORK", "MEMORY", "AUTH" -> FutonTheme.colors.statusWarning
    else -> FutonTheme.colors.textMuted
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(8.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(eventColor),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = event.type,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = eventColor,
        modifier = Modifier.width(80.dp),
      )
      Text(
        text = event.description,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textNormal,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = dateFormat.format(Date(event.timestamp)),
        style = MaterialTheme.typography.labelSmall,
        color = FutonTheme.colors.textMuted,
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}

@Composable
private fun EmptyEventsState() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        imageVector = FutonIcons.Info,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = FutonTheme.colors.textMuted.copy(alpha = 0.5f),
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.debug_chaos_no_events),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}
