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
package me.fleey.futon.domain.automation.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hot path rule types matching daemon's rule_parser.cpp schema.
 */
@Serializable
enum class HotPathRuleType {
  @SerialName("detection")
  DETECTION,

  @SerialName("ocr")
  OCR
}

@Serializable
enum class HotPathActionType {
  @SerialName("tap")
  TAP,

  @SerialName("swipe")
  SWIPE,

  @SerialName("wait")
  WAIT,

  @SerialName("complete")
  COMPLETE
}

@Serializable
data class OcrRoi(
  val x: Float = 0f,
  val y: Float = 0f,
  @SerialName("width")
  val width: Float = 0f,
  @SerialName("height")
  val height: Float = 0f,
) {
  fun isValid(): Boolean =
    width > 0f && height > 0f &&
      x >= 0f && y >= 0f &&
      x + width <= 1f && y + height <= 1f

  companion object {
    val FULL_SCREEN = OcrRoi(0f, 0f, 1f, 1f)
  }
}

@Serializable
data class HotPathRule(
  val id: String,
  @SerialName("type")
  val ruleType: HotPathRuleType,
  @SerialName("class_id")
  val classId: Int = -1,
  @SerialName("min_confidence")
  val minConfidence: Float = 0.5f,
  @SerialName("action")
  val actionType: HotPathActionType = HotPathActionType.TAP,
  @SerialName("tap_offset_x")
  val tapOffsetX: Float = 0f,
  @SerialName("tap_offset_y")
  val tapOffsetY: Float = 0f,
  @SerialName("min_interval_ms")
  val minIntervalMs: Int = 500,
  val roi: OcrRoi? = null,
  @SerialName("target")
  val targetText: String? = null,
  @SerialName("exact_match")
  val exactMatch: Boolean = true,
  @SerialName("case_sensitive")
  val caseSensitive: Boolean = false,
  @SerialName("tap_x")
  val tapX: Float = -1f,
  @SerialName("tap_y")
  val tapY: Float = -1f,
  val enabled: Boolean = true,
  val priority: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val successCount: Int = 0,
  val failureCount: Int = 0,
) {
  val isDetectionRule: Boolean get() = ruleType == HotPathRuleType.DETECTION
  val isOcrRule: Boolean get() = ruleType == HotPathRuleType.OCR

  fun isValid(): Boolean = when (ruleType) {
    HotPathRuleType.DETECTION -> classId >= 0 && minConfidence in 0f..1f
    HotPathRuleType.OCR -> roi?.isValid() == true && !targetText.isNullOrBlank()
  }

  companion object {
    fun detection(
      id: String,
      classId: Int,
      minConfidence: Float = 0.5f,
      actionType: HotPathActionType = HotPathActionType.TAP,
      tapOffsetX: Float = 0f,
      tapOffsetY: Float = 0f,
      minIntervalMs: Int = 500,
    ) = HotPathRule(
      id = id,
      ruleType = HotPathRuleType.DETECTION,
      classId = classId,
      minConfidence = minConfidence,
      actionType = actionType,
      tapOffsetX = tapOffsetX,
      tapOffsetY = tapOffsetY,
      minIntervalMs = minIntervalMs,
    )

    fun ocr(
      id: String,
      roi: OcrRoi,
      targetText: String,
      exactMatch: Boolean = true,
      caseSensitive: Boolean = false,
      actionType: HotPathActionType = HotPathActionType.TAP,
      tapX: Float = -1f,
      tapY: Float = -1f,
      minIntervalMs: Int = 500,
    ) = HotPathRule(
      id = id,
      ruleType = HotPathRuleType.OCR,
      roi = roi,
      targetText = targetText,
      exactMatch = exactMatch,
      caseSensitive = caseSensitive,
      actionType = actionType,
      tapX = tapX,
      tapY = tapY,
      minIntervalMs = minIntervalMs,
    )
  }
}

sealed interface HotPathMatchResult {
  data class Matched(
    val rule: HotPathRule,
    val matchedClassId: Int = -1,
    val matchedConfidence: Float = 0f,
    val matchedText: String? = null,
    val tapX: Int = 0,
    val tapY: Int = 0,
  ) : HotPathMatchResult

  data object NoMatch : HotPathMatchResult
}

/**
 * Status of hot path rule synchronization with daemon.
 */
sealed interface HotPathSyncStatus {
  data object Idle : HotPathSyncStatus
  data object Syncing : HotPathSyncStatus
  data class Synced(val ruleCount: Int, val timestamp: Long) : HotPathSyncStatus
  data class Failed(val error: String) : HotPathSyncStatus
}
