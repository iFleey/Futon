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

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.prompt.models.PromptCategory
import me.fleey.futon.data.prompt.models.PromptTemplate
import me.fleey.futon.data.prompt.models.PromptVariable
import org.koin.android.annotation.KoinViewModel

data class PromptEditorUiState(
  val templateId: String? = null,
  val name: String = "",
  val description: String = "",
  val content: String = "",
  val isBuiltIn: Boolean = false,
  val isReadOnly: Boolean = false,
  val availableVariables: List<PromptVariable> = emptyList(),
  val insertionRequest: String? = null,
  val isLoading: Boolean = false,
  val saveSuccess: Boolean = false,
  val error: PromptEditorError? = null,
) {
  val canSave: Boolean
    get() = name.isNotBlank() && content.isNotBlank() && !isReadOnly
}

sealed interface PromptEditorError {
  @get:StringRes
  val messageRes: Int

  data object NameRequired : PromptEditorError {
    override val messageRes = R.string.prompt_error_name_required
  }

  data object ContentRequired : PromptEditorError {
    override val messageRes = R.string.prompt_error_content_required
  }

  data object SaveFailed : PromptEditorError {
    override val messageRes = R.string.prompt_error_save_failed
  }
}

sealed interface PromptEditorUiEvent {
  data class NameChanged(val value: String) : PromptEditorUiEvent
  data class DescriptionChanged(val value: String) : PromptEditorUiEvent
  data class ContentChanged(val value: String) : PromptEditorUiEvent
  data class InsertVariable(val placeholder: String) : PromptEditorUiEvent
  data object ClearInsertionRequest : PromptEditorUiEvent
  data object Save : PromptEditorUiEvent
  data object DismissError : PromptEditorUiEvent
}

@KoinViewModel
class PromptEditorViewModel(
  private val promptRepository: PromptRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(PromptEditorUiState())
  val uiState: StateFlow<PromptEditorUiState> = _uiState.asStateFlow()

  init {
    _uiState.update {
      it.copy(availableVariables = promptRepository.getAvailableVariables())
    }
  }

  fun loadTemplate(templateId: String?) {
    if (templateId == null) {
      _uiState.update {
        it.copy(
          templateId = null,
          name = "",
          description = "",
          content = "",
          isBuiltIn = false,
          isReadOnly = false,
        )
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      try {
        val settings = promptRepository.getPromptSettingsOnce()
        val template = settings.templates.find { it.id == templateId }
        if (template != null) {
          _uiState.update {
            it.copy(
              templateId = template.id,
              name = template.name,
              description = template.description,
              content = template.content,
              isBuiltIn = template.isBuiltIn,
              isReadOnly = template.isBuiltIn,
              isLoading = false,
            )
          }
        } else {
          _uiState.update { it.copy(isLoading = false) }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, error = PromptEditorError.SaveFailed)
        }
      }
    }
  }

  fun onEvent(event: PromptEditorUiEvent) {
    when (event) {
      is PromptEditorUiEvent.NameChanged -> _uiState.update { it.copy(name = event.value) }
      is PromptEditorUiEvent.DescriptionChanged -> _uiState.update { it.copy(description = event.value) }
      is PromptEditorUiEvent.ContentChanged -> _uiState.update { it.copy(content = event.value) }
      is PromptEditorUiEvent.InsertVariable -> _uiState.update { it.copy(insertionRequest = event.placeholder) }
      PromptEditorUiEvent.ClearInsertionRequest -> _uiState.update { it.copy(insertionRequest = null) }
      PromptEditorUiEvent.Save -> save()
      PromptEditorUiEvent.DismissError -> _uiState.update { it.copy(error = null) }
    }
  }

  private fun save() {
    val state = _uiState.value
    if (state.isReadOnly) return

    when {
      state.name.isBlank() -> {
        _uiState.update { it.copy(error = PromptEditorError.NameRequired) }
        return
      }

      state.content.isBlank() -> {
        _uiState.update { it.copy(error = PromptEditorError.ContentRequired) }
        return
      }
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      try {
        val template = PromptTemplate(
          id = state.templateId ?: PromptTemplate.generateId(),
          name = state.name.trim(),
          description = state.description.trim(),
          content = state.content,
          isBuiltIn = false,
          category = PromptCategory.CUSTOM,
        )

        if (state.templateId != null) {
          promptRepository.updateTemplate(template)
        } else {
          promptRepository.addTemplate(template)
        }

        _uiState.update { it.copy(isLoading = false, saveSuccess = true) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoading = false, error = PromptEditorError.SaveFailed) }
      }
    }
  }
}
