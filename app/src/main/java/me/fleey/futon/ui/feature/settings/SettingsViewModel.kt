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
package me.fleey.futon.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ModelConfig
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.settings.LocaleManager
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.AISettings
import me.fleey.futon.data.settings.models.AppLanguage
import me.fleey.futon.data.settings.models.ScreenshotQuality
import me.fleey.futon.data.settings.models.ThemeMode
import org.koin.android.annotation.KoinViewModel

sealed interface ConnectionTestResult {
  data object Idle : ConnectionTestResult
  data object Testing : ConnectionTestResult
  data class Success(val message: String) : ConnectionTestResult
  data class Failure(val error: String) : ConnectionTestResult
}

data class SettingsUiState(
  val providers: List<Provider> = emptyList(),
  val selectedProviderId: String? = null,

  val editingProvider: Provider? = null,
  val providerModels: List<ModelConfig> = emptyList(),
  val selectedModelId: String? = null,
  val hasUnsavedChanges: Boolean = false,

  val availableModels: List<String> = emptyList(),
  val isLoadingModels: Boolean = false,
  val modelFetchError: String? = null,

  val connectionTestResult: ConnectionTestResult = ConnectionTestResult.Idle,

  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColorEnabled: Boolean = true,
  val appLanguage: AppLanguage = AppLanguage.SYSTEM,

  val systemPrompt: String = AISettings.DEFAULT_SYSTEM_PROMPT,
  val maxSteps: Int = 20,
  val requestTimeoutMs: Long = AISettings.DEFAULT_TIMEOUT_MS,
  val maxTokens: Int = AISettings.DEFAULT_MAX_TOKENS,
  val screenshotQuality: ScreenshotQuality = ScreenshotQuality.MEDIUM,

  val isLoading: Boolean = true,
  val isSaving: Boolean = false,
  val saveSuccess: Boolean = false,
  val errorMessage: String? = null,
) {
  val currentProvider: Provider?
    get() = editingProvider ?: providers.find { it.id == selectedProviderId }

  val selectedProviderNeedsApiKey: Boolean
    get() = currentProvider?.let { !it.isConfigured() } ?: true

  val requestTimeoutSeconds: Long
    get() = requestTimeoutMs / 1000
}

sealed interface SettingsUiEvent {
  data class ProviderSelected(val providerId: String) : SettingsUiEvent
  data class SetActiveModel(val providerId: String, val modelId: String) : SettingsUiEvent

  data class ProviderChanged(val provider: Provider) : SettingsUiEvent
  data class ModelSelected(val modelId: String) : SettingsUiEvent
  data object SaveProviderConfig : SettingsUiEvent

  data object FetchAvailableModels : SettingsUiEvent
  data object TestConnection : SettingsUiEvent
  data object DismissConnectionTestResult : SettingsUiEvent

  data class ApiKeyChanged(val value: String) : SettingsUiEvent
  data class BaseUrlChanged(val value: String) : SettingsUiEvent

  data class ThemeModeChanged(val mode: ThemeMode) : SettingsUiEvent
  data class DynamicColorChanged(val enabled: Boolean) : SettingsUiEvent
  data class AppLanguageChanged(val language: AppLanguage) : SettingsUiEvent

  data class SystemPromptChanged(val value: String) : SettingsUiEvent
  data class MaxStepsChanged(val value: Int) : SettingsUiEvent
  data class RequestTimeoutChanged(val seconds: Long) : SettingsUiEvent
  data class MaxTokensChanged(val value: Int) : SettingsUiEvent
  data class ScreenshotQualityChanged(val quality: ScreenshotQuality) : SettingsUiEvent

  data object DismissSuccess : SettingsUiEvent
  data object DismissError : SettingsUiEvent
  data object DismissModelFetchError : SettingsUiEvent
}

