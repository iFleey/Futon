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
package me.fleey.futon.ui.feature.settings.perception

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.apps.AppDiscovery
import me.fleey.futon.data.apps.AppSettingsRepository
import me.fleey.futon.data.apps.DefaultAppCategory
import me.fleey.futon.data.apps.models.InstalledApp
import org.koin.android.annotation.KoinViewModel

data class AppDiscoveryUiState(
  val enabled: Boolean = true,
  val includeSystemApps: Boolean = false,
  val maxAppsInContext: Int = 30,
  val discoveredApps: List<InstalledApp> = emptyList(),
  val hiddenApps: List<String> = emptyList(),
  val appsExpanded: Boolean = false,
  val hiddenExpanded: Boolean = false,
  val isLoading: Boolean = true,
  val isLoadingApps: Boolean = false,
  val saveSuccess: Boolean = false,
)

sealed interface AppDiscoveryUiEvent {
  data class EnabledChanged(val enabled: Boolean) : AppDiscoveryUiEvent
  data class IncludeSystemAppsChanged(val include: Boolean) : AppDiscoveryUiEvent
  data class MaxAppsChanged(val max: Int) : AppDiscoveryUiEvent
  data class HideApp(val packageName: String) : AppDiscoveryUiEvent
  data class UnhideApp(val packageName: String) : AppDiscoveryUiEvent
  data class SetAppAliases(val packageName: String, val aliases: List<String>) : AppDiscoveryUiEvent
  data object ToggleAppsExpanded : AppDiscoveryUiEvent
  data object ToggleHiddenExpanded : AppDiscoveryUiEvent
  data object RefreshApps : AppDiscoveryUiEvent
  data object ResetToDefaults : AppDiscoveryUiEvent
  data object DismissSuccess : AppDiscoveryUiEvent
}

@KoinViewModel
class AppDiscoverySettingsViewModel(
  private val appSettingsRepository: AppSettingsRepository,
  private val appDiscovery: AppDiscovery,
) : ViewModel() {

  private val _uiState = MutableStateFlow(AppDiscoveryUiState())
  val uiState: StateFlow<AppDiscoveryUiState> = _uiState.asStateFlow()

  private var categories: Map<String, String?> = emptyMap()

  init {
    loadSettings()
  }

  private fun loadSettings() {
    viewModelScope.launch {
      appSettingsRepository.settings.collect { settings ->
        categories = settings.categories.associate { cat ->
          cat.id to cat.customName
        }
        _uiState.update {
          it.copy(
            enabled = settings.enabled,
            includeSystemApps = settings.includeSystemApps,
            maxAppsInContext = settings.maxAppsInContext,
            hiddenApps = settings.hiddenApps,
            isLoading = false,
          )
        }
      }
    }
  }

  fun getCategoryName(categoryId: String?, context: Context): String {
    if (categoryId == null) return ""

    val customName = categories[categoryId]
    if (customName != null) return customName

    return DefaultAppCategory.fromId(categoryId)?.let {
      context.getString(it.nameRes)
    } ?: categoryId
  }

  fun onEvent(event: AppDiscoveryUiEvent) {
    when (event) {
      is AppDiscoveryUiEvent.EnabledChanged -> updateEnabled(event.enabled)
      is AppDiscoveryUiEvent.IncludeSystemAppsChanged -> updateIncludeSystemApps(event.include)
      is AppDiscoveryUiEvent.MaxAppsChanged -> updateMaxApps(event.max)
      is AppDiscoveryUiEvent.HideApp -> hideApp(event.packageName)
      is AppDiscoveryUiEvent.UnhideApp -> unhideApp(event.packageName)
      is AppDiscoveryUiEvent.SetAppAliases -> setAppAliases(event.packageName, event.aliases)
      is AppDiscoveryUiEvent.ToggleAppsExpanded -> toggleAppsExpanded()
      is AppDiscoveryUiEvent.ToggleHiddenExpanded -> toggleHiddenExpanded()
      is AppDiscoveryUiEvent.RefreshApps -> refreshApps()
      is AppDiscoveryUiEvent.ResetToDefaults -> resetToDefaults()
      is AppDiscoveryUiEvent.DismissSuccess -> _uiState.update { it.copy(saveSuccess = false) }
    }
  }

  private fun updateEnabled(enabled: Boolean) {
    viewModelScope.launch {
      val current = appSettingsRepository.getSettings()
      appSettingsRepository.updateSettings(current.copy(enabled = enabled))
      showSaveSuccess()
    }
  }

  private fun updateIncludeSystemApps(include: Boolean) {
    viewModelScope.launch {
      val current = appSettingsRepository.getSettings()
      appSettingsRepository.updateSettings(current.copy(includeSystemApps = include))
      refreshApps()
      showSaveSuccess()
    }
  }

  private fun updateMaxApps(max: Int) {
    viewModelScope.launch {
      val current = appSettingsRepository.getSettings()
      appSettingsRepository.updateSettings(current.copy(maxAppsInContext = max))
      showSaveSuccess()
    }
  }

  private fun hideApp(packageName: String) {
    viewModelScope.launch {
      appSettingsRepository.hideApp(packageName)
      _uiState.update { state ->
        state.copy(
          discoveredApps = state.discoveredApps.filter { it.packageName != packageName },
        )
      }
      showSaveSuccess()
    }
  }

  private fun unhideApp(packageName: String) {
    viewModelScope.launch {
      appSettingsRepository.unhideApp(packageName)
      refreshApps()
      showSaveSuccess()
    }
  }

  private fun setAppAliases(packageName: String, aliases: List<String>) {
    viewModelScope.launch {
      appSettingsRepository.setAppAliases(packageName, aliases)
      _uiState.update { state ->
        state.copy(
          discoveredApps = state.discoveredApps.map { app ->
            if (app.packageName == packageName) {
              app.copy(userAliases = aliases)
            } else app
          },
        )
      }
      showSaveSuccess()
    }
  }

  private fun toggleAppsExpanded() {
    val currentExpanded = _uiState.value.appsExpanded
    if (!currentExpanded && _uiState.value.discoveredApps.isEmpty()) {
      loadDiscoveredApps()
    }
    _uiState.update { it.copy(appsExpanded = !currentExpanded) }
  }

  private fun toggleHiddenExpanded() {
    _uiState.update { it.copy(hiddenExpanded = !it.hiddenExpanded) }
  }

  private fun loadDiscoveredApps() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingApps = true) }
      try {
        val apps = appDiscovery.getLaunchableApps()
        _uiState.update { it.copy(discoveredApps = apps, isLoadingApps = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoadingApps = false) }
      }
    }
  }

  private fun refreshApps() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingApps = true) }
      try {
        appDiscovery.refresh()
        val apps = appDiscovery.getLaunchableApps()
        _uiState.update { it.copy(discoveredApps = apps, isLoadingApps = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoadingApps = false) }
      }
    }
  }

  private fun resetToDefaults() {
    viewModelScope.launch {
      appSettingsRepository.resetToDefaults()
      refreshApps()
      showSaveSuccess()
    }
  }

  private fun showSaveSuccess() {
    _uiState.update { it.copy(saveSuccess = true) }
  }
}
