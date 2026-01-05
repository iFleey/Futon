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
package me.fleey.futon.ui.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.ButtonStyle
import me.fleey.futon.ui.designsystem.component.buttons.FutonButton
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingScreen(
  onComplete: () -> Unit,
  onExit: () -> Unit,
  viewModel: OnboardingViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  LaunchedEffect(Unit) {
    viewModel.onEvent(OnboardingUiEvent.StartOnboarding)
  }

  LaunchedEffect(uiState.isCompleted) {
    if (uiState.isCompleted) onComplete()
  }

  if (uiState.showExitConfirmation) {
    ExitConfirmationDialog(
      onConfirm = onExit,
      onDismiss = { viewModel.onEvent(OnboardingUiEvent.DismissExitConfirmation) },
    )
  }

  if (uiState.showSkipWarning) {
    SkipModelDeploymentDialog(
      onConfirm = { viewModel.onEvent(OnboardingUiEvent.SkipModelDeployment) },
      onDismiss = { viewModel.onEvent(OnboardingUiEvent.DismissSkipWarning) },
    )
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(FutonTheme.colors.background),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_logo),
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = Color.Unspecified,
      )

      Spacer(modifier = Modifier.height(24.dp))

      Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = FutonTheme.colors.textNormal,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = stringResource(R.string.onboarding_welcome_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = FutonTheme.colors.textMuted,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(40.dp))

      StepsCard(
        steps = OnboardingStep.entries,
        stepStates = uiState.stepStates,
        currentStep = uiState.currentStep,
      )

      Spacer(modifier = Modifier.height(32.dp))

      ActionButtons(
        uiState = uiState,
        onRetry = { viewModel.onEvent(OnboardingUiEvent.RetryCurrentStep) },
        onConfirm = { viewModel.onEvent(OnboardingUiEvent.ConfirmCurrentStep) },
        onComplete = { viewModel.onEvent(OnboardingUiEvent.CompleteOnboarding) },
        onExit = { viewModel.onEvent(OnboardingUiEvent.ShowExitConfirmation) },
        onSkip = { viewModel.onEvent(OnboardingUiEvent.ShowSkipWarning) },
      )
    }
  }
}

@Composable
private fun StepsCard(
  steps: List<OnboardingStep>,
  stepStates: Map<OnboardingStep, StepState>,
  currentStep: OnboardingStep,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    steps.forEachIndexed { index, step ->
      val state = stepStates[step] ?: StepState.Pending
      val isCurrent = step == currentStep

      val shape = when {
        steps.size == 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = 4.dp,
          bottomEnd = 4.dp,
        )

        index == steps.lastIndex -> RoundedCornerShape(
          topStart = 4.dp,
          topEnd = 4.dp,
          bottomStart = 16.dp,
          bottomEnd = 16.dp,
        )

        else -> RoundedCornerShape(4.dp)
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FutonTheme.colors.backgroundSecondary,
        shape = shape,
      ) {
        StepItem(step = step, state = state, isCurrent = isCurrent)
      }
    }
  }
}

@Composable
private fun StepItem(
  step: OnboardingStep,
  state: StepState,
  isCurrent: Boolean,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StepStatusIcon(state = state)

    Spacer(modifier = Modifier.width(14.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(step.titleRes),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        color = when {
          state.isCompleted -> FutonTheme.colors.statusPositive
          state.isFailed -> FutonTheme.colors.statusDanger
          isCurrent -> FutonTheme.colors.textNormal
          else -> FutonTheme.colors.textMuted
        },
      )

      Text(
        text = getStepSubtitle(step, state),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.isFailed) {
          FutonTheme.colors.statusDanger.copy(alpha = 0.8f)
        } else {
          FutonTheme.colors.textMuted
        },
      )
    }
  }
}

@Composable
private fun getStepSubtitle(step: OnboardingStep, state: StepState): String {
  return when (state) {
    is StepState.InProgress -> stringResource(step.inProgressMessageRes)
    is StepState.Success -> state.detailsRes?.let { stringResource(it) }
      ?: stringResource(step.successMessageRes)

    is StepState.Failed -> stringResource(state.reasonRes, *state.reasonArgs.toTypedArray())
    is StepState.WaitingForConfirmation -> stringResource(state.messageRes)
    is StepState.WaitingForReboot -> stringResource(state.messageRes)
    is StepState.Pending -> stringResource(step.descriptionRes)
  }
}

