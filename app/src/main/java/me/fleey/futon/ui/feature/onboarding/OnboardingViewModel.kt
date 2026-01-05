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

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.daemon.DaemonAuthException
import me.fleey.futon.data.daemon.DaemonAuthenticator
import me.fleey.futon.data.daemon.DaemonBinderClient
import me.fleey.futon.data.daemon.DaemonLifecycleManager
import me.fleey.futon.data.daemon.KeyDeployer
import me.fleey.futon.data.daemon.deployment.DeploymentError
import me.fleey.futon.data.daemon.deployment.ModuleDeployer
import me.fleey.futon.data.daemon.deployment.ModuleDeploymentState
import me.fleey.futon.data.daemon.deployment.RootState
import me.fleey.futon.data.daemon.models.DaemonConnectionResult
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.perception.models.ModelDeploymentStatus
import me.fleey.futon.data.perception.models.ModelManager
import me.fleey.futon.platform.root.RootChecker
import org.koin.android.annotation.KoinViewModel
import java.util.concurrent.atomic.AtomicBoolean

@KoinViewModel
class OnboardingViewModel(
  private val rootChecker: RootChecker,
  private val moduleDeployer: ModuleDeployer,
  private val daemonAuthenticator: DaemonAuthenticator,
  private val daemonLifecycleManager: DaemonLifecycleManager,
  private val daemonBinderClient: DaemonBinderClient,
  private val keyDeployer: KeyDeployer,
  private val dataStore: DataStore<Preferences>,
  private val modelManager: ModelManager,
) : ViewModel() {

  private val _uiState = MutableStateFlow(OnboardingUiState())
  val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

  private val isExecutingStep = AtomicBoolean(false)

  fun onEvent(event: OnboardingUiEvent) {
    when (event) {
      OnboardingUiEvent.StartOnboarding -> startOnboarding()
      OnboardingUiEvent.RetryCurrentStep -> retryCurrentStep()
      OnboardingUiEvent.ConfirmCurrentStep -> confirmCurrentStep()
      OnboardingUiEvent.SkipModelDeployment -> skipModelDeployment()
      OnboardingUiEvent.ShowSkipWarning -> showSkipWarning()
      OnboardingUiEvent.DismissSkipWarning -> dismissSkipWarning()
      OnboardingUiEvent.ExitApp -> { /* Handled by UI */
      }

      OnboardingUiEvent.ShowExitConfirmation -> showExitConfirmation()
      OnboardingUiEvent.DismissExitConfirmation -> dismissExitConfirmation()
      OnboardingUiEvent.CompleteOnboarding -> completeOnboarding()
    }
  }

  private fun startOnboarding() {
    viewModelScope.launch {
      executeStep(OnboardingStep.ROOT_CHECK)
    }
  }

  private fun retryCurrentStep() {
    viewModelScope.launch {
      val currentStep = _uiState.value.currentStep
      executeStep(currentStep)
    }
  }

  private fun confirmCurrentStep() {
    viewModelScope.launch {
      val currentStep = _uiState.value.currentStep
      val currentState = _uiState.value.currentStepState

      when (currentStep) {
        OnboardingStep.DAEMON_DEPLOY -> {
          if (currentState is StepState.WaitingForConfirmation) {
            performModuleInstall()
          }
        }

        else -> Unit
      }
    }
  }

  private fun skipModelDeployment() {
    viewModelScope.launch {
      val currentStep = _uiState.value.currentStep
      if (currentStep == OnboardingStep.MODEL_DEPLOY) {
        _uiState.update { it.copy(showSkipWarning = false) }
        dataStore.edit { prefs ->
          prefs[KEY_MODEL_DEPLOYMENT_SKIPPED] = true
        }
        updateStepState(currentStep, StepState.Success(R.string.onboarding_step_model_skipped))
        proceedToNextStep()
      }
    }
  }

  private fun showExitConfirmation() {
    _uiState.update { it.copy(showExitConfirmation = true) }
  }

  private fun dismissExitConfirmation() {
    _uiState.update { it.copy(showExitConfirmation = false) }
  }

  private fun showSkipWarning() {
    _uiState.update { it.copy(showSkipWarning = true) }
  }

  private fun dismissSkipWarning() {
    _uiState.update { it.copy(showSkipWarning = false) }
  }

  private fun completeOnboarding() {
    viewModelScope.launch {
      dataStore.edit { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] = true
      }
      _uiState.update { it.copy(isCompleted = true) }
    }
  }

  private suspend fun executeStep(step: OnboardingStep) {
    if (!isExecutingStep.compareAndSet(false, true)) {
      Log.w(TAG, "executeStep: Already executing a step, ignoring duplicate call for $step")
      return
    }

    try {
      executeStepInternal(step)
    } finally {
      isExecutingStep.set(false)
    }
  }

  private suspend fun executeStepInternal(step: OnboardingStep) {
    updateStepState(step, StepState.InProgress)
    _uiState.update { it.copy(currentStep = step) }

    when (step) {
      OnboardingStep.ROOT_CHECK -> executeRootCheck()
      OnboardingStep.DAEMON_DEPLOY -> executeModuleDeploy()
      OnboardingStep.MODEL_DEPLOY -> executeModelDeploy()
      OnboardingStep.KEY_GENERATION -> executeKeyGeneration()
      OnboardingStep.VERIFICATION -> executeVerification()
    }
  }

  private suspend fun executeRootCheck() {
    delay(300)
    val rootState = rootChecker.checkRoot(forceRecheck = true)

    when (rootState) {
      is RootState.Available -> {
        updateStepState(OnboardingStep.ROOT_CHECK, StepState.Success())
        proceedToNextStep()
      }

      is RootState.Unavailable -> {
        updateStepState(
          OnboardingStep.ROOT_CHECK,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_root_unavailable,
            canRetry = true,
            isFatal = true,
          ),
        )
      }

      is RootState.SELinuxBlocked -> {
        updateStepState(
          OnboardingStep.ROOT_CHECK,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_selinux_blocked,
            canRetry = true,
            isFatal = false,
          ),
        )
      }

      is RootState.NotChecked -> {
        updateStepState(
          OnboardingStep.ROOT_CHECK,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_root_check_failed,
            canRetry = true,
            isFatal = false,
          ),
        )
      }
    }
  }

  private suspend fun executeModuleDeploy() {
    val verificationResult = moduleDeployer.checkDeploymentStatus()

    when (verificationResult) {
      is ModuleDeploymentState.UpToDate -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.Success(R.string.onboarding_step_deploy_up_to_date),
        )
        proceedToNextStep()
      }

      is ModuleDeploymentState.NeedsInstall -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.WaitingForConfirmation(
            messageRes = R.string.onboarding_step_deploy_confirm_message,
            actionRes = R.string.onboarding_step_deploy_confirm_action,
          ),
        )
      }

      is ModuleDeploymentState.NeedsUpdate -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.WaitingForConfirmation(
            messageRes = R.string.onboarding_step_deploy_update_message,
            actionRes = R.string.onboarding_step_deploy_update_action,
          ),
        )
      }

      is ModuleDeploymentState.Failed -> {
        Log.e(TAG, "Module deployment check failed: ${verificationResult.error}")
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_deploy_check_failed,
            canRetry = true,
            isFatal = false,
          ),
        )
      }

      else -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.WaitingForConfirmation(
            messageRes = R.string.onboarding_step_deploy_confirm_message,
            actionRes = R.string.onboarding_step_deploy_confirm_action,
          ),
        )
      }
    }
  }

  private suspend fun performModuleInstall() {
    updateStepState(OnboardingStep.DAEMON_DEPLOY, StepState.InProgress)

    when (val deploymentState = moduleDeployer.deployIfNeeded()) {
      is ModuleDeploymentState.Deployed -> {
        val detailsRes = if (deploymentState.needsReboot) {
          R.string.onboarding_step_deploy_success_reboot
        } else {
          null
        }
        updateStepState(OnboardingStep.DAEMON_DEPLOY, StepState.Success(detailsRes))
        proceedToNextStep()
      }

      is ModuleDeploymentState.UpToDate -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.Success(R.string.onboarding_step_deploy_up_to_date),
        )
        proceedToNextStep()
      }

      is ModuleDeploymentState.Failed -> {
        Log.e(TAG, "Module deployment failed: ${deploymentState.error}")
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.Failed(
            reasonRes = deploymentState.error.toStringRes(),
            canRetry = deploymentState.canRetry,
            isFatal = false,
          ),
        )
      }

      else -> {
        updateStepState(
          OnboardingStep.DAEMON_DEPLOY,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_deploy_failed,
            canRetry = true,
            isFatal = false,
          ),
        )
      }
    }
  }

  private fun DeploymentError.toStringRes(): Int = when (this) {
    is DeploymentError.ContractNotFound -> R.string.deploy_error_contract_not_found
    is DeploymentError.ContractParseError -> R.string.deploy_error_contract_parse
    is DeploymentError.IncompatibleVersion -> R.string.deploy_error_incompatible_version
    is DeploymentError.UnknownDeployment -> R.string.deploy_error_unknown_deployment
    is DeploymentError.RootNotAvailable -> R.string.deploy_error_root_not_available
    is DeploymentError.ExtractionFailed -> R.string.deploy_error_extraction_failed
    is DeploymentError.InstallationTimeout -> R.string.deploy_error_installation_timeout
    is DeploymentError.InstallationError -> R.string.deploy_error_installation_failed
    is DeploymentError.RootDenied -> R.string.deploy_error_root_denied
    is DeploymentError.FallbackFailed -> R.string.deploy_error_fallback_failed
    is DeploymentError.UninstallFailed -> R.string.deploy_error_uninstall_failed
    is DeploymentError.Unknown -> R.string.onboarding_error_deploy_failed
  }

  private suspend fun executeModelDeploy() {
    val deployResult = modelManager.deployAllModels()

    deployResult.fold(
      onSuccess = {
        updateStepState(OnboardingStep.MODEL_DEPLOY, StepState.Success())
        proceedToNextStep()
      },
      onFailure = { error ->
        Log.e(TAG, "Model deployment failed: ${error.message}")
        val state = modelManager.deploymentState.value
        val errorMessage = state.lastError ?: error.message ?: "Unknown error"

        if (state.hasFailedModels) {
          val failedModel = state.models.entries.firstOrNull {
            it.value is ModelDeploymentStatus.Failed
          }
          val failureReason = (failedModel?.value as? ModelDeploymentStatus.Failed)?.reason
            ?: errorMessage

          updateStepState(
            OnboardingStep.MODEL_DEPLOY,
            StepState.Failed(
              reasonRes = R.string.onboarding_error_model_deploy_failed,
              reasonArgs = listOf(failureReason),
              canRetry = true,
              isFatal = false,
            ),
          )
        } else {
          updateStepState(
            OnboardingStep.MODEL_DEPLOY,
            StepState.Failed(
              reasonRes = R.string.onboarding_error_model_deploy_failed,
              reasonArgs = listOf(errorMessage),
              canRetry = true,
              isFatal = false,
            ),
          )
        }
      },
    )
  }

  private suspend fun executeKeyGeneration() {
    delay(200)

    // Delete existing key first to ensure we get a fresh TEE-backed key
    // This clears any potentially corrupted StrongBox keys
    val deleteResult = daemonAuthenticator.deleteKeyPair()
    if (deleteResult.isFailure) {
      Log.w(TAG, "Failed to delete existing key pair: ${deleteResult.exceptionOrNull()?.message}")
      // Continue anyway, key might not exist
    }

    val result = daemonAuthenticator.ensureKeyPairExists()

    if (result.isFailure) {
      updateStepState(
        OnboardingStep.KEY_GENERATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_generation_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    val publicKeyResult = daemonAuthenticator.getPublicKey()
    if (publicKeyResult.isFailure) {
      updateStepState(
        OnboardingStep.KEY_GENERATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_generation_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    val deployResult = keyDeployer.deployPublicKey(publicKeyResult.getOrThrow())
    if (deployResult.isFailure) {
      updateStepState(
        OnboardingStep.KEY_GENERATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_deploy_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    updateStepState(OnboardingStep.KEY_GENERATION, StepState.Success())
    proceedToNextStep()
  }

  private suspend fun executeVerification() {
    Log.d(TAG, "Restarting daemon to clear any existing sessions")
    daemonLifecycleManager.stopDaemon()
    delay(500)

    val startResult = daemonLifecycleManager.startDaemon()
    if (startResult.isFailure) {
      Log.e(TAG, "Failed to start daemon: ${startResult.exceptionOrNull()?.message}")
      val deployState = _uiState.value.stepStates[OnboardingStep.DAEMON_DEPLOY]
      val moduleJustInstalled = deployState is StepState.Success &&
        deployState.detailsRes == R.string.onboarding_step_deploy_success_reboot

      if (moduleJustInstalled) {
        updateStepState(
          OnboardingStep.VERIFICATION,
          StepState.WaitingForReboot(messageRes = R.string.onboarding_verify_needs_reboot),
        )
      } else {
        updateStepState(
          OnboardingStep.VERIFICATION,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_daemon_start_failed,
            canRetry = true,
            isFatal = false,
          ),
        )
      }
      return
    }

    delay(500)
    val connectResult = daemonBinderClient.connect()
    if (connectResult !is DaemonConnectionResult.Connected) {
      Log.e(TAG, "Binder connection failed: $connectResult")
      val deployState = _uiState.value.stepStates[OnboardingStep.DAEMON_DEPLOY]
      val moduleJustInstalled = deployState is StepState.Success &&
        deployState.detailsRes == R.string.onboarding_step_deploy_success_reboot

      if (moduleJustInstalled) {
        updateStepState(
          OnboardingStep.VERIFICATION,
          StepState.WaitingForReboot(messageRes = R.string.onboarding_verify_needs_reboot),
        )
      } else {
        updateStepState(
          OnboardingStep.VERIFICATION,
          StepState.Failed(
            reasonRes = R.string.onboarding_error_binder_connection_failed,
            canRetry = true,
            isFatal = false,
          ),
        )
      }
      return
    }

    val keyResult = daemonAuthenticator.ensureKeyPairExists()
    if (keyResult.isFailure) {
      Log.e(TAG, "Key pair check failed: ${keyResult.exceptionOrNull()?.message}")
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_generation_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    val challengeResult = daemonBinderClient.getChallenge()
    if (challengeResult.isFailure) {
      Log.e(TAG, "Failed to get challenge: ${challengeResult.exceptionOrNull()?.message}")
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    val challenge = challengeResult.getOrThrow()
    val signatureResult = daemonAuthenticator.signChallenge(challenge)
    if (signatureResult.isFailure) {
      val exception = signatureResult.exceptionOrNull()
      Log.e(TAG, "Failed to sign challenge: ${exception?.message}")

      val isKeyCorrupted = exception is DaemonAuthException &&
        exception.error.code == ErrorCode.AUTH_KEY_CORRUPTED

      if (isKeyCorrupted) {
        Log.w(TAG, "Key corrupted, regenerating...")
        if (!handleKeyRegeneration()) return
        return
      }

      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    val signature = signatureResult.getOrThrow()
    val instanceId = daemonAuthenticator.getInstanceId()
    val authResult = daemonBinderClient.authenticate(signature, instanceId)
    if (authResult.isFailure) {
      Log.e(TAG, "Authentication failed: ${authResult.exceptionOrNull()?.message}")
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return
    }

    updateStepState(OnboardingStep.VERIFICATION, StepState.Success())
  }

  /**
   * Handle key regeneration when the existing key is corrupted.
   * @return true if authentication succeeded after regeneration, false otherwise
   */
  private suspend fun handleKeyRegeneration(): Boolean {
    daemonAuthenticator.deleteKeyPair()

    val regenResult = daemonAuthenticator.ensureKeyPairExists()
    if (regenResult.isFailure) {
      Log.e(TAG, "Key regeneration failed")
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_generation_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    val newPublicKeyResult = daemonAuthenticator.getPublicKey()
    if (newPublicKeyResult.isFailure) {
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_generation_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    val redeployResult = keyDeployer.deployPublicKey(newPublicKeyResult.getOrThrow())
    if (redeployResult.isFailure) {
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_key_deploy_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    val newChallengeResult = daemonBinderClient.getChallenge()
    if (newChallengeResult.isFailure) {
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    val newChallenge = newChallengeResult.getOrThrow()
    val newSignatureResult = daemonAuthenticator.signChallenge(newChallenge)
    if (newSignatureResult.isFailure) {
      Log.e(TAG, "Signing still failed after key regeneration")
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    val newSignature = newSignatureResult.getOrThrow()
    val instanceId = daemonAuthenticator.getInstanceId()
    val authResult = daemonBinderClient.authenticate(newSignature, instanceId)
    if (authResult.isFailure) {
      Log.e(
        TAG,
        "Authentication failed after key regeneration: ${authResult.exceptionOrNull()?.message}",
      )
      updateStepState(
        OnboardingStep.VERIFICATION,
        StepState.Failed(
          reasonRes = R.string.onboarding_error_auth_failed,
          canRetry = true,
          isFatal = false,
        ),
      )
      return false
    }

    updateStepState(OnboardingStep.VERIFICATION, StepState.Success())
    return true
  }

  private fun updateStepState(step: OnboardingStep, state: StepState) {
    _uiState.update { currentState ->
      currentState.copy(
        stepStates = currentState.stepStates + (step to state),
      )
    }
  }

  private suspend fun proceedToNextStep() {
    val currentStep = _uiState.value.currentStep
    val nextStep = currentStep.next()

    if (nextStep != null) {
      delay(500)
      executeStepInternal(nextStep)
    }
  }

  companion object {
    private const val TAG = "OnboardingViewModel"
    val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val KEY_MODEL_DEPLOYMENT_SKIPPED = booleanPreferencesKey("model_deployment_skipped")
  }
}
