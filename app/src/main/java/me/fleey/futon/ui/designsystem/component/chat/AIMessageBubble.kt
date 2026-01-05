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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.agent.models.AIResponseMetadata
import me.fleey.futon.ui.feature.agent.models.AIResponseType
import sv.lib.squircleshape.SquircleShape

private val LARGE_RADIUS = 20.dp
private val SMALL_RADIUS = 4.dp

private val SingleShape = SquircleShape(LARGE_RADIUS, LARGE_RADIUS, SMALL_RADIUS, LARGE_RADIUS)
private val FirstShape = SquircleShape(LARGE_RADIUS, LARGE_RADIUS, SMALL_RADIUS, LARGE_RADIUS)
private val MiddleShape = SquircleShape(SMALL_RADIUS, LARGE_RADIUS, SMALL_RADIUS, LARGE_RADIUS)
private val LastShape = SquircleShape(SMALL_RADIUS, LARGE_RADIUS, LARGE_RADIUS, LARGE_RADIUS)

private fun getAIBubbleShape(position: MessagePosition): Shape {
  return when (position) {
    MessagePosition.SINGLE -> SingleShape
    MessagePosition.FIRST -> FirstShape
    MessagePosition.MIDDLE -> MiddleShape
    MessagePosition.LAST -> LastShape
  }
}

@Immutable
data class AIResponseStyling(
  val backgroundColor: Color,
  val textColor: Color,
  val iconTint: Color,
  val icon: ImageVector,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AIMessageBubble(
  type: AIResponseType,
  content: String,
  metadata: AIResponseMetadata?,
  timestamp: Long,
  position: MessagePosition = MessagePosition.SINGLE,
  onRetry: () -> Unit,
  onLongPress: () -> Unit,
  onViewLog: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val hapticFeedback = LocalHapticFeedback.current
  val styling = getAIResponseStyling(type)
  val timeText = remember(timestamp) { formatMessageTime(timestamp) }
  val shape = remember(position) { getAIBubbleShape(position) }

  val isResultBubble =
    type == AIResponseType.RESULT_FAILURE || type == AIResponseType.RESULT_SUCCESS

  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.CenterStart,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(0.85f)
        .clip(shape)
        .background(styling.backgroundColor)
        .combinedClickable(
          enabled = true,
          onClick = { if (isResultBubble) onViewLog?.invoke() },
          onLongClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
          },
          role = Role.Button,
        )
        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
      when (type) {
        AIResponseType.THINKING -> ThinkingContent(content, styling, timeText)
        AIResponseType.REASONING -> ReasoningContent(content, styling, timeText)
        AIResponseType.ACTION -> ActionContent(content, metadata, styling, timeText)
        AIResponseType.STEP_PROGRESS -> StepProgressContent(content, metadata, styling, timeText)
        AIResponseType.RESULT_SUCCESS -> ResultSuccessContent(content, styling, timeText)
        AIResponseType.RESULT_FAILURE -> ResultFailureContent(
          content,
          metadata,
          styling,
          onRetry,
          timeText,
        )

        AIResponseType.ERROR -> ErrorContent(content, metadata, styling, onRetry, timeText)
      }
    }
  }
}

@Composable
private fun MessageTimestamp(
  timeText: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Text(
    text = timeText,
    style = MaterialTheme.typography.labelSmall.copy(
      fontSize = 11.sp,
      lineHeight = 14.sp,
    ),
    color = color.copy(alpha = 0.6f),
    textAlign = TextAlign.End,
    modifier = modifier,
  )
}

@Composable
private fun ThinkingContent(content: String, styling: AIResponseStyling, timeText: String) {
  Row(
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f, fill = false),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )
      Text(
        text = content,
        style = MaterialTheme.typography.bodyMedium,
        color = styling.textColor,
      )
    }
    MessageTimestamp(timeText = timeText, color = styling.textColor)
  }
}

@Composable
private fun ReasoningContent(content: String, styling: AIResponseStyling, timeText: String) {
  var isExpanded by remember { mutableStateOf(false) }

  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { isExpanded = !isExpanded }
        .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )
      Text(
        text = stringResource(R.string.ai_reasoning),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = styling.textColor,
        modifier = Modifier.weight(1f),
      )
      Icon(
        imageVector = if (isExpanded) FutonIcons.ExpandLess else FutonIcons.ExpandMore,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = styling.iconTint.copy(alpha = 0.6f),
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Text(
        text = content,
        style = MaterialTheme.typography.bodySmall,
        color = styling.textColor.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      MessageTimestamp(timeText = timeText, color = styling.textColor)
    }
  }
}

@Composable
private fun ActionContent(
  content: String,
  metadata: AIResponseMetadata?,
  styling: AIResponseStyling,
  timeText: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )

      metadata?.actionType?.let { actionType ->
        Text(
          text = actionType,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = styling.textColor,
        )
      }

      metadata?.isSuccess?.let { isSuccess ->
        val statusColor =
          if (isSuccess) FutonTheme.colors.statusPositive else FutonTheme.colors.statusDanger
        val statusIcon = if (isSuccess) FutonIcons.Success else FutonIcons.Error

        Icon(
          imageVector = statusIcon,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = statusColor,
        )
      }
    }

    metadata?.actionParams?.let { params ->
      Text(
        text = params,
        style = MaterialTheme.typography.bodySmall,
        color = styling.textColor.copy(alpha = 0.7f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      metadata?.durationMs?.let { duration ->
        Text(
          text = stringResource(R.string.duration_ms, duration),
          style = MaterialTheme.typography.labelSmall,
          color = styling.textColor.copy(alpha = 0.5f),
        )
      } ?: Spacer(modifier = Modifier.width(1.dp))
      MessageTimestamp(timeText = timeText, color = styling.textColor)
    }
  }
}

