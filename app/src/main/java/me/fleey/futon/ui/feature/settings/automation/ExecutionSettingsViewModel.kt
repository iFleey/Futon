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
package me.fleey.futon.ui.feature.settings.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.capture.CaptureCapabilities
import me.fleey.futon.data.capture.PrivacyManager
import me.fleey.futon.data.capture.ScreenCaptureStrategyFactory
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.daemon.ConfigurationSynchronizer
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.InputDeviceEntry
import me.fleey.futon.data.privacy.models.CaptureAuditEntry
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.platform.capability.CapabilityDetector
import me.fleey.futon.platform.capability.models.DeviceCapabilities
import me.fleey.futon.platform.capability.models.SELinuxMode
import me.fleey.futon.platform.input.models.InjectionMode
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.strategy.InputCapabilities
import me.fleey.futon.platform.input.strategy.InputStrategyFactory
import me.fleey.futon.platform.root.RootType
import me.fleey.futon.platform.root.SELinuxFixer
import org.koin.android.annotation.KoinViewModel

data class ExecutionSettingsUiState(
  // Execution settings
  val preferredInputMethod: InputMethod? = null,
  val preferredCaptureMethod: CaptureMethod? = null,
  val enableFallback: Boolean = true,
  val showCapabilityWarnings: Boolean = true,

  // Input device settings (for HotPath/DSP)
  val injectionMode: InjectionMode = InjectionMode.ROOT_UINPUT,
  val touchDevicePath: String = "",
  val inputDevices: List<InputDeviceEntry> = emptyList(),
  val isLoadingDevices: Boolean = false,

  // Privacy settings
  val privacyMode: PrivacyMode = PrivacyMode.STRICT,
  val auditLogEnabled: Boolean = true,
  val auditLog: List<CaptureAuditEntry> = emptyList(),

  // Capabilities
  val inputCapabilities: InputCapabilities = InputCapabilities.EMPTY,
  val captureCapabilities: CaptureCapabilities? = null,
  val deviceCapabilities: DeviceCapabilities? = null,

  // Active methods
  val activeInputMethod: InputMethod? = null,
  val activeCaptureMethod: CaptureMethod? = null,

  // Daemon state
  val daemonState: DaemonState = DaemonState.Stopped,
  val isDaemonConnected: Boolean = false,

  // SELinux state
  val selinuxMode: String = "Unknown",
  val isFixingSELinux: Boolean = false,
  val selinuxFixResult: SELinuxFixResultUi? = null,

  val isLoading: Boolean = true,
  val isRefreshing: Boolean = false,
  val isSaving: Boolean = false,
  val saveSuccess: Boolean = false,
  val errorMessage: String? = null,
) {

  fun getDaemonConnectionStatus(): String {
    return when (daemonState) {
      is DaemonState.Ready -> "Daemon (Binder IPC)"
      is DaemonState.Connecting -> "Connecting..."
      is DaemonState.Authenticating -> "Authenticating..."
      is DaemonState.Reconciling -> "Reconciling..."
      is DaemonState.Starting -> "Starting..."
      is DaemonState.Error -> "Error: ${daemonState.message}"
      is DaemonState.Stopped -> "Daemon not running"
    }
  }

  fun getRootStatusDisplay(): String {
    val caps = deviceCapabilities ?: return "Unknown"
    return if (caps.rootStatus.isAvailable) {
      val rootType = when (caps.rootStatus.rootType) {
        RootType.KSU -> "KernelSU"
        RootType.KSU_NEXT -> "KernelSU Next"
        RootType.SUKISU_ULTRA -> "SukiSU Ultra"
        RootType.MAGISK -> "Magisk"
        RootType.SUPERSU -> "SuperSU"
        RootType.APATCH -> "APatch"
        RootType.OTHER -> "Other"
        RootType.NONE -> "None"
      }
      val version = caps.rootStatus.version?.let { " ($it)" } ?: ""
      "Yes - $rootType$version"
    } else {
      "No"
    }
  }

  fun getSELinuxModeDisplay(): String {
    val caps = deviceCapabilities ?: return selinuxMode
    return when (caps.seLinuxStatus.mode) {
      SELinuxMode.ENFORCING -> "Enforcing"
      SELinuxMode.PERMISSIVE -> "Permissive"
      SELinuxMode.DISABLED -> "Disabled"
      SELinuxMode.UNKNOWN -> "Unknown"
    }
  }

  fun getInputInjectionDisplay(): String {
    return when {
      isDaemonConnected && inputCapabilities.nativeAvailable -> "Daemon IPC"
      inputCapabilities.androidInputAvailable -> "Android Input"
      inputCapabilities.shellAvailable -> "Shell Sendevent"
      else -> "Unavailable"
    }
  }

  fun getCapabilityUnavailableReason(capabilityName: String): String? {
    val caps = deviceCapabilities ?: return getDaemonStateReason()

    return when (capabilityName) {
      "input_injection" -> {
        if (!inputCapabilities.nativeAvailable) {
          inputCapabilities.nativeError ?: getDaemonStateReason()
        } else null
      }

      "screen_capture" -> {
        if (!caps.hasScreenCapture()) {
          getDaemonStateReason() ?: "Screen capture capability not available"
        } else null
      }

      "ocr" -> {
        if (!caps.hasOcr()) {
          getDaemonStateReason() ?: "OCR capability not available"
        } else null
      }

      "object_detection" -> {
        if (!caps.hasObjectDetection()) {
          getDaemonStateReason() ?: "Object detection capability not available"
        } else null
      }

      "hot_path" -> {
        if (!caps.hasHotPath()) {
          getDaemonStateReason() ?: "Hot path capability not available"
        } else null
      }

      "debug_stream" -> {
        if (!caps.hasDebugStream()) {
          getDaemonStateReason() ?: "Debug stream capability not available"
        } else null
      }

      else -> null
    }
  }

  private fun getDaemonStateReason(): String? {
    return when (daemonState) {
      is DaemonState.Stopped -> "Daemon not running - start via root shell"
      is DaemonState.Starting -> "Daemon is starting..."
      is DaemonState.Connecting -> "Connecting to daemon..."
      is DaemonState.Authenticating -> "Authenticating with daemon..."
      is DaemonState.Reconciling -> "Reconciling daemon state..."
      is DaemonState.Error -> "Daemon error: ${daemonState.message}"
      is DaemonState.Ready -> null
    }
  }
}

