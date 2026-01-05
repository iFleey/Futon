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
package me.fleey.futon.ui.designsystem.component.swipe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.fleey.futon.R

enum class SwipeActionMode {
  Immediate,
  WithUndo
}

data class SwipeUndoConfig(
  val messageResId: Int = R.string.swipe_deleted_item,
  val message: String? = null,
  val actionLabelResId: Int = R.string.action_undo,
  val durationSeconds: Int = 5,
)

@Composable
fun SwipeActionBox(
  modifier: Modifier = Modifier,
  startActions: List<SwipeAction> = emptyList(),
  endActions: List<SwipeAction> = emptyList(),
  enabled: Boolean = true,
  shape: Shape = RoundedCornerShape(16.dp),
  enableHapticFeedback: Boolean = true,
  actionMode: SwipeActionMode = SwipeActionMode.Immediate,
  undoConfig: SwipeUndoConfig? = null,
  snackbarHostState: SnackbarHostState? = null,
  countdownSnackbarState: CountdownSnackbarState? = null,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val hapticFeedback = LocalHapticFeedback.current

  var isVisible by rememberSaveable { mutableStateOf(true) }
  var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
  var hasTriggeredHaptic by remember { mutableStateOf(false) }

  val enableStart = enabled && startActions.isNotEmpty()
  val enableEnd = enabled && endActions.isNotEmpty()

  val undoMessage = undoConfig?.message
    ?: stringResource(undoConfig?.messageResId ?: R.string.swipe_deleted_item)
  val undoActionLabel = stringResource(undoConfig?.actionLabelResId ?: R.string.action_undo)
  val durationSeconds = undoConfig?.durationSeconds ?: 5

  val swipeToDismissBoxState = rememberSwipeToDismissBoxState(
    positionalThreshold = { totalDistance -> totalDistance * 0.4f },
  )

  LaunchedEffect(swipeToDismissBoxState.currentValue) {
    when (swipeToDismissBoxState.currentValue) {
      SwipeToDismissBoxValue.StartToEnd -> {
        if (enableStart) {
          startActions.firstOrNull()?.let { action ->
            if (actionMode == SwipeActionMode.WithUndo) {
              isVisible = false
              pendingAction = action.onAction
              if (countdownSnackbarState != null) {
                countdownSnackbarState.show(
                  message = undoMessage,
                  actionLabel = undoActionLabel,
                  durationSeconds = durationSeconds,
                  onAction = {
                    isVisible = true
                    pendingAction = null
                    scope.launch {
                      delay(50)
                      swipeToDismissBoxState.reset()
                    }
                  },
                  onDismiss = {
                    pendingAction?.invoke()
                    pendingAction = null
                  },
                )
              } else {
                delay(durationSeconds * 1000L)
                pendingAction?.invoke()
                pendingAction = null
              }
            } else {
              action.onAction()
              swipeToDismissBoxState.reset()
            }
          }
        }
      }

      SwipeToDismissBoxValue.EndToStart -> {
        if (enableEnd) {
          endActions.firstOrNull()?.let { action ->
            if (actionMode == SwipeActionMode.WithUndo) {
              isVisible = false
              pendingAction = action.onAction
              if (countdownSnackbarState != null) {
                countdownSnackbarState.show(
                  message = undoMessage,
                  actionLabel = undoActionLabel,
                  durationSeconds = durationSeconds,
                  onAction = {
                    isVisible = true
                    pendingAction = null
                    scope.launch {
                      delay(50)
                      swipeToDismissBoxState.reset()
                    }
                  },
                  onDismiss = {
                    pendingAction?.invoke()
                    pendingAction = null
                  },
                )
              } else {
                delay(durationSeconds * 1000L)
                pendingAction?.invoke()
                pendingAction = null
              }
            } else {
              action.onAction()
              swipeToDismissBoxState.reset()
            }
          }
        }
      }

      SwipeToDismissBoxValue.Settled -> {}
    }
  }

  val progress = swipeToDismissBoxState.progress
  val targetValue = swipeToDismissBoxState.targetValue

  LaunchedEffect(targetValue, progress) {
    if (enableHapticFeedback) {
      val isActive = targetValue != SwipeToDismissBoxValue.Settled && progress >= 0.4f
      if (isActive && !hasTriggeredHaptic) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        hasTriggeredHaptic = true
      } else if (!isActive) {
        hasTriggeredHaptic = false
      }
    }
  }

  AnimatedVisibility(
    visible = isVisible,
    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
  ) {
    SwipeToDismissBox(
      state = swipeToDismissBoxState,
      modifier = modifier.clip(shape),
      enableDismissFromStartToEnd = enableStart,
      enableDismissFromEndToStart = enableEnd,
      backgroundContent = {
        SwipeBackground(
          dismissDirection = swipeToDismissBoxState.dismissDirection,
          progress = progress,
          startAction = startActions.firstOrNull(),
          endAction = endActions.firstOrNull(),
        )
      },
    ) {
      content()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
  dismissDirection: SwipeToDismissBoxValue,
  progress: Float,
  startAction: SwipeAction?,
  endAction: SwipeAction?,
) {
  val action = when (dismissDirection) {
    SwipeToDismissBoxValue.StartToEnd -> startAction
    SwipeToDismissBoxValue.EndToStart -> endAction
    SwipeToDismissBoxValue.Settled -> null
  }

  val alignment = when (dismissDirection) {
    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
    SwipeToDismissBoxValue.Settled -> Alignment.CenterEnd
  }

  val backgroundColor = action?.backgroundColor ?: Color.Transparent
  val animatedColor = lerp(Color.Transparent, backgroundColor, progress.coerceIn(0f, 1f))

  val iconScale = (0.5f + progress * 0.5f).coerceIn(0.5f, 1f)
  val iconAlpha = progress.coerceIn(0f, 1f)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(animatedColor),
    contentAlignment = alignment,
  ) {
    action?.let {
      Icon(
        imageVector = it.icon,
        contentDescription = null,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .size(24.dp)
          .graphicsLayer {
            scaleX = iconScale
            scaleY = iconScale
            alpha = iconAlpha
          },
        tint = it.iconTint,
      )
    }
  }
}
