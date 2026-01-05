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
package me.fleey.futon.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes using Kotlin Serialization. */
sealed interface FutonRoute {

  /** Onboarding screen for first-time setup. */
  @Serializable
  data object Onboarding : FutonRoute

  @Serializable
  data object Task : FutonRoute

  @Serializable
  data object History : FutonRoute

  @Serializable
  data object Settings : FutonRoute

  @Serializable
  data class TaskWithPrefill(val prefill: String) : FutonRoute

  @Serializable
  data object SettingsAIProvider : FutonRoute

  @Serializable
  data class SettingsAIProviderDetail(val providerId: String) : FutonRoute

  @Serializable
  data object SettingsAppearance : FutonRoute

  @Serializable
  data object SettingsLanguage : FutonRoute

  @Serializable
  data object SettingsAutomation : FutonRoute

  @Serializable
  data object SettingsAdvanced : FutonRoute

  @Serializable
  data object SettingsLocalModel : FutonRoute

  @Serializable
  data object SettingsExecution : FutonRoute

  @Serializable
  data object SettingsPrivacy : FutonRoute

  @Serializable
  data object SettingsCapability : FutonRoute

  @Serializable
  data object SettingsSom : FutonRoute

  @Serializable
  data object SettingsAppDiscovery : FutonRoute

  @Serializable
  data object SettingsDaemon : FutonRoute

  @Serializable
  data object SettingsInferenceRouting : FutonRoute

  @Serializable
  data object SettingsInferenceMetrics : FutonRoute

  @Serializable
  data object SettingsIntegrations : FutonRoute

  /** Prompt management screens. */
  @Serializable
  data object SettingsPromptManagement : FutonRoute

  @Serializable
  data class SettingsPromptEditor(val templateId: String?) : FutonRoute

  @Serializable
  data class SettingsQuickPhraseEditor(val phraseId: String?) : FutonRoute

  @Serializable
  data class ExecutionLogDetail(val logId: String) : FutonRoute

  /** About screen. */
  @Serializable
  data object SettingsAbout : FutonRoute

  /** Debug screens (debug builds only). */
  @Serializable
  data object DebugDashboard : FutonRoute

  @Serializable
  data object DebugSecurityLogs : FutonRoute

  @Serializable
  data object DebugChaosTesting : FutonRoute
}
