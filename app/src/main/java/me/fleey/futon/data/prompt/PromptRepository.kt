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
package me.fleey.futon.data.prompt

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.prompt.models.PromptSettings
import me.fleey.futon.data.prompt.models.PromptTemplate
import me.fleey.futon.data.prompt.models.PromptVariable
import me.fleey.futon.data.prompt.models.QuickPhrase

interface PromptRepository {
  fun getPromptSettings(): Flow<PromptSettings>
  suspend fun getPromptSettingsOnce(): PromptSettings

  // Template operations
  suspend fun addTemplate(template: PromptTemplate)
  suspend fun updateTemplate(template: PromptTemplate)
  suspend fun deleteTemplate(id: String)
  suspend fun setActiveSystemPrompt(id: String?)

  // Quick phrase operations
  suspend fun addQuickPhrase(phrase: QuickPhrase)
  suspend fun updateQuickPhrase(phrase: QuickPhrase)
  suspend fun deleteQuickPhrase(id: String)
  suspend fun setQuickPhrasesEnabled(enabled: Boolean)

  // Variable support
  fun getAvailableVariables(): List<PromptVariable>
  fun resolveVariables(content: String, context: Map<String, String>): String

  // Built-in templates
  fun getBuiltInTemplates(): List<PromptTemplate>
}
