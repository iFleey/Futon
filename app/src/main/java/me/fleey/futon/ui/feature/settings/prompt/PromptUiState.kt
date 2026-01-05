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
import me.fleey.futon.R
import me.fleey.futon.data.prompt.models.PromptCategory
import me.fleey.futon.data.prompt.models.PromptTemplate
import me.fleey.futon.data.prompt.models.PromptVariable
import me.fleey.futon.data.prompt.models.QuickPhrase

data class PromptUiState(
  val templates: List<PromptTemplate> = emptyList(),
  val quickPhrases: List<QuickPhrase> = emptyList(),
  val availableVariables: List<PromptVariable> = emptyList(),
  val activeSystemPromptId: String? = null,
  val enableQuickPhrases: Boolean = true,
  val selectedTab: PromptTab = PromptTab.TEMPLATES,
  val isLoading: Boolean = true,
  val error: PromptError? = null,
  val showDeleteConfirmation: DeleteTarget? = null,
) {
  val systemTemplates: List<PromptTemplate>
    get() = templates.filter { it.category == PromptCategory.SYSTEM }

  val customTemplates: List<PromptTemplate>
    get() = templates.filter { it.category == PromptCategory.CUSTOM }

  val activeTemplate: PromptTemplate?
    get() = templates.find { it.id == activeSystemPromptId }
}

enum class PromptTab {
  TEMPLATES,
  QUICK_PHRASES
}

sealed interface DeleteTarget {
  data class Template(val id: String, val name: String) : DeleteTarget
  data class QuickPhrase(val id: String, val trigger: String) : DeleteTarget
}

sealed interface PromptError {
  @get:StringRes
  val messageRes: Int

  data object SaveFailed : PromptError {
    override val messageRes = R.string.prompt_error_save_failed
  }

  data object DeleteFailed : PromptError {
    override val messageRes = R.string.prompt_error_delete_failed
  }

  data object InvalidTemplate : PromptError {
    override val messageRes = R.string.prompt_error_invalid_template
  }
}

sealed interface PromptUiEvent {
  data class TabSelected(val tab: PromptTab) : PromptUiEvent
  data class SetActivePrompt(val id: String?) : PromptUiEvent
  data class ToggleTemplateEnabled(val id: String) : PromptUiEvent
  data class DeleteTemplate(val id: String) : PromptUiEvent
  data class ToggleQuickPhraseEnabled(val id: String) : PromptUiEvent
  data class DeleteQuickPhrase(val id: String) : PromptUiEvent
  data class SetQuickPhrasesEnabled(val enabled: Boolean) : PromptUiEvent
  data class ShowDeleteConfirmation(val target: DeleteTarget) : PromptUiEvent
  data object DismissDeleteConfirmation : PromptUiEvent
  data object ConfirmDelete : PromptUiEvent
  data object DismissError : PromptUiEvent
}