@Composable
private fun StepProgressContent(
  content: String,
  metadata: AIResponseMetadata?,
  styling: AIResponseStyling,
  timeText: String,
) {
  Row(
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f, fill = false),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )

      val stepText = if (metadata?.stepNumber != null && metadata.maxSteps != null) {
        stringResource(R.string.step_progress, metadata.stepNumber, metadata.maxSteps)
      } else {
        content
      }

      Text(
        text = stepText,
        style = MaterialTheme.typography.bodyMedium,
        color = styling.textColor,
      )
    }
    MessageTimestamp(timeText = timeText, color = styling.textColor)
  }
}

@Composable
private fun ResultSuccessContent(content: String, styling: AIResponseStyling, timeText: String) {
  Row(
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f, fill = false),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )
      Text(
        text = stringResource(R.string.result_success),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = styling.textColor,
      )
    }
    MessageTimestamp(timeText = timeText, color = styling.textColor)
  }
}

@Composable
private fun ResultFailureContent(
  content: String,
  metadata: AIResponseMetadata?,
  styling: AIResponseStyling,
  onRetry: () -> Unit,
  timeText: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )
      Text(
        text = stringResource(R.string.result_failure),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = styling.textColor,
      )
    }

    if (content.isNotBlank()) {
      Text(
        text = content,
        style = MaterialTheme.typography.bodySmall,
        color = styling.textColor.copy(alpha = 0.8f),
      )
    }

    metadata?.suggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        suggestions.forEach { suggestion ->
          Text(
            text = "• $suggestion",
            style = MaterialTheme.typography.labelSmall,
            color = styling.textColor.copy(alpha = 0.7f),
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RetryButton(onClick = onRetry, tint = styling.iconTint)
      MessageTimestamp(timeText = timeText, color = styling.textColor)
    }
  }
}

@Composable
private fun ErrorContent(
  content: String,
  metadata: AIResponseMetadata?,
  styling: AIResponseStyling,
  onRetry: () -> Unit,
  timeText: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = styling.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = styling.iconTint,
      )
      Text(
        text = content,
        style = MaterialTheme.typography.bodyMedium,
        color = styling.textColor,
      )
    }

    metadata?.suggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        suggestions.forEach { suggestion ->
          Text(
            text = "• $suggestion",
            style = MaterialTheme.typography.labelSmall,
            color = styling.textColor.copy(alpha = 0.7f),
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RetryButton(onClick = onRetry, tint = styling.iconTint)
      MessageTimestamp(timeText = timeText, color = styling.textColor)
    }
  }
}

@Composable
private fun RetryButton(onClick: () -> Unit, tint: Color) {
  Surface(
    onClick = onClick,
    shape = FutonShapes.ButtonShape,
    color = tint.copy(alpha = 0.15f),
    contentColor = tint,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = FutonIcons.Retry,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = stringResource(R.string.action_retry),
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

@Composable
fun getAIResponseStyling(type: AIResponseType): AIResponseStyling {
  return when (type) {
    AIResponseType.THINKING -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.backgroundSecondary,
      textColor = FutonTheme.colors.textMuted,
      iconTint = FutonTheme.colors.textMuted,
      icon = FutonIcons.AI,
    )

    AIResponseType.REASONING -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.backgroundSecondary,
      textColor = FutonTheme.colors.textNormal,
      iconTint = MaterialTheme.colorScheme.primary,
      icon = FutonIcons.Learning,
    )

    AIResponseType.ACTION -> AIResponseStyling(
      backgroundColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
      textColor = MaterialTheme.colorScheme.onTertiaryContainer,
      iconTint = MaterialTheme.colorScheme.tertiary,
      icon = FutonIcons.Play,
    )

    AIResponseType.STEP_PROGRESS -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.backgroundSecondary,
      textColor = FutonTheme.colors.textNormal,
      iconTint = FutonTheme.colors.textMuted,
      icon = FutonIcons.Steps,
    )

    AIResponseType.RESULT_SUCCESS -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.statusPositive.copy(alpha = 0.12f),
      textColor = FutonTheme.colors.statusPositive,
      iconTint = FutonTheme.colors.statusPositive,
      icon = FutonIcons.Success,
    )

    AIResponseType.RESULT_FAILURE -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.statusDanger.copy(alpha = 0.12f),
      textColor = FutonTheme.colors.statusDanger,
      iconTint = FutonTheme.colors.statusDanger,
      icon = FutonIcons.Error,
    )

    AIResponseType.ERROR -> AIResponseStyling(
      backgroundColor = FutonTheme.colors.statusWarning.copy(alpha = 0.12f),
      textColor = FutonTheme.colors.statusWarning,
      iconTint = FutonTheme.colors.statusWarning,
      icon = FutonIcons.Warning,
    )
  }
}
