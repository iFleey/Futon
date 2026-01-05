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
package me.fleey.futon.ui.feature.settings.prompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.prompt.PromptRepository
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class PromptViewModel(
  private val promptRepository: PromptRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(PromptUiState())
  val uiState: StateFlow<PromptUiState> = _uiState.asStateFlow()

  init {
    loadData()
  }

  private fun loadData() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      try {
        promptRepository.getPromptSettings().collect { settings ->
          _uiState.update {
            it.copy(
              templates = settings.templates,
              quickPhrases = settings.quickPhrases,
              availableVariables = promptRepository.getAvailableVariables(),
              activeSystemPromptId = settings.activeSystemPromptId,
              enableQuickPhrases = settings.enableQuickPhrases,
              isLoading = false,
            )
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, error = PromptError.SaveFailed)
        }
      }
    }
  }

  fun onEvent(event: PromptUiEvent) {
    when (event) {
      is PromptUiEvent.TabSelected -> _uiState.update { it.copy(selectedTab = event.tab) }
      is PromptUiEvent.SetActivePrompt -> setActivePrompt(event.id)
      is PromptUiEvent.ToggleTemplateEnabled -> toggleTemplateEnabled(event.id)
      is PromptUiEvent.DeleteTemplate -> showDeleteConfirmation(
        DeleteTarget.Template(
          event.id,
          getTemplateName(event.id),
        ),
      )

      is PromptUiEvent.ToggleQuickPhraseEnabled -> toggleQuickPhraseEnabled(event.id)
      is PromptUiEvent.DeleteQuickPhrase -> showDeleteConfirmation(
        DeleteTarget.QuickPhrase(
          event.id,
          getPhraseTrigger(event.id),
        ),
      )

      is PromptUiEvent.SetQuickPhrasesEnabled -> setQuickPhrasesEnabled(event.enabled)
      is PromptUiEvent.ShowDeleteConfirmation -> _uiState.update { it.copy(showDeleteConfirmation = event.target) }
      PromptUiEvent.DismissDeleteConfirmation -> _uiState.update { it.copy(showDeleteConfirmation = null) }
      PromptUiEvent.ConfirmDelete -> confirmDelete()
      PromptUiEvent.DismissError -> _uiState.update { it.copy(error = null) }
    }
  }

  private fun setActivePrompt(id: String?) {
    viewModelScope.launch {
      try {
        promptRepository.setActiveSystemPrompt(id)
      } catch (e: Exception) {
        _uiState.update { it.copy(error = PromptError.SaveFailed) }
      }
    }
  }

  private fun toggleTemplateEnabled(id: String) {
    viewModelScope.launch {
      try {
        val template = _uiState.value.templates.find { it.id == id } ?: return@launch
        if (template.isBuiltIn) return@launch
        promptRepository.updateTemplate(template.copy(isEnabled = !template.isEnabled))
      } catch (e: Exception) {
        _uiState.update { it.copy(error = PromptError.SaveFailed) }
      }
    }
  }

  private fun toggleQuickPhraseEnabled(id: String) {
    viewModelScope.launch {
      try {
        val phrase = _uiState.value.quickPhrases.find { it.id == id } ?: return@launch
        promptRepository.updateQuickPhrase(phrase.copy(isEnabled = !phrase.isEnabled))
      } catch (e: Exception) {
        _uiState.update { it.copy(error = PromptError.SaveFailed) }
      }
    }
  }

  private fun setQuickPhrasesEnabled(enabled: Boolean) {
    viewModelScope.launch {
      try {
        promptRepository.setQuickPhrasesEnabled(enabled)
      } catch (e: Exception) {
        _uiState.update { it.copy(error = PromptError.SaveFailed) }
      }
    }
  }

  private fun showDeleteConfirmation(target: DeleteTarget) {
    _uiState.update { it.copy(showDeleteConfirmation = target) }
  }

  private fun confirmDelete() {
    val target = _uiState.value.showDeleteConfirmation ?: return
    viewModelScope.launch {
      try {
        when (target) {
          is DeleteTarget.Template -> promptRepository.deleteTemplate(target.id)
          is DeleteTarget.QuickPhrase -> promptRepository.deleteQuickPhrase(target.id)
        }
        _uiState.update { it.copy(showDeleteConfirmation = null) }
      } catch (e: Exception) {
        _uiState.update { it.copy(error = PromptError.DeleteFailed, showDeleteConfirmation = null) }
      }
    }
  }

  private fun getTemplateName(id: String): String =
    _uiState.value.templates.find { it.id == id }?.name ?: ""

  private fun getPhraseTrigger(id: String): String =
    _uiState.value.quickPhrases.find { it.id == id }?.trigger ?: ""
}
