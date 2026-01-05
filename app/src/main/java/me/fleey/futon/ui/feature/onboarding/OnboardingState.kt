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

import androidx.annotation.StringRes
import me.fleey.futon.R

enum class OnboardingStep(
  @param:StringRes val titleRes: Int,
  @param:StringRes val descriptionRes: Int,
  @param:StringRes val inProgressMessageRes: Int,
  @param:StringRes val successMessageRes: Int,
  val isSkippable: Boolean = false,
) {
  ROOT_CHECK(
    titleRes = R.string.onboarding_step_root_title,
    descriptionRes = R.string.onboarding_step_root_description,
    inProgressMessageRes = R.string.onboarding_step_root_in_progress,
    successMessageRes = R.string.onboarding_step_root_success,
  ),
  DAEMON_DEPLOY(
    titleRes = R.string.onboarding_step_deploy_title,
    descriptionRes = R.string.onboarding_step_deploy_description,
    inProgressMessageRes = R.string.onboarding_step_deploy_in_progress,
    successMessageRes = R.string.onboarding_step_deploy_success,
  ),
  MODEL_DEPLOY(
    titleRes = R.string.onboarding_step_model_title,
    descriptionRes = R.string.onboarding_step_model_description,
    inProgressMessageRes = R.string.onboarding_step_model_in_progress,
    successMessageRes = R.string.onboarding_step_model_success,
    isSkippable = true,
  ),
  KEY_GENERATION(
    titleRes = R.string.onboarding_step_key_title,
    descriptionRes = R.string.onboarding_step_key_description,
    inProgressMessageRes = R.string.onboarding_step_key_in_progress,
    successMessageRes = R.string.onboarding_step_key_success,
  ),
  VERIFICATION(
    titleRes = R.string.onboarding_step_verify_title,
    descriptionRes = R.string.onboarding_step_verify_description,
    inProgressMessageRes = R.string.onboarding_step_verify_in_progress,
    successMessageRes = R.string.onboarding_step_verify_success,
  );

  val index: Int get() = ordinal
  val isFirst: Boolean get() = this == ROOT_CHECK
  val isLast: Boolean get() = this == VERIFICATION

  fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)

  companion object {
    val totalSteps: Int = entries.size
  }
}

sealed interface StepState {
  data object Pending : StepState
  data object InProgress : StepState
  data class Success(@param:StringRes val detailsRes: Int? = null) : StepState
  data class Failed(
    @param:StringRes val reasonRes: Int,
    val reasonArgs: List<Any> = emptyList(),
    val canRetry: Boolean = true,
    val isFatal: Boolean = false,
  ) : StepState

  data class WaitingForConfirmation(
    @param:StringRes val messageRes: Int,
    @param:StringRes val actionRes: Int,
  ) : StepState

  data class WaitingForReboot(
    @param:StringRes val messageRes: Int,
  ) : StepState

  val isCompleted: Boolean get() = this is Success
  val isFailed: Boolean get() = this is Failed
  val isActive: Boolean get() = this is InProgress
  val isPending: Boolean get() = this is Pending
  val isWaiting: Boolean get() = this is WaitingForConfirmation || this is WaitingForReboot
}

data class OnboardingUiState(
  val currentStep: OnboardingStep = OnboardingStep.ROOT_CHECK,
  val stepStates: Map<OnboardingStep, StepState> = OnboardingStep.entries.associateWith { StepState.Pending },
  val isCompleted: Boolean = false,
  val showExitConfirmation: Boolean = false,
  val showSkipWarning: Boolean = false,
) {
  val currentStepState: StepState
    get() = stepStates[currentStep] ?: StepState.Pending

  val progress: Float
    get() {
      val completedSteps = stepStates.count { it.value.isCompleted }
      return completedSteps.toFloat() / OnboardingStep.totalSteps
    }

  val completedStepsCount: Int
    get() = stepStates.count { it.value.isCompleted }

  val canProceed: Boolean
    get() = currentStepState.isCompleted && !currentStep.isLast

  val canRetry: Boolean
    get() = (currentStepState as? StepState.Failed)?.canRetry == true

  val canConfirm: Boolean
    get() = currentStepState is StepState.WaitingForConfirmation

  val needsReboot: Boolean
    get() = currentStepState is StepState.WaitingForReboot

  val isFatalError: Boolean
    get() = (currentStepState as? StepState.Failed)?.isFatal == true

  val allStepsCompleted: Boolean
    get() = stepStates.all { it.value.isCompleted }

  val canSkip: Boolean
    get() = currentStep.isSkippable && (currentStepState.isActive || currentStepState.isFailed)
}

sealed interface OnboardingUiEvent {
  data object StartOnboarding : OnboardingUiEvent
  data object RetryCurrentStep : OnboardingUiEvent
  data object ConfirmCurrentStep : OnboardingUiEvent
  data object SkipModelDeployment : OnboardingUiEvent
  data object ShowSkipWarning : OnboardingUiEvent
  data object DismissSkipWarning : OnboardingUiEvent
  data object ExitApp : OnboardingUiEvent
  data object DismissExitConfirmation : OnboardingUiEvent
  data object ShowExitConfirmation : OnboardingUiEvent
  data object CompleteOnboarding : OnboardingUiEvent
}
