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
package me.fleey.futon.ui.feature.agent.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun AutomationProgressCard(
  currentStep: Int,
  maxSteps: Int,
  automationMode: AutomationMode,
  aiDecisionMode: AIDecisionMode,
  hotPathHits: Int,
  aiCalls: Int,
  loopDetected: Boolean,
  loopCount: Int,
  modifier: Modifier = Modifier,
  currentAction: String? = null,
) {
  val totalActions = hotPathHits + aiCalls
  val hotPathRate = if (totalActions > 0) (hotPathHits * 100) / totalActions else 0
  val progress = if (maxSteps > 0) currentStep.toFloat() / maxSteps else 0f

  val progressColor by animateColorAsState(
    targetValue = when {
      loopDetected -> FutonTheme.colors.statusDanger
      aiDecisionMode == AIDecisionMode.ANALYZING -> FutonTheme.colors.statusWarning
      else -> FutonTheme.colors.statusPositive
    },
    animationSpec = tween(300),
    label = "progressColor",
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
          AutomationModeIcon(mode = automationMode, aiDecisionMode = aiDecisionMode)
          Spacer(modifier = Modifier.width(FutonSizes.CardElementSpacing))
          Column {
            Text(
              text = stringResource(R.string.automation_progress_title),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.automation_progress_step, currentStep, maxSteps),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
            )
          }
        }

        HotPathRateChip(rate = hotPathRate)
      }

      Spacer(modifier = Modifier.height(FutonSizes.CardElementSpacing))

      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
          .fillMaxWidth()
          .height(FutonSizes.ProgressBarHeight)
          .clip(FutonShapes.ProgressBarShape),
        color = progressColor,
        trackColor = progressColor.copy(alpha = 0.2f),
      )

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        StatItem(
          label = stringResource(R.string.automation_progress_executed),
          value = totalActions.toString(),
        )
        StatItem(
          label = stringResource(R.string.automation_progress_hot_path_hits),
          value = hotPathHits.toString(),
        )
        StatItem(
          label = stringResource(R.string.automation_progress_ai_calls),
          value = aiCalls.toString(),
        )
      }

      AnimatedVisibility(
        visible = loopDetected,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        LoopWarning(loopCount = loopCount)
      }

      AnimatedVisibility(
        visible = aiDecisionMode != AIDecisionMode.IDLE && automationMode == AutomationMode.HYBRID,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        AIFallbackIndicator(mode = aiDecisionMode)
      }

      currentAction?.let { action ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.automation_progress_current_action, action),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

@Composable
private fun AutomationModeIcon(
  mode: AutomationMode,
  aiDecisionMode: AIDecisionMode,
) {
  val (icon, color) = when {
    aiDecisionMode == AIDecisionMode.ANALYZING -> Icons.Default.Psychology to FutonTheme.colors.statusWarning
    aiDecisionMode == AIDecisionMode.EXECUTING -> Icons.Default.AutoAwesome to MaterialTheme.colorScheme.primary
    mode == AutomationMode.HOT_PATH -> Icons.Default.Bolt to FutonTheme.colors.statusPositive
    mode == AutomationMode.AI -> Icons.Default.Psychology to MaterialTheme.colorScheme.primary
    else -> Icons.Default.AutoAwesome to FutonTheme.colors.statusWarning
  }

  val infiniteTransition = rememberInfiniteTransition(label = "iconPulse")
  val alpha by infiniteTransition.animateFloat(
    initialValue = 0.6f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(600, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "iconAlpha",
  )

  Box(
    modifier = Modifier
      .size(FutonSizes.LargeIconSize)
      .alpha(alpha),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = color,
      modifier = Modifier.size(FutonSizes.IconSize),
    )
  }
}

@Composable
private fun HotPathRateChip(rate: Int) {
  val color = when {
    rate >= 80 -> FutonTheme.colors.statusPositive
    rate >= 50 -> FutonTheme.colors.statusWarning
    else -> FutonTheme.colors.textMuted
  }

  Surface(
    shape = FutonShapes.ChipShape,
    color = color.copy(alpha = 0.15f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Bolt,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(14.dp),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = stringResource(R.string.automation_progress_hot_path_rate, rate),
        style = MaterialTheme.typography.labelSmall,
        color = color,
      )
    }
  }
}

@Composable
private fun StatItem(
  label: String,
  value: String,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = FutonTheme.colors.textNormal,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun LoopWarning(loopCount: Int) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp),
    shape = FutonShapes.CardShape,
    color = FutonTheme.colors.statusDanger.copy(alpha = 0.1f),
  ) {
    Row(
      modifier = Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val infiniteTransition = rememberInfiniteTransition(label = "warningPulse")
      val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(500, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
        label = "warningAlpha",
      )

      Icon(
        imageVector = Icons.Default.Loop,
        contentDescription = null,
        tint = FutonTheme.colors.statusDanger,
        modifier = Modifier
          .size(FutonSizes.SmallIconSize)
          .alpha(alpha),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          text = stringResource(R.string.automation_progress_loop_warning),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.statusDanger,
        )
        Text(
          text = stringResource(R.string.automation_progress_loop_message, loopCount),
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.statusDanger.copy(alpha = 0.8f),
        )
      }
    }
  }
}

@Composable
private fun AIFallbackIndicator(mode: AIDecisionMode) {
  val (text, color) = when (mode) {
    AIDecisionMode.ANALYZING -> stringResource(R.string.ai_decision_mode_analyzing) to FutonTheme.colors.statusWarning
    AIDecisionMode.EXECUTING -> stringResource(R.string.ai_decision_mode_executing) to MaterialTheme.colorScheme.primary
    else -> return
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp),
    shape = FutonShapes.CardShape,
    color = color.copy(alpha = 0.1f),
  ) {
    Row(
      modifier = Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Psychology,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(FutonSizes.SmallIconSize),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = stringResource(R.string.automation_progress_ai_fallback),
        style = MaterialTheme.typography.labelMedium,
        color = color,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "($text)",
        style = MaterialTheme.typography.bodySmall,
        color = color.copy(alpha = 0.8f),
      )
    }
  }
}