sealed interface SELinuxFixResultUi {
  data object Success : SELinuxFixResultUi
  data class Failed(val message: String, val suggestion: String?) : SELinuxFixResultUi
}

sealed interface ExecutionSettingsUiEvent {
  // Input method events
  data class InputMethodSelected(val method: InputMethod?) : ExecutionSettingsUiEvent

  // Capture method events
  data class CaptureMethodSelected(val method: CaptureMethod?) : ExecutionSettingsUiEvent

  // Fallback toggle
  data class FallbackToggled(val enabled: Boolean) : ExecutionSettingsUiEvent

  // Capability warnings toggle
  data class CapabilityWarningsToggled(val show: Boolean) : ExecutionSettingsUiEvent

  // Input device events (for HotPath/DSP)
  data class InjectionModeChanged(val mode: InjectionMode) : ExecutionSettingsUiEvent
  data class TouchDeviceChanged(val path: String) : ExecutionSettingsUiEvent
  data object RefreshInputDevices : ExecutionSettingsUiEvent

  // Privacy events
  data class PrivacyModeSelected(val mode: PrivacyMode) : ExecutionSettingsUiEvent
  data class AuditLogToggled(val enabled: Boolean) : ExecutionSettingsUiEvent
  data object ClearAuditLog : ExecutionSettingsUiEvent
  data object RefreshAuditLog : ExecutionSettingsUiEvent

  // Capability events
  data object RefreshCapabilities : ExecutionSettingsUiEvent

  // SELinux events
  data object FixSELinux : ExecutionSettingsUiEvent
  data object DismissSELinuxResult : ExecutionSettingsUiEvent

  data object DismissSuccess : ExecutionSettingsUiEvent
  data object DismissError : ExecutionSettingsUiEvent
}

