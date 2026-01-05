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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.models.CapabilityFlags
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.theme.FutonTheme


/**
 * Compact connection status indicator for TopAppBar.
 *
 * Shows a colored dot with optional label indicating daemon connection state.
 * Clicking expands a dropdown with detailed status information.
 */
@Composable
fun ConnectionIndicator(
  state: DaemonState,
  modifier: Modifier = Modifier,
  showLabel: Boolean = true,
  onRetry: (() -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }

  val (color, isAnimating) = state.toIndicatorState()
  val animatedColor by animateColorAsState(
    targetValue = color,
    animationSpec = tween(300),
    label = "indicatorColor",
  )

  Box(modifier = modifier) {
    Row(
      modifier = Modifier
        .clip(FutonShapes.ChipShape)
        .clickable { expanded = true }
        .padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AnimatedDot(
        color = animatedColor,
        isAnimating = isAnimating,
      )
      if (showLabel) {
        Spacer(modifier = Modifier.width(6.dp))
        AnimatedContent(
          targetState = state.toLabel(),
          transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
          },
          label = "labelTransition",
        ) { label ->
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = animatedColor,
          )
        }
      }
    }

    ConnectionDropdownMenu(
      expanded = expanded,
      state = state,
      onDismiss = { expanded = false },
      onRetry = onRetry,
    )
  }
}

@Composable
private fun AnimatedDot(
  color: Color,
  isAnimating: Boolean,
  modifier: Modifier = Modifier,
) {
  val alpha = if (isAnimating) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
    infiniteTransition.animateFloat(
      initialValue = 0.4f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(600, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "dotAlpha",
    ).value
  } else {
    1f
  }

  Box(
    modifier = modifier
      .size(8.dp)
      .alpha(alpha)
      .clip(FutonShapes.StatusDotShape)
      .background(color),
  )
}

@Composable
private fun ConnectionDropdownMenu(
  expanded: Boolean,
  state: DaemonState,
  onDismiss: () -> Unit,
  onRetry: (() -> Unit)?,
) {
  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = stringResource(R.string.daemon_status_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = FutonTheme.colors.textMuted,
      )

      StatusDetailRow(state = state)

      when (state) {
        is DaemonState.Ready -> {
          ReadyDetails(version = state.version, capabilities = state.capabilities)
        }

        is DaemonState.Error -> {
          ErrorDetails(
            message = state.message,
            recoverable = state.recoverable,
            onRetry = {
              onDismiss()
              onRetry?.invoke()
            },
          )
        }

        else -> {}
      }
    }
  }
}

@Composable
private fun StatusDetailRow(state: DaemonState) {
  val (color, _) = state.toIndicatorState()
  val label = state.toLabel()

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(vertical = 4.dp),
  ) {
    Box(
      modifier = Modifier
        .size(10.dp)
        .clip(FutonShapes.StatusDotShape)
        .background(color),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = color,
    )
  }
}

@Composable
private fun ReadyDetails(version: Int, capabilities: Int) {
  Column(modifier = Modifier.padding(top = 4.dp)) {
    Text(
      text = "${stringResource(R.string.daemon_version_label)}: v$version",
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )

    val capList = CapabilityFlags.toReadableList(capabilities)
    if (capList.isNotEmpty()) {
      Text(
        text = "${stringResource(R.string.daemon_capabilities_label)}: ${
          capList.take(3).joinToString(", ")
        }${if (capList.size > 3) "..." else ""}",
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun ErrorDetails(
  message: String,
  recoverable: Boolean,
  onRetry: () -> Unit,
) {
  Column(modifier = Modifier.padding(top = 4.dp)) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.statusDanger,
      maxLines = 2,
    )

    if (recoverable) {
      DropdownMenuItem(
        text = {
          Text(
            text = stringResource(R.string.retry),
            color = MaterialTheme.colorScheme.primary,
          )
        },
        onClick = onRetry,
      )
    }
  }
}

@Composable
private fun DaemonState.toIndicatorState(): Pair<Color, Boolean> = when (this) {
  is DaemonState.Stopped -> FutonTheme.colors.textMuted to false
  is DaemonState.Starting,
  is DaemonState.Connecting,
  is DaemonState.Authenticating,
  is DaemonState.Reconciling,
    -> FutonTheme.colors.statusWarning to true

  is DaemonState.Ready -> FutonTheme.colors.statusPositive to false
  is DaemonState.Error -> FutonTheme.colors.statusDanger to false
}

@Composable
private fun DaemonState.toLabel(): String = when (this) {
  is DaemonState.Stopped -> stringResource(R.string.connection_disconnected)
  is DaemonState.Starting,
  is DaemonState.Connecting,
  is DaemonState.Authenticating,
  is DaemonState.Reconciling,
    -> stringResource(R.string.connection_connecting)

  is DaemonState.Ready -> stringResource(R.string.connection_connected)
  is DaemonState.Error -> stringResource(R.string.daemon_state_error)
}
