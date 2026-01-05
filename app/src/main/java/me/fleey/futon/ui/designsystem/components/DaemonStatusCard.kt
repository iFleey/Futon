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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.models.CapabilityFlags
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme


private data class StateVisuals(
  val icon: ImageVector,
  val color: @Composable () -> Color,
  val titleRes: Int,
  val showProgress: Boolean = false,
)

@Composable
private fun DaemonState.toVisuals(): StateVisuals = when (this) {
  is DaemonState.Stopped -> StateVisuals(
    icon = Icons.Default.Stop,
    color = { FutonTheme.colors.textMuted },
    titleRes = R.string.daemon_state_stopped,
  )

  is DaemonState.Starting -> StateVisuals(
    icon = Icons.Default.PlayArrow,
    color = { FutonTheme.colors.statusWarning },
    titleRes = R.string.daemon_state_starting,
    showProgress = true,
  )

  is DaemonState.Connecting -> StateVisuals(
    icon = Icons.Default.Link,
    color = { FutonTheme.colors.statusWarning },
    titleRes = R.string.daemon_state_connecting,
    showProgress = true,
  )

  is DaemonState.Authenticating -> StateVisuals(
    icon = Icons.Default.Lock,
    color = { FutonTheme.colors.statusWarning },
    titleRes = R.string.daemon_state_authenticating,
    showProgress = true,
  )

  is DaemonState.Reconciling -> StateVisuals(
    icon = Icons.Default.Sync,
    color = { FutonTheme.colors.statusWarning },
    titleRes = R.string.daemon_state_reconciling,
    showProgress = true,
  )

  is DaemonState.Ready -> StateVisuals(
    icon = Icons.Default.CheckCircle,
    color = { FutonTheme.colors.statusPositive },
    titleRes = R.string.daemon_state_ready,
  )

  is DaemonState.Error -> StateVisuals(
    icon = Icons.Default.Error,
    color = { FutonTheme.colors.statusDanger },
    titleRes = R.string.daemon_state_error,
  )
}

/**
 * Card displaying daemon connection state with animated transitions.
 *
 * Shows 7 states: Stopped, Starting, Connecting, Authenticating, Reconciling, Ready, Error.
 * Ready state displays version and capability flags.
 * Error state shows error message and retry button.
 */
@Composable
fun DaemonStatusCard(
  state: DaemonState,
  modifier: Modifier = Modifier,
  onRetry: (() -> Unit)? = null,
) {
  val visuals = state.toVisuals()
  val statusColor by animateColorAsState(
    targetValue = visuals.color(),
    animationSpec = tween(300),
    label = "statusColor",
  )

  Surface(
    modifier = modifier.fillMaxWidth(),
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
          AnimatedStatusIcon(
            icon = visuals.icon,
            color = statusColor,
            showProgress = visuals.showProgress,
          )
          Spacer(modifier = Modifier.width(FutonSizes.CardElementSpacing))
          Column {
            Text(
              text = stringResource(R.string.daemon_status_title),
              style = MaterialTheme.typography.titleMedium,
            )
            AnimatedContent(
              targetState = visuals.titleRes,
              transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
              },
              label = "stateText",
            ) { titleRes ->
              Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .size(FutonSizes.StatusDotSize)
            .clip(FutonShapes.StatusDotShape)
            .background(statusColor),
        )
      }

      AnimatedVisibility(
        visible = state is DaemonState.Ready,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        if (state is DaemonState.Ready) {
          ReadyStateDetails(
            version = state.version,
            capabilities = state.capabilities,
          )
        }
      }

      AnimatedVisibility(
        visible = state is DaemonState.Error,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        if (state is DaemonState.Error) {
          ErrorStateDetails(
            message = state.message,
            recoverable = state.recoverable,
            onRetry = onRetry,
          )
        }
      }
    }
  }
}

@Composable
private fun AnimatedStatusIcon(
  icon: ImageVector,
  color: Color,
  showProgress: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(FutonSizes.LargeIconSize),
    contentAlignment = Alignment.Center,
  ) {
    if (showProgress) {
      val infiniteTransition = rememberInfiniteTransition(label = "pulse")
      val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(800, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
      )
      CircularProgressIndicator(
        modifier = Modifier
          .size(FutonSizes.LargeIconSize)
          .alpha(alpha),
        color = color,
        strokeWidth = 2.dp,
      )
    }
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = color,
      modifier = Modifier.size(FutonSizes.IconSize),
    )
  }
}

@Composable
private fun ReadyStateDetails(
  version: Int,
  capabilities: Int,
) {
  Column(modifier = Modifier.padding(top = FutonSizes.CardElementSpacing)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.daemon_version_label),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Text(
        text = "v$version",
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textNormal,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val capabilityList = CapabilityFlags.toReadableList(capabilities)
    if (capabilityList.isNotEmpty()) {
      Text(
        text = stringResource(R.string.daemon_capabilities_label),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.height(4.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        capabilityList.forEach { capability ->
          CapabilityChip(text = capability)
        }
      }
    }
  }
}

@Composable
private fun CapabilityChip(text: String) {
  Surface(
    shape = FutonShapes.ChipShape,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun ErrorStateDetails(
  message: String,
  recoverable: Boolean,
  onRetry: (() -> Unit)?,
) {
  Column(modifier = Modifier.padding(top = FutonSizes.CardElementSpacing)) {
    Surface(
      shape = FutonShapes.CardShape,
      color = FutonTheme.colors.statusDanger.copy(alpha = 0.1f),
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.statusDanger,
        modifier = Modifier.padding(8.dp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (recoverable && onRetry != null) {
      Spacer(modifier = Modifier.height(8.dp))
      TextButton(
        onClick = onRetry,
        modifier = Modifier.align(Alignment.End),
      ) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = null,
          modifier = Modifier.size(FutonSizes.SmallIconSize),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = stringResource(R.string.retry))
      }
    }
  }
}