@KoinViewModel
class ExecutionSettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val inputStrategyFactory: InputStrategyFactory,
  private val screenCaptureStrategyFactory: ScreenCaptureStrategyFactory,
  private val privacyManager: PrivacyManager,
  private val capabilityDetector: CapabilityDetector,
  private val selinuxFixer: SELinuxFixer,
  private val daemonRepository: DaemonRepository,
  private val configurationSynchronizer: ConfigurationSynchronizer,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ExecutionSettingsUiState())
  val uiState: StateFlow<ExecutionSettingsUiState> = _uiState.asStateFlow()

  init {
    loadSettings()
    observeDaemonState()
    loadSELinuxStatus()
    loadInputDevices()
    initializeDaemonConnection()
  }

  private fun initializeDaemonConnection() {
    viewModelScope.launch {
      val currentState = daemonRepository.daemonState.value
      if (currentState is DaemonState.Ready) {
        // Already connected, load capabilities immediately
        loadCapabilities()
        loadAuditLog()
      } else if (currentState !is DaemonState.Connecting) {
        // Not connected and not connecting, try to connect
        daemonRepository.connect()
      }
    }
  }

  private fun observeDaemonState() {
    viewModelScope.launch {
      daemonRepository.daemonState.collect { state ->
        _uiState.update {
          it.copy(
            daemonState = state,
            isDaemonConnected = state is DaemonState.Ready,
          )
        }
        if (state is DaemonState.Ready) {
          loadCapabilities()
          loadAuditLog()
        }
      }
    }
  }

  fun onEvent(event: ExecutionSettingsUiEvent) {
    when (event) {
      is ExecutionSettingsUiEvent.InputMethodSelected -> handleInputMethodSelected(event.method)
      is ExecutionSettingsUiEvent.CaptureMethodSelected -> handleCaptureMethodSelected(event.method)
      is ExecutionSettingsUiEvent.FallbackToggled -> handleFallbackToggled(event.enabled)
      is ExecutionSettingsUiEvent.CapabilityWarningsToggled -> handleCapabilityWarningsToggled(event.show)
      is ExecutionSettingsUiEvent.InjectionModeChanged -> handleInjectionModeChanged(event.mode)
      is ExecutionSettingsUiEvent.TouchDeviceChanged -> handleTouchDeviceChanged(event.path)
      ExecutionSettingsUiEvent.RefreshInputDevices -> loadInputDevices()
      is ExecutionSettingsUiEvent.PrivacyModeSelected -> handlePrivacyModeSelected(event.mode)
      is ExecutionSettingsUiEvent.AuditLogToggled -> handleAuditLogToggled(event.enabled)
      ExecutionSettingsUiEvent.ClearAuditLog -> handleClearAuditLog()
      ExecutionSettingsUiEvent.RefreshAuditLog -> loadAuditLog()
      ExecutionSettingsUiEvent.RefreshCapabilities -> refreshCapabilities()
      ExecutionSettingsUiEvent.FixSELinux -> handleFixSELinux()
      ExecutionSettingsUiEvent.DismissSELinuxResult -> _uiState.update { it.copy(selinuxFixResult = null) }
      ExecutionSettingsUiEvent.DismissSuccess -> _uiState.update { it.copy(saveSuccess = false) }
      ExecutionSettingsUiEvent.DismissError -> _uiState.update { it.copy(errorMessage = null) }
    }
  }

  private fun loadSELinuxStatus() {
    viewModelScope.launch {
      try {
        val mode = selinuxFixer.getMode()
        _uiState.update { it.copy(selinuxMode = mode) }
      } catch (e: Exception) {
        _uiState.update { it.copy(selinuxMode = "Unknown") }
      }
    }
  }

  private fun handleFixSELinux() {
    viewModelScope.launch {
      _uiState.update { it.copy(isFixingSELinux = true, selinuxFixResult = null) }

      try {
        val result = selinuxFixer.fixInputDeviceAccess()

        val uiResult = when (result) {
          is SELinuxFixer.FixResult.Success -> SELinuxFixResultUi.Success
          is SELinuxFixer.FixResult.AlreadyFixed -> SELinuxFixResultUi.Success
          is SELinuxFixer.FixResult.NotNeeded -> SELinuxFixResultUi.Success
          is SELinuxFixer.FixResult.Failed -> SELinuxFixResultUi.Failed(
            message = result.message,
            suggestion = result.suggestion,
          )
        }

        _uiState.update {
          it.copy(
            isFixingSELinux = false,
            selinuxFixResult = uiResult,
          )
        }

        // If successful, refresh capabilities to check if Native IOCTL is now available
        if (uiResult is SELinuxFixResultUi.Success) {
          // Longer delay to let SELinux policy take effect and allow native lib to reinitialize
          delay(1000)
          refreshCapabilities()
          // Refresh again after another delay to ensure detection
          delay(500)
          refreshCapabilities()
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isFixingSELinux = false,
            selinuxFixResult = SELinuxFixResultUi.Failed(
              message = e.message ?: "",
              suggestion = null,
            ),
          )
        }
      }
    }
  }

  private fun loadSettings() {
    viewModelScope.launch {
      try {
        val settings = settingsRepository.getExecutionSettings()
        val privacyMode = privacyManager.getPrivacyMode()
        val dspSettings = settingsRepository.getDspPerceptionSettings()

        _uiState.update {
          it.copy(
            preferredInputMethod = settings.preferredInputMethod,
            preferredCaptureMethod = settings.preferredCaptureMethod,
            enableFallback = settings.enableFallback,
            showCapabilityWarnings = settings.showCapabilityWarnings,
            injectionMode = dspSettings.injectionMode,
            touchDevicePath = dspSettings.touchDevicePath,
            privacyMode = privacyMode,
            auditLogEnabled = settings.auditLogEnabled,
            isLoading = false,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isLoading = false,
            errorMessage = e.message,
          )
        }
      }
    }
  }

  private fun loadCapabilities() {
    viewModelScope.launch {
      try {
        val inputCaps = inputStrategyFactory.detectCapabilities()
        val captureCaps = screenCaptureStrategyFactory.detectCapabilities()
        val deviceCaps = capabilityDetector.detectAll()

        val activeInput = inputStrategyFactory.getCurrentMethod()
        val activeCapture = screenCaptureStrategyFactory.getCurrentMethod()

        _uiState.update {
          it.copy(
            inputCapabilities = inputCaps,
            captureCapabilities = captureCaps,
            deviceCapabilities = deviceCaps,
            activeInputMethod = activeInput,
            activeCaptureMethod = activeCapture,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(errorMessage = e.message)
        }
      }
    }
  }

  private fun loadAuditLog() {
    viewModelScope.launch {
      try {
        val log = privacyManager.getAuditLog(100)
        _uiState.update { it.copy(auditLog = log) }
      } catch (e: Exception) {
        // Silently fail for audit log loading
      }
    }
  }

  private fun refreshCapabilities() {
    viewModelScope.launch {
      _uiState.update { it.copy(isRefreshing = true) }
      try {
        inputStrategyFactory.refreshCapabilities()
        capabilityDetector.refresh()

        val inputCaps = inputStrategyFactory.detectCapabilities()
        val captureCaps = screenCaptureStrategyFactory.detectCapabilities()
        val deviceCaps = capabilityDetector.detectAll()

        val activeInput = inputStrategyFactory.getCurrentMethod()
        val activeCapture = screenCaptureStrategyFactory.getCurrentMethod()

        _uiState.update {
          it.copy(
            inputCapabilities = inputCaps,
            captureCapabilities = captureCaps,
            deviceCapabilities = deviceCaps,
            activeInputMethod = activeInput,
            activeCaptureMethod = activeCapture,
            isRefreshing = false,
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isRefreshing = false,
            errorMessage = e.message,
          )
        }
      }
    }
  }

  private fun handleInputMethodSelected(method: InputMethod?) {
    _uiState.update { it.copy(preferredInputMethod = method) }
    viewModelScope.launch {
      try {
        settingsRepository.setPreferredInputMethod(method)
        inputStrategyFactory.setPreferredMethod(method)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleCaptureMethodSelected(method: CaptureMethod?) {
    _uiState.update { it.copy(preferredCaptureMethod = method) }
    viewModelScope.launch {
      try {
        settingsRepository.setPreferredCaptureMethod(method)
        screenCaptureStrategyFactory.setPreferredMethod(method)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleFallbackToggled(enabled: Boolean) {
    _uiState.update { it.copy(enableFallback = enabled) }
    viewModelScope.launch {
      try {
        settingsRepository.setEnableFallback(enabled)
        inputStrategyFactory.setFallbackEnabled(enabled)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleCapabilityWarningsToggled(show: Boolean) {
    _uiState.update { it.copy(showCapabilityWarnings = show) }
    viewModelScope.launch {
      try {
        settingsRepository.setShowCapabilityWarnings(show)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handlePrivacyModeSelected(mode: PrivacyMode) {
    _uiState.update { it.copy(privacyMode = mode) }
    viewModelScope.launch {
      try {
        privacyManager.setPrivacyMode(mode)
        settingsRepository.setPrivacyMode(mode)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleAuditLogToggled(enabled: Boolean) {
    _uiState.update { it.copy(auditLogEnabled = enabled) }
    viewModelScope.launch {
      try {
        settingsRepository.setAuditLogEnabled(enabled)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleClearAuditLog() {
    viewModelScope.launch {
      try {
        privacyManager.clearAuditLog()
        _uiState.update { it.copy(auditLog = emptyList()) }
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun loadInputDevices() {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isLoadingDevices = true) }
      try {
        val result = daemonRepository.listInputDevices()
        result.onSuccess { devices ->
          _uiState.update { it.copy(inputDevices = devices, isLoadingDevices = false) }
        }.onFailure {
          _uiState.update { it.copy(isLoadingDevices = false) }
        }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoadingDevices = false) }
      }
    }
  }

  private fun handleInjectionModeChanged(mode: InjectionMode) {
    _uiState.update { it.copy(injectionMode = mode) }
    viewModelScope.launch {
      try {
        settingsRepository.setInjectionMode(mode)
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun handleTouchDeviceChanged(path: String) {
    _uiState.update { it.copy(touchDevicePath = path) }
    viewModelScope.launch {
      try {
        settingsRepository.setTouchDevicePath(path)
        configurationSynchronizer.forceSync()
        showSaveSuccess()
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message) }
      }
    }
  }

  private fun showSaveSuccess() {
    viewModelScope.launch {
      _uiState.update { it.copy(saveSuccess = true) }
      delay(2000)
      _uiState.update { it.copy(saveSuccess = false) }
    }
  }
}
