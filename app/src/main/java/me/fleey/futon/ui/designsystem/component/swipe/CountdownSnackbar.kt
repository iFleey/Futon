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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.fleey.futon.ui.designsystem.component.feedback.FutonSnackbarHost
import me.fleey.futon.ui.designsystem.component.feedback.FutonSnackbarState
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Stable
class CountdownSnackbarState {
  internal val snackbarState = FutonSnackbarState()

  var progress by mutableFloatStateOf(1f)
    private set

  private var remainingSeconds by mutableIntStateOf(0)

  private var durationSeconds: Int = 5
  private var onDismissCallback: (() -> Unit)? = null

  val isVisible: Boolean get() = snackbarState.isVisible

  fun show(
    message: String,
    actionLabel: String,
    durationSeconds: Int = 5,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    this.durationSeconds = durationSeconds
    this.remainingSeconds = durationSeconds
    this.progress = 1f
    this.onDismissCallback = onDismiss

    snackbarState.show(
      message = message,
      actionLabel = actionLabel,
      durationMs = Long.MAX_VALUE,
      leadingContent = { CountdownIndicator(progress, remainingSeconds) },
      onAction = onAction,
      onDismiss = null,
    )
  }

  fun performAction() {
    snackbarState.performAction()
    reset()
  }

  fun dismiss() {
    snackbarState.dismiss()
    onDismissCallback?.invoke()
    reset()
  }

  internal fun updateProgress(progress: Float, remainingSeconds: Int) {
    this.progress = progress
    this.remainingSeconds = remainingSeconds
  }

  internal fun getDurationMs(): Long = durationSeconds * 1000L

  private fun reset() {
    progress = 1f
    remainingSeconds = 0
    onDismissCallback = null
  }
}

@Composable
fun rememberCountdownSnackbarState(): CountdownSnackbarState {
  return remember { CountdownSnackbarState() }
}

@Composable
fun CountdownSnackbarHost(
  state: CountdownSnackbarState,
  modifier: Modifier = Modifier,
) {
  LaunchedEffect(state.isVisible) {
    if (state.isVisible) {
      val durationMs = state.getDurationMs()
      val startTime = System.currentTimeMillis()

      while (state.isVisible) {
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (durationMs - elapsed).coerceAtLeast(0)
        val progress = remaining.toFloat() / durationMs
        val remainingSeconds = ((remaining + 999) / 1000).toInt()

        state.updateProgress(progress, remainingSeconds)

        if (remaining <= 0) {
          state.dismiss()
          break
        }
        delay(16)
      }
    }
  }

  FutonSnackbarHost(
    state = state.snackbarState,
    modifier = modifier,
    autoDismiss = false,
  )
}

@Composable
private fun CountdownIndicator(
  progress: Float,
  remainingSeconds: Int,
  modifier: Modifier = Modifier,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.size(28.dp),
  ) {
    CircularProgressIndicator(
      progress = { progress },
      modifier = Modifier.size(24.dp),
      color = FutonTheme.colors.snackbarAction,
      trackColor = FutonTheme.colors.snackbarAction.copy(alpha = 0.2f),
      strokeWidth = 2.5.dp,
      strokeCap = StrokeCap.Round,
    )
    Text(
      text = remainingSeconds.toString(),
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.snackbarText,
    )
  }
}
