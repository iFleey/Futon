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
package me.fleey.futon.ui.designsystem.component.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Stable
class FutonSnackbarState {
  var isVisible by mutableStateOf(false)
    private set

  var message by mutableStateOf("")
    private set

  var actionLabel by mutableStateOf<String?>(null)
    private set

  var leadingContent by mutableStateOf<(@Composable () -> Unit)?>(null)
    private set

  private var durationMs: Long = 4000L
  private var onAction: (() -> Unit)? = null
  private var onDismiss: (() -> Unit)? = null

  fun show(
    message: String,
    actionLabel: String? = null,
    durationMs: Long = 4000L,
    leadingContent: (@Composable () -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
  ) {
    this.message = message
    this.actionLabel = actionLabel
    this.durationMs = durationMs
    this.leadingContent = leadingContent
    this.onAction = onAction
    this.onDismiss = onDismiss
    this.isVisible = true
  }

  fun performAction() {
    onAction?.invoke()
    hide()
  }

  fun dismiss() {
    onDismiss?.invoke()
    hide()
  }

  internal fun getDurationMs(): Long = durationMs

  private fun hide() {
    isVisible = false
    onAction = null
    onDismiss = null
    leadingContent = null
  }
}

@Composable
fun rememberFutonSnackbarState(): FutonSnackbarState {
  return remember { FutonSnackbarState() }
}

@Composable
fun FutonSnackbarHost(
  state: FutonSnackbarState,
  modifier: Modifier = Modifier,
  autoDismiss: Boolean = true,
) {
  LaunchedEffect(state.isVisible, autoDismiss) {
    if (state.isVisible && autoDismiss) {
      delay(state.getDurationMs())
      if (state.isVisible) {
        state.dismiss()
      }
    }
  }

  AnimatedVisibility(
    visible = state.isVisible,
    enter = slideInVertically(
      initialOffsetY = { it },
      animationSpec = tween(200),
    ) + fadeIn(animationSpec = tween(200)),
    exit = slideOutVertically(
      targetOffsetY = { it },
      animationSpec = tween(150),
    ) + fadeOut(animationSpec = tween(150)),
    modifier = modifier,
  ) {
    FutonSnackbar(
      message = state.message,
      actionLabel = state.actionLabel,
      onAction = if (state.actionLabel != null) {
        { state.performAction() }
      } else null,
      leadingContent = state.leadingContent,
    )
  }
}

@Composable
fun FutonSnackbar(
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  leadingContent: @Composable (() -> Unit)? = null,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(8.dp),
    color = FutonTheme.colors.snackbarBackground,
    shadowElevation = 4.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      leadingContent?.invoke()

      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.snackbarText,
        modifier = Modifier.weight(1f),
      )

      if (actionLabel != null && onAction != null) {
        TextButton(onClick = onAction) {
          Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = FutonTheme.colors.snackbarAction,
          )
        }
      }
    }
  }
}
