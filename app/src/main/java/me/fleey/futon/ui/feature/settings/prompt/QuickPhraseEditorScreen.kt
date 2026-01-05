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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.R
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.prompt.models.QuickPhrase
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.android.annotation.KoinViewModel
import org.koin.androidx.compose.koinViewModel

data class QuickPhraseEditorUiState(
  val phraseId: String? = null,
  val trigger: String = "",
  val expansion: String = "",
  val description: String = "",
  val isLoading: Boolean = false,
  val saveSuccess: Boolean = false,
  val error: QuickPhraseEditorError? = null,
) {
  val canSave: Boolean
    get() = trigger.isNotBlank() && expansion.isNotBlank()
}

sealed interface QuickPhraseEditorError {
  @get:StringRes
  val messageRes: Int

  data object TriggerRequired : QuickPhraseEditorError {
    override val messageRes = R.string.prompt_error_trigger_required
  }

  data object ExpansionRequired : QuickPhraseEditorError {
    override val messageRes = R.string.prompt_error_expansion_required
  }

  data object SaveFailed : QuickPhraseEditorError {
    override val messageRes = R.string.prompt_error_save_failed
  }
}

sealed interface QuickPhraseEditorUiEvent {
  data class TriggerChanged(val value: String) : QuickPhraseEditorUiEvent
  data class ExpansionChanged(val value: String) : QuickPhraseEditorUiEvent
  data class DescriptionChanged(val value: String) : QuickPhraseEditorUiEvent
  data object Save : QuickPhraseEditorUiEvent
  data object DismissError : QuickPhraseEditorUiEvent
}

@KoinViewModel
class QuickPhraseEditorViewModel(
  private val promptRepository: PromptRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(QuickPhraseEditorUiState())
  val uiState: StateFlow<QuickPhraseEditorUiState> = _uiState.asStateFlow()

  fun loadPhrase(phraseId: String?) {
    if (phraseId == null) {
      _uiState.update {
        it.copy(
          phraseId = null,
          trigger = "/",
          expansion = "",
          description = "",
        )
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      try {
        val settings = promptRepository.getPromptSettingsOnce()
        val phrase = settings.quickPhrases.find { it.id == phraseId }
        if (phrase != null) {
          _uiState.update {
            it.copy(
              phraseId = phrase.id,
              trigger = phrase.trigger,
              expansion = phrase.expansion,
              description = phrase.description,
              isLoading = false,
            )
          }
        } else {
          _uiState.update { it.copy(isLoading = false) }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, error = QuickPhraseEditorError.SaveFailed)
        }
      }
    }
  }

  fun onEvent(event: QuickPhraseEditorUiEvent) {
    when (event) {
      is QuickPhraseEditorUiEvent.TriggerChanged -> _uiState.update { it.copy(trigger = event.value) }
      is QuickPhraseEditorUiEvent.ExpansionChanged -> _uiState.update { it.copy(expansion = event.value) }
      is QuickPhraseEditorUiEvent.DescriptionChanged -> _uiState.update { it.copy(description = event.value) }
      QuickPhraseEditorUiEvent.Save -> save()
      QuickPhraseEditorUiEvent.DismissError -> _uiState.update { it.copy(error = null) }
    }
  }

  private fun save() {
    val state = _uiState.value

    when {
      state.trigger.isBlank() -> {
        _uiState.update { it.copy(error = QuickPhraseEditorError.TriggerRequired) }
        return
      }

      state.expansion.isBlank() -> {
        _uiState.update { it.copy(error = QuickPhraseEditorError.ExpansionRequired) }
        return
      }
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      try {
        val phrase = QuickPhrase(
          id = state.phraseId ?: QuickPhrase.generateId(),
          trigger = state.trigger.trim(),
          expansion = state.expansion.trim(),
          description = state.description.trim(),
        )

        if (state.phraseId != null) {
          promptRepository.updateQuickPhrase(phrase)
        } else {
          promptRepository.addQuickPhrase(phrase)
        }

        _uiState.update { it.copy(isLoading = false, saveSuccess = true) }
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoading = false, error = QuickPhraseEditorError.SaveFailed) }
      }
    }
  }
}

@Composable
fun QuickPhraseEditorScreen(
  phraseId: String?,
  onBack: () -> Unit,
  viewModel: QuickPhraseEditorViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(phraseId) {
    viewModel.loadPhrase(phraseId)
  }

  uiState.error?.let { error ->
    val message = stringResource(error.messageRes)
    LaunchedEffect(error) {
      snackbarHostState.showSnackbar(message)
      viewModel.onEvent(QuickPhraseEditorUiEvent.DismissError)
    }
  }

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      onBack()
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(
          if (phraseId == null) R.string.prompt_phrase_editor_title_new
          else R.string.prompt_phrase_editor_title_edit,
        ),
        onBackClick = onBack,
        actions = {
          TextButton(
            onClick = { viewModel.onEvent(QuickPhraseEditorUiEvent.Save) },
            enabled = uiState.canSave,
          ) {
            Text(stringResource(R.string.common_save))
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    ) {
      OutlinedTextField(
        value = uiState.trigger,
        onValueChange = { viewModel.onEvent(QuickPhraseEditorUiEvent.TriggerChanged(it)) },
        label = { Text(stringResource(R.string.prompt_phrase_trigger)) },
        placeholder = { Text(stringResource(R.string.prompt_phrase_trigger_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = stringResource(R.string.prompt_phrase_trigger_hint),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )

      Spacer(modifier = Modifier.height(16.dp))

      OutlinedTextField(
        value = uiState.expansion,
        onValueChange = { viewModel.onEvent(QuickPhraseEditorUiEvent.ExpansionChanged(it)) },
        label = { Text(stringResource(R.string.prompt_phrase_expansion)) },
        modifier = Modifier
          .fillMaxWidth()
          .height(150.dp),
      )

      Spacer(modifier = Modifier.height(16.dp))

      OutlinedTextField(
        value = uiState.description,
        onValueChange = { viewModel.onEvent(QuickPhraseEditorUiEvent.DescriptionChanged(it)) },
        label = { Text(stringResource(R.string.prompt_phrase_description)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )

      Spacer(modifier = Modifier.height(24.dp))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FutonTheme.colors.backgroundSecondary),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = stringResource(R.string.prompt_phrase_preview),
            style = MaterialTheme.typography.titleSmall,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.prompt_phrase_preview_input, uiState.trigger),
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
          Icon(
            imageVector = FutonIcons.ExpandMore,
            contentDescription = null,
            tint = FutonTheme.colors.interactiveActive,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = uiState.expansion.ifBlank { "..." },
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textNormal,
          )
        }
      }
    }
  }
}