@Composable
private fun StepStatusIcon(state: StepState) {
  val backgroundColor = when {
    state.isCompleted -> FutonTheme.colors.statusPositive
    state.isFailed -> FutonTheme.colors.statusDanger
    state.isActive -> MaterialTheme.colorScheme.primary
    state.isWaiting -> FutonTheme.colors.statusWarning
    else -> MaterialTheme.colorScheme.surfaceVariant
  }

  val scale = remember { Animatable(1f) }

  LaunchedEffect(state) {
    if (state.isCompleted) {
      scale.animateTo(1.15f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
      scale.animateTo(1f)
    }
  }

  Box(
    modifier = Modifier
      .size(32.dp)
      .scale(scale.value)
      .clip(CircleShape)
      .background(backgroundColor),
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = state,
      transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
      label = "status",
    ) { targetState ->
      when {
        targetState.isCompleted -> Icon(
          imageVector = FutonIcons.Check,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = Color.White,
        )

        targetState.isFailed -> Icon(
          imageVector = FutonIcons.Close,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = Color.White,
        )

        targetState.isActive -> Spinner(modifier = Modifier.size(18.dp), color = Color.White)
        targetState.isWaiting -> Icon(
          imageVector = FutonIcons.Warning,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = Color.White,
        )

        else -> Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(FutonTheme.colors.textMuted),
        )
      }
    }
  }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier, color: Color) {
  val infiniteTransition = rememberInfiniteTransition(label = "spinner")
  val rotation by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
    label = "rotation",
  )

  Canvas(modifier = modifier) {
    val stroke = 2.dp.toPx()
    val radius = (size.minDimension - stroke) / 2

    drawCircle(
      color = color.copy(alpha = 0.25f),
      radius = radius,
      style = Stroke(width = stroke),
    )

    drawArc(
      color = color,
      startAngle = rotation - 90f,
      sweepAngle = 90f,
      useCenter = false,
      topLeft = Offset(stroke / 2, stroke / 2),
      size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
  }
}

@Composable
private fun ActionButtons(
  uiState: OnboardingUiState,
  onRetry: () -> Unit,
  onConfirm: () -> Unit,
  onComplete: () -> Unit,
  onExit: () -> Unit,
  onSkip: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when {
      uiState.allStepsCompleted -> {
        FutonButton(
          text = stringResource(R.string.onboarding_complete),
          onClick = onComplete,
          style = ButtonStyle.Primary,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      uiState.canConfirm -> {
        val confirmState = uiState.currentStepState as StepState.WaitingForConfirmation
        FutonButton(
          text = stringResource(confirmState.actionRes),
          onClick = onConfirm,
          style = ButtonStyle.Primary,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      uiState.canRetry -> {
        FutonButton(
          text = stringResource(R.string.action_retry),
          onClick = onRetry,
          style = ButtonStyle.Primary,
          modifier = Modifier.fillMaxWidth(),
        )
        if (uiState.canSkip) {
          FutonButton(
            text = stringResource(R.string.onboarding_model_skip_confirm),
            onClick = onSkip,
            style = ButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      uiState.needsReboot -> {
        FutonButton(
          text = stringResource(R.string.action_retry),
          onClick = onRetry,
          style = ButtonStyle.Secondary,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      uiState.canSkip -> {
        FutonButton(
          text = stringResource(R.string.onboarding_model_skip_confirm),
          onClick = onSkip,
          style = ButtonStyle.Secondary,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (uiState.isFatalError) {
      FutonButton(
        text = stringResource(R.string.onboarding_exit),
        onClick = onExit,
        style = ButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun ExitConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        tint = FutonTheme.colors.statusWarning,
      )
    },
    title = { Text(text = stringResource(R.string.onboarding_exit_confirm_title)) },
    text = { Text(text = stringResource(R.string.onboarding_exit_confirm_message)) },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(R.string.onboarding_exit))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
private fun SkipModelDeploymentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = FutonIcons.Warning,
        contentDescription = null,
        tint = FutonTheme.colors.statusWarning,
      )
    },
    title = { Text(text = stringResource(R.string.onboarding_model_skip_title)) },
    text = { Text(text = stringResource(R.string.onboarding_model_skip_message)) },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(R.string.onboarding_model_skip_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
