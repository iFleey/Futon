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
package me.fleey.futon.data.prompt.models

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/**
 * Represents a prompt template with variable support.
 */
@Serializable
data class PromptTemplate(
  val id: String,
  val name: String,
  val content: String,
  val description: String = "",
  val isBuiltIn: Boolean = false,
  val isEnabled: Boolean = true,
  val category: PromptCategory = PromptCategory.CUSTOM,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  companion object {
    fun generateId(): String = "prompt_${System.currentTimeMillis()}"
  }
}

@Serializable
enum class PromptCategory {
  SYSTEM,
  TASK,
  CUSTOM
}

/**
 * Available variables that can be used in prompt templates.
 * Uses @StringRes for i18n support.
 */
data class PromptVariable(
  val key: String,
  @param:StringRes val displayNameRes: Int,
  @param:StringRes val descriptionRes: Int,
  val example: String,
) {
  val placeholder: String get() = "{{$key}}"
}

/**
 * Quick phrase for fast input.
 */
@Serializable
data class QuickPhrase(
  val id: String,
  val trigger: String,
  val expansion: String,
  val description: String = "",
  val isEnabled: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
) {
  companion object {
    fun generateId(): String = "phrase_${System.currentTimeMillis()}"
  }
}

/**
 * Prompt settings configuration.
 */
@Serializable
data class PromptSettings(
  val templates: List<PromptTemplate> = emptyList(),
  val quickPhrases: List<QuickPhrase> = emptyList(),
  val activeSystemPromptId: String? = null,
  val enableQuickPhrases: Boolean = true,
)
