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
package me.fleey.futon.data.settings

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.privacy.models.PrivacyMode
import me.fleey.futon.data.settings.models.AISettings
import me.fleey.futon.data.settings.models.AppLanguage
import me.fleey.futon.data.settings.models.DspPerceptionSettings
import me.fleey.futon.data.settings.models.ExecutionSettings
import me.fleey.futon.data.settings.models.HybridPerceptionSettings
import me.fleey.futon.data.settings.models.PerceptionModeConfig
import me.fleey.futon.data.settings.models.SomSettings
import me.fleey.futon.data.settings.models.ThemeMode
import me.fleey.futon.data.settings.models.ThemePreferences
import me.fleey.futon.platform.input.models.InjectionMode
import me.fleey.futon.platform.input.models.InputMethod

/**
 * Repository for managing app settings.
 */
interface SettingsRepository {
  // AI Settings
  fun getSettingsFlow(): Flow<AISettings>
  suspend fun getSettings(): AISettings
  suspend fun updateSettings(settings: AISettings)
  suspend fun clearSettings()

  // Theme preferences
  fun getThemePreferencesFlow(): Flow<ThemePreferences>
  suspend fun getThemePreferences(): ThemePreferences
  suspend fun setThemeMode(mode: ThemeMode)
  suspend fun setDynamicColorEnabled(enabled: Boolean)
  suspend fun setAppLanguage(language: AppLanguage)

  // Hybrid perception settings
  fun getHybridPerceptionSettingsFlow(): Flow<HybridPerceptionSettings>
  suspend fun getHybridPerceptionSettings(): HybridPerceptionSettings
  suspend fun updateHybridPerceptionSettings(settings: HybridPerceptionSettings)
  suspend fun setHybridPerceptionEnabled(enabled: Boolean)
  suspend fun setPerceptionTimeout(timeoutMs: Long)
  suspend fun setAdaptiveLearningEnabled(enabled: Boolean)
  suspend fun setPerceptionMode(mode: PerceptionModeConfig)
  suspend fun setUITreeMaxDepth(depth: Int)
  suspend fun setIncludeNonInteractive(include: Boolean)

  // Execution settings
  fun getExecutionSettingsFlow(): Flow<ExecutionSettings>
  suspend fun getExecutionSettings(): ExecutionSettings
  suspend fun updateExecutionSettings(settings: ExecutionSettings)
  suspend fun setPreferredInputMethod(method: InputMethod?)
  suspend fun setPreferredCaptureMethod(method: CaptureMethod?)
  suspend fun setEnableFallback(enabled: Boolean)
  suspend fun setPrivacyMode(mode: PrivacyMode)
  suspend fun setAuditLogEnabled(enabled: Boolean)
  suspend fun setShowCapabilityWarnings(show: Boolean)

  // DSP perception settings
  fun getDspPerceptionSettingsFlow(): Flow<DspPerceptionSettings>
  suspend fun getDspPerceptionSettings(): DspPerceptionSettings
  suspend fun updateDspPerceptionSettings(settings: DspPerceptionSettings)
  suspend fun setInjectionMode(mode: InjectionMode)
  suspend fun setDownscaleFactor(factor: Int)
  suspend fun setTargetLatency(latencyMs: Long)
  suspend fun setOcrEnabled(enabled: Boolean)
  suspend fun setMinConfidence(confidence: Float)
  suspend fun setMaxConcurrentBuffers(buffers: Int)
  suspend fun setTouchDevicePath(path: String)

  // SoM settings
  fun getSomSettingsFlow(): Flow<SomSettings>
  suspend fun getSomSettings(): SomSettings
  suspend fun updateSomSettings(settings: SomSettings)
  suspend fun setSomEnabled(enabled: Boolean)
  suspend fun setSomRenderAnnotations(enabled: Boolean)
  suspend fun setSomIncludeUITree(enabled: Boolean)
  suspend fun setSomMinConfidence(confidence: Float)
  suspend fun setSomMaxElements(maxElements: Int)
}