@KoinViewModel
class SettingsViewModel(
  private val providerRepository: ProviderRepository,
  private val settingsRepository: SettingsRepository,
  private val adapters: Map<ApiProtocol, ProviderAdapter>,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  private var currentSettings: AISettings = AISettings()

  init {
    loadSettings()
  }

  private fun loadSettings() {
    viewModelScope.launch {
      providerRepository.initializeDefaults()

      val providers = providerRepository.getProviders()
      val settings = settingsRepository.getSettings()
      val themePrefs = settingsRepository.getThemePreferences()
      currentSettings = settings

      val activeProvider = providers.find { it.enabled && it.isConfigured() && it.selectedModelId != null }
        ?: providers.firstOrNull()
      val selectedId = activeProvider?.id
      val models = selectedId?.let { providerRepository.getModels(it) } ?: emptyList()

      _uiState.update {
        it.copy(
          providers = providers,
          selectedProviderId = selectedId,
          editingProvider = activeProvider,
          providerModels = models,
          selectedModelId = activeProvider?.selectedModelId ?: models.firstOrNull()?.modelId,
          themeMode = themePrefs.themeMode,
          dynamicColorEnabled = themePrefs.dynamicColorEnabled,
          appLanguage = themePrefs.appLanguage,
          systemPrompt = settings.systemPrompt,
          maxSteps = settings.maxSteps,
          requestTimeoutMs = settings.requestTimeoutMs,
          maxTokens = settings.maxTokens,
          screenshotQuality = settings.screenshotQuality,
          isLoading = false,
        )
      }

      fetchAvailableModels()
    }
  }

  fun onEvent(event: SettingsUiEvent) {
    when (event) {
      is SettingsUiEvent.ProviderSelected -> handleProviderSelected(event.providerId)
      is SettingsUiEvent.SetActiveModel -> handleSetActiveModel(event.providerId, event.modelId)
      is SettingsUiEvent.ProviderChanged -> handleProviderChanged(event.provider)
      is SettingsUiEvent.ModelSelected -> handleModelSelected(event.modelId)
      SettingsUiEvent.SaveProviderConfig -> saveProviderConfig()
      SettingsUiEvent.FetchAvailableModels -> fetchAvailableModels()
      SettingsUiEvent.TestConnection -> testConnection()
      SettingsUiEvent.DismissConnectionTestResult -> _uiState.update { it.copy(connectionTestResult = ConnectionTestResult.Idle) }
      is SettingsUiEvent.ApiKeyChanged -> updateCurrentProvider { it.copy(apiKey = event.value) }
      is SettingsUiEvent.BaseUrlChanged -> updateCurrentProvider { it.copy(baseUrl = event.value) }
      is SettingsUiEvent.ThemeModeChanged -> handleThemeModeChanged(event.mode)
      is SettingsUiEvent.DynamicColorChanged -> handleDynamicColorChanged(event.enabled)
      is SettingsUiEvent.AppLanguageChanged -> handleAppLanguageChanged(event.language)
      is SettingsUiEvent.SystemPromptChanged -> handleSystemPromptChanged(event.value)
      is SettingsUiEvent.MaxStepsChanged -> handleMaxStepsChanged(event.value)
      is SettingsUiEvent.RequestTimeoutChanged -> handleRequestTimeoutChanged(event.seconds)
      is SettingsUiEvent.MaxTokensChanged -> handleMaxTokensChanged(event.value)
      is SettingsUiEvent.ScreenshotQualityChanged -> handleScreenshotQualityChanged(event.quality)
      SettingsUiEvent.DismissSuccess -> _uiState.update { it.copy(saveSuccess = false) }
      SettingsUiEvent.DismissError -> _uiState.update { it.copy(errorMessage = null) }
      SettingsUiEvent.DismissModelFetchError -> _uiState.update { it.copy(modelFetchError = null) }
    }
  }

  private fun handleProviderSelected(providerId: String) {
    val state = _uiState.value
    if (providerId == state.selectedProviderId) return

    viewModelScope.launch {
      val provider = providerRepository.getProvider(providerId)
      val models = providerRepository.getModels(providerId)

      _uiState.update {
        it.copy(
          selectedProviderId = providerId,
          editingProvider = provider,
          providerModels = models,
          selectedModelId = models.firstOrNull()?.modelId,
          hasUnsavedChanges = false,
          availableModels = emptyList(),
          modelFetchError = null,
          connectionTestResult = ConnectionTestResult.Idle,
        )
      }

      fetchAvailableModels()
    }
  }

  private fun handleSetActiveModel(providerId: String, modelId: String) {
    viewModelScope.launch {
      try {
        val state = _uiState.value
        if (state.hasUnsavedChanges && state.editingProvider != null) {
          val error = validateProvider(state.editingProvider)
          if (error != null) {
            _uiState.update { it.copy(errorMessage = error) }
            return@launch
          }
          providerRepository.saveProvider(
            state.editingProvider.copy(
              apiKey = state.editingProvider.apiKey.trim(),
              baseUrl = state.editingProvider.baseUrl.trim(),
            ),
          )
        }

        providerRepository.setProviderSelectedModel(providerId, modelId)

        _uiState.update {
          it.copy(
            selectedModelId = modelId,
            hasUnsavedChanges = false,
            saveSuccess = true,
          )
        }

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(errorMessage = e.message ?: "Failed to set active model") }
      }
    }
  }

  private fun handleProviderChanged(provider: Provider) {
    val state = _uiState.value
    val savedProvider = state.providers.find { it.id == provider.id }

    val hasChanges = if (savedProvider != null) {
      provider.apiKey.trim() != savedProvider.apiKey.trim() ||
        provider.baseUrl.trim() != savedProvider.baseUrl.trim()
    } else {
      provider.apiKey.isNotBlank() || provider.baseUrl.isNotBlank()
    }

    _uiState.update {
      it.copy(
        editingProvider = provider,
        hasUnsavedChanges = hasChanges,
      )
    }
  }

  private fun handleModelSelected(modelId: String) {
    _uiState.update { it.copy(selectedModelId = modelId) }
  }

  private fun updateCurrentProvider(transform: (Provider) -> Provider) {
    val state = _uiState.value
    val currentProvider = state.editingProvider ?: return
    handleProviderChanged(transform(currentProvider))
  }

  private fun saveProviderConfig() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return

    val error = validateProvider(provider)
    if (error != null) {
      _uiState.update { it.copy(errorMessage = error) }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isSaving = true) }
      try {
        val trimmedProvider = provider.copy(
          apiKey = provider.apiKey.trim(),
          baseUrl = provider.baseUrl.trim(),
        )

        providerRepository.saveProvider(trimmedProvider)

        val updatedProviders = providerRepository.getProviders()

        _uiState.update {
          it.copy(
            providers = updatedProviders,
            editingProvider = trimmedProvider,
            hasUnsavedChanges = false,
            isSaving = false,
            saveSuccess = true,
          )
        }

        fetchAvailableModels()

        delay(2000)
        _uiState.update { it.copy(saveSuccess = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isSaving = false, errorMessage = e.message ?: "Save failed") }
      }
    }
  }

  private fun fetchAvailableModels() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return

    val adapter = adapters[provider.protocol] ?: return

    if (!provider.isConfigured()) {
      _uiState.update {
        it.copy(
          availableModels = state.providerModels.map { m -> m.modelId },
          modelFetchError = "Please configure API key first",
        )
      }
      return
    }

    if (provider.baseUrl.isBlank()) {
      _uiState.update {
        it.copy(
          availableModels = state.providerModels.map { m -> m.modelId },
          modelFetchError = "Please configure Base URL first",
        )
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingModels = true, modelFetchError = null) }
      try {
        val models = adapter.fetchAvailableModels(provider)
        if (models.isEmpty()) {
          _uiState.update {
            it.copy(
              availableModels = state.providerModels.map { m -> m.modelId },
              isLoadingModels = false,
              modelFetchError = "API returned empty model list, using defaults",
            )
          }
        } else {
          _uiState.update {
            it.copy(
              availableModels = models,
              isLoadingModels = false,
              modelFetchError = null,
            )
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            availableModels = state.providerModels.map { m -> m.modelId },
            isLoadingModels = false,
            modelFetchError = e.message ?: "Failed to fetch models",
          )
        }
      }
    }
  }

  private fun testConnection() {
    val state = _uiState.value
    val provider = state.editingProvider ?: return

    if (!provider.isConfigured()) {
      _uiState.update {
        it.copy(connectionTestResult = ConnectionTestResult.Failure("Please configure API key first"))
      }
      return
    }
    if (provider.baseUrl.isBlank()) {
      _uiState.update {
        it.copy(connectionTestResult = ConnectionTestResult.Failure("Please configure Base URL first"))
      }
      return
    }

    val adapter = adapters[provider.protocol]
    if (adapter == null) {
      _uiState.update {
        it.copy(connectionTestResult = ConnectionTestResult.Failure("Unsupported protocol"))
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(connectionTestResult = ConnectionTestResult.Testing) }
      try {
        val models = adapter.fetchAvailableModels(provider)
        if (models.isNotEmpty()) {
          _uiState.update {
            it.copy(
              connectionTestResult = ConnectionTestResult.Success("Connected! Found ${models.size} models"),
              availableModels = models,
              modelFetchError = null,
            )
          }
        } else {
          _uiState.update {
            it.copy(connectionTestResult = ConnectionTestResult.Success("Connected, but no models found"))
          }
        }

        delay(3000)
        _uiState.update {
          if (it.connectionTestResult is ConnectionTestResult.Success) {
            it.copy(connectionTestResult = ConnectionTestResult.Idle)
          } else {
            it
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(connectionTestResult = ConnectionTestResult.Failure(e.message ?: "Connection failed"))
        }
      }
    }
  }

  private fun handleThemeModeChanged(mode: ThemeMode) {
    _uiState.update { it.copy(themeMode = mode) }
    viewModelScope.launch {
      runCatching { settingsRepository.setThemeMode(mode) }
    }
  }

  private fun handleDynamicColorChanged(enabled: Boolean) {
    _uiState.update { it.copy(dynamicColorEnabled = enabled) }
    viewModelScope.launch {
      runCatching { settingsRepository.setDynamicColorEnabled(enabled) }
    }
  }

  private fun handleAppLanguageChanged(language: AppLanguage) {
    _uiState.update { it.copy(appLanguage = language) }
    viewModelScope.launch {
      runCatching {
        settingsRepository.setAppLanguage(language)
        LocaleManager.applyLanguage(language)
      }
    }
  }

  private fun handleSystemPromptChanged(value: String) {
    _uiState.update { it.copy(systemPrompt = value) }
    viewModelScope.launch {
      runCatching {
        val updatedSettings = currentSettings.copy(systemPrompt = value)
        settingsRepository.updateSettings(updatedSettings)
        currentSettings = updatedSettings
      }
    }
  }

  private fun handleMaxStepsChanged(value: Int) {
    _uiState.update { it.copy(maxSteps = value) }
    viewModelScope.launch {
      runCatching {
        val updatedSettings = currentSettings.copy(maxSteps = value)
        settingsRepository.updateSettings(updatedSettings)
        currentSettings = updatedSettings
      }
    }
  }

  private fun handleRequestTimeoutChanged(seconds: Long) {
    val timeoutMs = seconds * 1000
    _uiState.update { it.copy(requestTimeoutMs = timeoutMs) }
    viewModelScope.launch {
      runCatching {
        val updatedSettings = currentSettings.copy(requestTimeoutMs = timeoutMs)
        settingsRepository.updateSettings(updatedSettings)
        currentSettings = updatedSettings
      }
    }
  }

  private fun handleMaxTokensChanged(value: Int) {
    _uiState.update { it.copy(maxTokens = value) }
    viewModelScope.launch {
      runCatching {
        val updatedSettings = currentSettings.copy(maxTokens = value)
        settingsRepository.updateSettings(updatedSettings)
        currentSettings = updatedSettings
      }
    }
  }

  private fun handleScreenshotQualityChanged(quality: ScreenshotQuality) {
    _uiState.update { it.copy(screenshotQuality = quality) }
    viewModelScope.launch {
      runCatching {
        val updatedSettings = currentSettings.copy(screenshotQuality = quality)
        settingsRepository.updateSettings(updatedSettings)
        currentSettings = updatedSettings
      }
    }
  }

  private fun validateProvider(provider: Provider): String? {
    val trimmedBaseUrl = provider.baseUrl.trim()
    return when {
      trimmedBaseUrl.isBlank() -> "Please enter API Base URL"
      !isValidUrl(trimmedBaseUrl) -> "Invalid URL format"
      else -> null
    }
  }

  private fun isValidUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
      return false
    }
    val afterProtocol = trimmed.substringAfter("://")
    return afterProtocol.isNotBlank() && !afterProtocol.startsWith("/")
  }

  fun discardUnsavedChanges() {
    val state = _uiState.value
    val savedProvider = state.providers.find { it.id == state.selectedProviderId }

    _uiState.update {
      it.copy(
        editingProvider = savedProvider,
        hasUnsavedChanges = false,
      )
    }
  }

  fun hasAnyUnsavedChanges(): Boolean = _uiState.value.hasUnsavedChanges
}
