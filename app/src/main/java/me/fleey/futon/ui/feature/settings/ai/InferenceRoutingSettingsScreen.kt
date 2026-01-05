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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import me.fleey.futon.R
import me.fleey.futon.data.ai.routing.InferenceSource
import me.fleey.futon.data.ai.routing.RoutingStrategy
import me.fleey.futon.data.ai.routing.SourceAvailability
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.component.settings.SettingsRadioItem
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun InferenceRoutingSettingsScreen(
  onBack: () -> Unit,
  viewModel: InferenceRoutingSettingsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.settings_inference_routing),
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
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      SourcePrioritySection(
        priority = uiState.priority,
        enabledSources = uiState.enabledSources,
        availability = uiState.availability,
        lastUsedSource = uiState.lastUsedSource,
        onReorder = { from, to ->
          viewModel.onEvent(InferenceRoutingUiEvent.ReorderSources(from, to))
        },
        onToggle = { source, enabled ->
          viewModel.onEvent(InferenceRoutingUiEvent.ToggleSource(source, enabled))
        },
      )

      RoutingStrategySection(
        currentStrategy = uiState.strategy,
        onStrategyChange = { viewModel.onEvent(InferenceRoutingUiEvent.SetStrategy(it)) },
      )

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun SourcePrioritySection(
  priority: List<InferenceSource>,
  enabledSources: Map<InferenceSource, Boolean>,
  availability: Map<InferenceSource, SourceAvailability>,
  lastUsedSource: InferenceSource?,
  onReorder: (Int, Int) -> Unit,
  onToggle: (InferenceSource, Boolean) -> Unit,
) {
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current

  val itemHeightPx = with(density) { 74.dp.toPx() }

  var localList by remember { mutableStateOf(priority) }
  var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
  var draggedItemInitialIndex by remember { mutableStateOf<Int?>(null) }
  var dragOffsetY by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(priority) {
    if (draggedItemIndex == null) {
      localList = priority
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = stringResource(R.string.inference_source_priority),
      style = MaterialTheme.typography.titleMedium,
      color = FutonTheme.colors.textMuted,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FutonTheme.colors.backgroundSecondary,
        shape = RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = 4.dp,
          bottomEnd = 4.dp,
        ),
      ) {
        Text(
          text = stringResource(R.string.inference_source_priority_description),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }

      localList.forEachIndexed { index, source ->
        val sourceAvailability = availability[source] ?: SourceAvailability.Unknown
        val isDragging = draggedItemIndex == index
        val isLast = index == localList.lastIndex

        val shape = if (isLast) {
          RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        } else {
          RoundedCornerShape(4.dp)
        }

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
              if (isDragging) {
                translationY = dragOffsetY
                scaleX = 1.03f
                scaleY = 1.03f
                shadowElevation = 12.dp.toPx()
              }
            }
            .pointerInput(Unit) {
              detectDragGesturesAfterLongPress(
                onDragStart = {
                  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                  draggedItemIndex = index
                  draggedItemInitialIndex = index
                  dragOffsetY = 0f
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  dragOffsetY += dragAmount.y

                  val currentIdx = draggedItemIndex ?: return@detectDragGesturesAfterLongPress
                  val threshold = itemHeightPx * 0.5f

                  if (dragOffsetY > threshold && currentIdx < localList.lastIndex) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    localList = localList.toMutableList().apply {
                      add(currentIdx + 1, removeAt(currentIdx))
                    }
                    draggedItemIndex = currentIdx + 1
                    dragOffsetY -= itemHeightPx
                  } else if (dragOffsetY < -threshold && currentIdx > 0) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    localList = localList.toMutableList().apply {
                      add(currentIdx - 1, removeAt(currentIdx))
                    }
                    draggedItemIndex = currentIdx - 1
                    dragOffsetY += itemHeightPx
                  }
                },
                onDragEnd = {
                  val fromIdx = draggedItemInitialIndex
                  val toIdx = draggedItemIndex
                  draggedItemIndex = null
                  draggedItemInitialIndex = null
                  dragOffsetY = 0f

                  if (fromIdx != null && toIdx != null && fromIdx != toIdx) {
                    onReorder(fromIdx, toIdx)
                  }
                },
                onDragCancel = {
                  draggedItemIndex = null
                  draggedItemInitialIndex = null
                  dragOffsetY = 0f
                  localList = priority
                },
              )
            },
        ) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FutonTheme.colors.backgroundSecondary,
            shape = shape,
          ) {
            SourcePriorityItem(
              source = source,
              isEnabled = enabledSources[source] ?: true,
              availability = sourceAvailability,
              isLastUsed = source == lastUsedSource,
              isDragging = isDragging,
              onToggle = { onToggle(source, it) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SourcePriorityItem(
  source: InferenceSource,
  isEnabled: Boolean,
  availability: SourceAvailability,
  isLastUsed: Boolean,
  isDragging: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  val sourceName = when (source) {
    is InferenceSource.CloudProvider -> source.displayName
    InferenceSource.LocalModel -> stringResource(R.string.inference_source_local)
  }

  val availabilityColor = when (availability) {
    is SourceAvailability.Available -> FutonTheme.colors.statusPositive
    is SourceAvailability.Unavailable -> FutonTheme.colors.statusDanger
    SourceAvailability.Unknown -> FutonTheme.colors.textMuted
  }

  val availabilityText = when (availability) {
    is SourceAvailability.Available -> stringResource(R.string.inference_available)
    is SourceAvailability.Unavailable -> stringResource(R.string.inference_unavailable)
    SourceAvailability.Unknown -> stringResource(R.string.inference_unknown)
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(72.dp)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = FutonIcons.DragHandle,
      contentDescription = stringResource(R.string.action_drag_to_reorder),
      tint = if (isDragging) FutonTheme.colors.interactiveNormal else FutonTheme.colors.textMuted,
      modifier = Modifier.size(24.dp),
    )

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = sourceName,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
        )
        if (isLastUsed) {
          Spacer(modifier = Modifier.width(8.dp))
          Surface(
            color = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = stringResource(R.string.inference_last_used_badge),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusPositive,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(availabilityColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = availabilityText,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }

    FutonSwitch(
      checked = isEnabled,
      onCheckedChange = onToggle,
    )
  }
}

@Composable
private fun RoutingStrategySection(
  currentStrategy: RoutingStrategy,
  onStrategyChange: (RoutingStrategy) -> Unit,
) {
  SettingsGroup(title = stringResource(R.string.inference_routing_strategy)) {
    item {
      SettingsRadioItem(
        title = stringResource(R.string.inference_strategy_priority),
        description = stringResource(R.string.inference_strategy_priority_description),
        selected = currentStrategy is RoutingStrategy.PriorityOrder,
        onClick = { onStrategyChange(RoutingStrategy.PriorityOrder) },
        leadingIcon = FutonIcons.Steps,
      )
    }
    item {
      SettingsRadioItem(
        title = stringResource(R.string.inference_strategy_cost),
        description = stringResource(R.string.inference_strategy_cost_description),
        selected = currentStrategy is RoutingStrategy.CostOptimized,
        onClick = { onStrategyChange(RoutingStrategy.CostOptimized) },
        leadingIcon = FutonIcons.Token,
      )
    }
    item {
      SettingsRadioItem(
        title = stringResource(R.string.inference_strategy_latency),
        description = stringResource(R.string.inference_strategy_latency_description),
        selected = currentStrategy is RoutingStrategy.LatencyOptimized,
        onClick = { onStrategyChange(RoutingStrategy.LatencyOptimized) },
        leadingIcon = FutonIcons.Speed,
      )
    }
    item {
      SettingsRadioItem(
        title = stringResource(R.string.inference_strategy_reliability),
        description = stringResource(R.string.inference_strategy_reliability_description),
        selected = currentStrategy is RoutingStrategy.ReliabilityOptimized,
        onClick = { onStrategyChange(RoutingStrategy.ReliabilityOptimized) },
        leadingIcon = FutonIcons.Security,
      )
    }
  }
}
