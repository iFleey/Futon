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
package me.fleey.futon.data.settings.models

import kotlinx.serialization.Serializable

/**
 * Perception mode for UI understanding.
 * Determines how the system captures and analyzes UI structure.
 */
@Serializable
enum class PerceptionModeConfig {
  /**
   * Use only screenshot (visual) perception.
   * Works without root, relies on AI vision to understand UI.
   */
  SCREENSHOT_ONLY,

  /**
   * Use only UI tree (structural) perception.
   * Requires root access, provides precise element information.
   */
  UI_TREE_ONLY,

  /**
   * Use both screenshot and UI tree (hybrid mode).
   */
  HYBRID
}

/**
 * Settings for Hybrid UI perception functionality.
 * Combines visual (screenshot) and structural (UI tree) perception for more accurate element detection.
 * Root-Only architecture: Uses root-based UI tree capture.
 *
 * @property enabled Whether advanced perception mode is enabled (non-screenshot-only modes)
 * @property perceptionMode The selected perception mode
 * @property perceptionTimeoutMs Timeout for UI tree capture operations in milliseconds
 * @property adaptiveLearningEnabled Whether to enable adaptive learning cache for element matching
 * @property maxCacheSize Maximum number of cached element patterns
 * @property uiTreeMaxDepth Maximum depth of UI tree to include in AI context (reduces token usage)
 * @property includeNonInteractive Whether to include non-interactive nodes in AI context
 */
@Serializable
data class HybridPerceptionSettings(
  val enabled: Boolean = true,
  val perceptionMode: PerceptionModeConfig = PerceptionModeConfig.HYBRID,
  val perceptionTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
  val adaptiveLearningEnabled: Boolean = true,
  val maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE,
  val uiTreeMaxDepth: Int = DEFAULT_UI_TREE_MAX_DEPTH,
  val includeNonInteractive: Boolean = false,
) {
  fun isTimeoutValid(): Boolean = perceptionTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS

  fun isCacheSizeValid(): Boolean = maxCacheSize in MIN_CACHE_SIZE..MAX_CACHE_SIZE

  fun withValidTimeout(): HybridPerceptionSettings = copy(
    perceptionTimeoutMs = perceptionTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
  )

  fun withValidCacheSize(): HybridPerceptionSettings = copy(
    maxCacheSize = maxCacheSize.coerceIn(MIN_CACHE_SIZE, MAX_CACHE_SIZE),
  )

  fun requiresUITree(): Boolean = enabled && perceptionMode != PerceptionModeConfig.SCREENSHOT_ONLY

  fun requiresScreenshot(): Boolean =
    !enabled || perceptionMode != PerceptionModeConfig.UI_TREE_ONLY

  companion object {
    const val MIN_TIMEOUT_MS = 1000L

    const val MAX_TIMEOUT_MS = 10000L

    const val DEFAULT_TIMEOUT_MS = 5000L

    const val MIN_CACHE_SIZE = 100

    const val MAX_CACHE_SIZE = 2000

    const val DEFAULT_MAX_CACHE_SIZE = 1000

    /** Default UI tree max depth (limits token usage) */
    const val DEFAULT_UI_TREE_MAX_DEPTH = 8

    const val MIN_UI_TREE_DEPTH = 3

    const val MAX_UI_TREE_DEPTH = 20
  }
}
