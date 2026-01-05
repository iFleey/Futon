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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.R
import me.fleey.futon.data.prompt.models.PromptCategory
import me.fleey.futon.data.prompt.models.PromptSettings
import me.fleey.futon.data.prompt.models.PromptTemplate
import me.fleey.futon.data.prompt.models.PromptVariable
import me.fleey.futon.data.prompt.models.QuickPhrase
import me.fleey.futon.data.settings.models.AISettings

import org.koin.core.annotation.Single

@Single(binds = [PromptRepository::class])
class PromptRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
) : PromptRepository {

  private object Keys {
    val PROMPT_SETTINGS = stringPreferencesKey("prompt_settings")
  }

  override fun getPromptSettings(): Flow<PromptSettings> = dataStore.data.map { prefs ->
    prefs[Keys.PROMPT_SETTINGS]?.let { jsonStr ->
      try {
        json.decodeFromString<PromptSettings>(jsonStr)
      } catch (e: Exception) {
        createDefaultSettings()
      }
    } ?: createDefaultSettings()
  }

  override suspend fun getPromptSettingsOnce(): PromptSettings = getPromptSettings().first()

  override suspend fun addTemplate(template: PromptTemplate) {
    updateSettings { settings ->
      settings.copy(templates = settings.templates + template)
    }
  }

  override suspend fun updateTemplate(template: PromptTemplate) {
    updateSettings { settings ->
      settings.copy(
        templates = settings.templates.map {
          if (it.id == template.id) template.copy(updatedAt = System.currentTimeMillis())
          else it
        },
      )
    }
  }

  override suspend fun deleteTemplate(id: String) {
    updateSettings { settings ->
      val newActiveId =
        if (settings.activeSystemPromptId == id) null else settings.activeSystemPromptId
      settings.copy(
        templates = settings.templates.filter { it.id != id },
        activeSystemPromptId = newActiveId,
      )
    }
  }

  override suspend fun setActiveSystemPrompt(id: String?) {
    updateSettings { settings ->
      settings.copy(activeSystemPromptId = id)
    }
  }

  override suspend fun addQuickPhrase(phrase: QuickPhrase) {
    updateSettings { settings ->
      settings.copy(quickPhrases = settings.quickPhrases + phrase)
    }
  }

  override suspend fun updateQuickPhrase(phrase: QuickPhrase) {
    updateSettings { settings ->
      settings.copy(
        quickPhrases = settings.quickPhrases.map {
          if (it.id == phrase.id) phrase else it
        },
      )
    }
  }

  override suspend fun deleteQuickPhrase(id: String) {
    updateSettings { settings ->
      settings.copy(quickPhrases = settings.quickPhrases.filter { it.id != id })
    }
  }

  override suspend fun setQuickPhrasesEnabled(enabled: Boolean) {
    updateSettings { settings ->
      settings.copy(enableQuickPhrases = enabled)
    }
  }

  override fun getAvailableVariables(): List<PromptVariable> = listOf(
    // Task context
    PromptVariable(
      key = "task",
      displayNameRes = R.string.prompt_var_task_name,
      descriptionRes = R.string.prompt_var_task_desc,
      example = "Open Settings app",
    ),
    PromptVariable(
      key = "step_number",
      displayNameRes = R.string.prompt_var_step_number_name,
      descriptionRes = R.string.prompt_var_step_number_desc,
      example = "3",
    ),
    PromptVariable(
      key = "max_steps",
      displayNameRes = R.string.prompt_var_max_steps_name,
      descriptionRes = R.string.prompt_var_max_steps_desc,
      example = "20",
    ),

    // App context
    PromptVariable(
      key = "app_name",
      displayNameRes = R.string.prompt_var_app_name_name,
      descriptionRes = R.string.prompt_var_app_name_desc,
      example = "Chrome",
    ),
    PromptVariable(
      key = "app_package",
      displayNameRes = R.string.prompt_var_app_package_name,
      descriptionRes = R.string.prompt_var_app_package_desc,
      example = "com.android.chrome",
    ),
    PromptVariable(
      key = "app_activity",
      displayNameRes = R.string.prompt_var_app_activity_name,
      descriptionRes = R.string.prompt_var_app_activity_desc,
      example = "MainActivity",
    ),

    // Date & Time
    PromptVariable(
      key = "timestamp",
      displayNameRes = R.string.prompt_var_timestamp_name,
      descriptionRes = R.string.prompt_var_timestamp_desc,
      example = "2025-12-26 10:30:00",
    ),
    PromptVariable(
      key = "date",
      displayNameRes = R.string.prompt_var_date_name,
      descriptionRes = R.string.prompt_var_date_desc,
      example = "2025-12-26",
    ),
    PromptVariable(
      key = "time",
      displayNameRes = R.string.prompt_var_time_name,
      descriptionRes = R.string.prompt_var_time_desc,
      example = "10:30:00",
    ),
    PromptVariable(
      key = "day_of_week",
      displayNameRes = R.string.prompt_var_day_of_week_name,
      descriptionRes = R.string.prompt_var_day_of_week_desc,
      example = "Thursday",
    ),
    PromptVariable(
      key = "timezone",
      displayNameRes = R.string.prompt_var_timezone_name,
      descriptionRes = R.string.prompt_var_timezone_desc,
      example = "Asia/Shanghai",
    ),

    // Device info
    PromptVariable(
      key = "device_model",
      displayNameRes = R.string.prompt_var_device_model_name,
      descriptionRes = R.string.prompt_var_device_model_desc,
      example = "Pixel 8 Pro",
    ),
    PromptVariable(
      key = "device_brand",
      displayNameRes = R.string.prompt_var_device_brand_name,
      descriptionRes = R.string.prompt_var_device_brand_desc,
      example = "Google",
    ),
    PromptVariable(
      key = "android_version",
      displayNameRes = R.string.prompt_var_android_version_name,
      descriptionRes = R.string.prompt_var_android_version_desc,
      example = "14",
    ),
    PromptVariable(
      key = "sdk_version",
      displayNameRes = R.string.prompt_var_sdk_version_name,
      descriptionRes = R.string.prompt_var_sdk_version_desc,
      example = "34",
    ),

    // Screen info
    PromptVariable(
      key = "screen_size",
      displayNameRes = R.string.prompt_var_screen_size_name,
      descriptionRes = R.string.prompt_var_screen_size_desc,
      example = "1080x2400",
    ),
    PromptVariable(
      key = "screen_density",
      displayNameRes = R.string.prompt_var_screen_density_name,
      descriptionRes = R.string.prompt_var_screen_density_desc,
      example = "440",
    ),
    PromptVariable(
      key = "orientation",
      displayNameRes = R.string.prompt_var_orientation_name,
      descriptionRes = R.string.prompt_var_orientation_desc,
      example = "portrait",
    ),

    // AI model info
    PromptVariable(
      key = "model_name",
      displayNameRes = R.string.prompt_var_model_name_name,
      descriptionRes = R.string.prompt_var_model_name_desc,
      example = "gpt-4o",
    ),
    PromptVariable(
      key = "provider_name",
      displayNameRes = R.string.prompt_var_provider_name_name,
      descriptionRes = R.string.prompt_var_provider_name_desc,
      example = "OpenAI",
    ),

    // Locale & Language
    PromptVariable(
      key = "locale",
      displayNameRes = R.string.prompt_var_locale_name,
      descriptionRes = R.string.prompt_var_locale_desc,
      example = "zh_CN",
    ),
    PromptVariable(
      key = "language",
      displayNameRes = R.string.prompt_var_language_name,
      descriptionRes = R.string.prompt_var_language_desc,
      example = "Chinese",
    ),

    // System state
    PromptVariable(
      key = "battery_level",
      displayNameRes = R.string.prompt_var_battery_level_name,
      descriptionRes = R.string.prompt_var_battery_level_desc,
      example = "85",
    ),
    PromptVariable(
      key = "network_type",
      displayNameRes = R.string.prompt_var_network_type_name,
      descriptionRes = R.string.prompt_var_network_type_desc,
      example = "WiFi",
    ),

    // Session info
    PromptVariable(
      key = "session_id",
      displayNameRes = R.string.prompt_var_session_id_name,
      descriptionRes = R.string.prompt_var_session_id_desc,
      example = "abc123",
    ),
    PromptVariable(
      key = "elapsed_time",
      displayNameRes = R.string.prompt_var_elapsed_time_name,
      descriptionRes = R.string.prompt_var_elapsed_time_desc,
      example = "15",
    ),
  )

  override fun resolveVariables(content: String, context: Map<String, String>): String {
    var result = content
    context.forEach { (key, value) ->
      result = result.replace("{{$key}}", value)
    }
    return result
  }

  override fun getBuiltInTemplates(): List<PromptTemplate> = listOf(
    PromptTemplate(
      id = "builtin_default",
      name = "Default System Prompt",
      content = AISettings.DEFAULT_SYSTEM_PROMPT,
      description = "Standard automation agent prompt",
      isBuiltIn = true,
      category = PromptCategory.SYSTEM,
    ),
    PromptTemplate(
      id = "builtin_concise",
      name = "Concise Mode",
      content = CONCISE_PROMPT,
      description = "Shorter responses, faster execution",
      isBuiltIn = true,
      category = PromptCategory.SYSTEM,
    ),
    PromptTemplate(
      id = "builtin_careful",
      name = "Careful Mode",
      content = CAREFUL_PROMPT,
      description = "More verification, safer operations",
      isBuiltIn = true,
      category = PromptCategory.SYSTEM,
    ),
  )

  private suspend fun updateSettings(transform: (PromptSettings) -> PromptSettings) {
    dataStore.edit { prefs ->
      val current = prefs[Keys.PROMPT_SETTINGS]?.let {
        try {
          json.decodeFromString<PromptSettings>(it)
        } catch (e: Exception) {
          createDefaultSettings()
        }
      } ?: createDefaultSettings()
      val updated = transform(current)
      prefs[Keys.PROMPT_SETTINGS] = json.encodeToString(updated)
    }
  }

  private fun createDefaultSettings(): PromptSettings = PromptSettings(
    templates = getBuiltInTemplates(),
    quickPhrases = getDefaultQuickPhrases(),
    activeSystemPromptId = "builtin_default",
    enableQuickPhrases = true,
  )

  private fun getDefaultQuickPhrases(): List<QuickPhrase> = listOf(
    QuickPhrase(
      id = "phrase_open",
      trigger = "/open",
      expansion = "Open the {{app_name}} app",
      description = "Quick open app command",
    ),
    QuickPhrase(
      id = "phrase_scroll",
      trigger = "/scroll",
      expansion = "Scroll down to find more content",
      description = "Scroll action",
    ),
    QuickPhrase(
      id = "phrase_back",
      trigger = "/back",
      expansion = "Go back to the previous screen",
      description = "Navigate back",
    ),
  )

  companion object {
    private const val CONCISE_PROMPT =
      """You are Futon, an Android automation agent. Analyze UI and execute actions efficiently.

## Output Format
<think>{brief reasoning}</think><answer>{action_json}</answer>

## Actions
- tap: {"x":int,"y":int}
- long_press: {"x":int,"y":int,"duration":int}
- double_tap: {"x":int,"y":int}
- swipe: {"x1":int,"y1":int,"x2":int,"y2":int}
- scroll: {"x":int,"y":int,"direction":"up/down/left/right","distance":int}
- input: {"text":"string"}
- launch_app: {"text":"package_or_name"} - USE FIRST for opening apps!
- back/home/recents/notifications/quick_settings: {}
- wait: {"duration":int}
- call: {"command":"cmd","args":{...}}
- complete: {"message":"result"}
- error: {"message":"reason"}

## Rules
- Use launch_app FIRST for opening any app
- Use DSP coordinates when available
- Use @[x,y] from UI structure
- Set taskComplete=true only when done"""

    private const val CAREFUL_PROMPT =
      """You are Futon, a careful Android automation agent. Verify before each action.

## Output Format
<think>{detailed reasoning with verification}</think><answer>{action_json}</answer>

## Verification Steps
1. Confirm target element exists and is interactable
2. Check if action might cause data loss or irreversible changes
3. Prefer safer alternatives when available
4. If confidence < 70%, use wait and re-analyze

## Actions
- tap: {"x":int,"y":int}
- long_press: {"x":int,"y":int,"duration":int}
- double_tap: {"x":int,"y":int}
- swipe: {"x1":int,"y1":int,"x2":int,"y2":int}
- scroll: {"x":int,"y":int,"direction":"up/down/left/right","distance":int}
- input: {"text":"string"}
- launch_app: {"text":"package_or_name"} - PRIORITY for opening apps!
- back/home/recents/notifications/quick_settings: {}
- wait: {"duration":int}
- intervene: {"reason":"string","hint":"string"} - For sensitive operations
- call: {"command":"cmd","args":{...}}
- complete: {"message":"result"}
- error: {"message":"reason"}

## Safety Rules
- NEVER tap "Delete", "Remove", "Pay" without explicit task instruction
- Use intervene for login, payment, or sensitive screens
- Avoid actions on payment screens unless specifically requested
- If uncertain, use wait and re-analyze

## Decision Priority
1. launch_app for opening apps (fastest, most reliable)
2. DSP coordinates (highest accuracy)
3. UI Structure @[x,y] (element centers)
4. OCR text matching
5. Visual analysis (fallback)"""
  }
}
