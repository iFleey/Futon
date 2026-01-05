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
package me.fleey.futon.ui.designsystem.component.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.domain.automation.models.ExecutionPhase
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun TypingIndicator(
  phase: ExecutionPhase,
  modifier: Modifier = Modifier,
) {
  val phaseText = when (phase) {
    ExecutionPhase.CAPTURING_SCREENSHOT -> stringResource(R.string.phase_capturing_screenshot)
    ExecutionPhase.ANALYZING_WITH_AI -> stringResource(R.string.phase_analyzing_with_ai)
    ExecutionPhase.EXECUTING_ACTION -> stringResource(R.string.phase_executing_action)
    ExecutionPhase.WAITING -> stringResource(R.string.phase_waiting)
    ExecutionPhase.RETRYING -> stringResource(R.string.phase_retrying)
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(20.dp))
        .background(FutonTheme.colors.backgroundSecondary)
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AnimatedDots()

      Text(
        text = phaseText,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun AnimatedDots() {
  val infiniteTransition = rememberInfiniteTransition(label = "typing_dots")

  val dot1Alpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 500, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "dot1",
  )

  val dot2Alpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 500, delayMillis = 150, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "dot2",
  )

  val dot3Alpha by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 500, delayMillis = 300, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "dot3",
  )

  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Dot(alpha = dot1Alpha)
    Dot(alpha = dot2Alpha)
    Dot(alpha = dot3Alpha)
  }
}

@Composable
private fun Dot(alpha: Float) {
  Box(
    modifier = Modifier
      .size(6.dp)
      .alpha(alpha)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primary),
  )
}
