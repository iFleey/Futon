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
package me.fleey.futon.domain.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.fleey.futon.domain.automation.models.HotPathActionType
import me.fleey.futon.domain.automation.models.HotPathRule
import me.fleey.futon.domain.automation.models.HotPathRuleType
import me.fleey.futon.domain.automation.models.HotPathSyncStatus
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

interface HotPathRegistry {
  val rules: StateFlow<List<HotPathRule>>
  val syncStatus: StateFlow<HotPathSyncStatus>
  val ruleCount: Int

  fun addRule(rule: HotPathRule): Boolean
  fun removeRule(ruleId: String): Boolean
  fun updateRule(rule: HotPathRule): Boolean
  fun getRule(ruleId: String): HotPathRule?
  fun getAllRules(): List<HotPathRule>
  fun getEnabledRules(): List<HotPathRule>
  fun clear()
  fun contains(ruleId: String): Boolean

  fun incrementSuccessCount(ruleId: String)
  fun incrementFailureCount(ruleId: String)
  fun demoteRule(ruleId: String)

  fun serializeToJson(): String
  fun loadFromJson(json: String): Result<Int>

  fun setSyncStatus(status: HotPathSyncStatus)
}

@Single(binds = [HotPathRegistry::class])
class HotPathRegistryImpl(
  private val json: Json = Json { ignoreUnknownKeys = true },
) : HotPathRegistry {

  private val rulesMap = ConcurrentHashMap<String, HotPathRule>()
  private val _rules = MutableStateFlow<List<HotPathRule>>(emptyList())
  private val _syncStatus = MutableStateFlow<HotPathSyncStatus>(HotPathSyncStatus.Idle)

  override val rules: StateFlow<List<HotPathRule>> = _rules.asStateFlow()
  override val syncStatus: StateFlow<HotPathSyncStatus> = _syncStatus.asStateFlow()
  override val ruleCount: Int get() = rulesMap.size

  override fun addRule(rule: HotPathRule): Boolean {
    if (!rule.isValid()) return false
    val previous = rulesMap.put(rule.id, rule)
    updateRulesFlow()
    return previous == null
  }

  override fun removeRule(ruleId: String): Boolean {
    val removed = rulesMap.remove(ruleId)
    if (removed != null) {
      updateRulesFlow()
    }
    return removed != null
  }

  override fun updateRule(rule: HotPathRule): Boolean {
    if (!rule.isValid()) return false
    if (!rulesMap.containsKey(rule.id)) return false
    rulesMap[rule.id] = rule
    updateRulesFlow()
    return true
  }

  override fun getRule(ruleId: String): HotPathRule? = rulesMap[ruleId]

  override fun getAllRules(): List<HotPathRule> =
    rulesMap.values.sortedBy { it.priority }

  override fun getEnabledRules(): List<HotPathRule> =
    rulesMap.values.filter { it.enabled }.sortedBy { it.priority }

  override fun clear() {
    rulesMap.clear()
    updateRulesFlow()
  }

  override fun contains(ruleId: String): Boolean = rulesMap.containsKey(ruleId)

  override fun incrementSuccessCount(ruleId: String) {
    rulesMap[ruleId]?.let { rule ->
      rulesMap[ruleId] = rule.copy(successCount = rule.successCount + 1)
      updateRulesFlow()
    }
  }

  override fun incrementFailureCount(ruleId: String) {
    rulesMap[ruleId]?.let { rule ->
      rulesMap[ruleId] = rule.copy(failureCount = rule.failureCount + 1)
      updateRulesFlow()
    }
  }

  override fun demoteRule(ruleId: String) {
    rulesMap[ruleId]?.let { rule ->
      rulesMap[ruleId] = rule.copy(enabled = false)
      updateRulesFlow()
    }
  }

  override fun serializeToJson(): String {
    val enabledRules = getEnabledRules()
    return serializeRulesToDaemonJson(enabledRules)
  }

  override fun loadFromJson(json: String): Result<Int> {
    return try {
      val rules = parseRulesFromJson(json)
      rulesMap.clear()
      rules.forEach { rule ->
        rulesMap[rule.id] = rule
      }
      updateRulesFlow()
      Result.success(rules.size)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override fun setSyncStatus(status: HotPathSyncStatus) {
    _syncStatus.value = status
  }

  private fun updateRulesFlow() {
    _rules.update { rulesMap.values.sortedBy { it.priority } }
  }

  private fun serializeRulesToDaemonJson(rules: List<HotPathRule>): String {
    val jsonArray = buildJsonArray {
      rules.forEach { rule ->
        add(buildRuleJsonObject(rule))
      }
    }
    return json.encodeToString(jsonArray)
  }

  private fun buildRuleJsonObject(rule: HotPathRule): JsonObject {
    return buildJsonObject {
      put("type", rule.ruleType.toJsonValue())
      put("action", rule.actionType.toJsonValue())
      put("tap_offset_x", rule.tapOffsetX)
      put("tap_offset_y", rule.tapOffsetY)
      put("min_interval_ms", rule.minIntervalMs)

      when (rule.ruleType) {
        HotPathRuleType.DETECTION -> {
          put("class_id", rule.classId)
          put("min_confidence", rule.minConfidence)
        }

        HotPathRuleType.OCR -> {
          rule.roi?.let { roi ->
            put(
              "roi",
              buildJsonObject {
                put("x", roi.x)
                put("y", roi.y)
                put("width", roi.width)
                put("height", roi.height)
              },
            )
          }
          rule.targetText?.let { put("target", it) }
          put("exact_match", rule.exactMatch)
          put("case_sensitive", rule.caseSensitive)
          if (rule.tapX >= 0f) put("tap_x", rule.tapX)
          if (rule.tapY >= 0f) put("tap_y", rule.tapY)
        }
      }
    }
  }

  private fun parseRulesFromJson(jsonString: String): List<HotPathRule> {
    val jsonArray = json.decodeFromString<JsonArray>(jsonString)
    return jsonArray.mapIndexed { index, element ->
      parseRuleFromJsonObject(element as JsonObject, index)
    }
  }

  private fun parseRuleFromJsonObject(obj: JsonObject, index: Int): HotPathRule {
    val typeStr = (obj["type"] as? JsonPrimitive)?.content ?: "detection"
    val ruleType = HotPathRuleType.entries.find {
      it.name.equals(typeStr, ignoreCase = true)
    } ?: HotPathRuleType.DETECTION

    val actionStr = (obj["action"] as? JsonPrimitive)?.content ?: "tap"
    val actionType = HotPathActionType.entries.find {
      it.name.equals(actionStr, ignoreCase = true)
    } ?: HotPathActionType.TAP

    return HotPathRule(
      id = "rule_$index",
      ruleType = ruleType,
      classId = (obj["class_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
      minConfidence = (obj["min_confidence"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0.5f,
      actionType = actionType,
      tapOffsetX = (obj["tap_offset_x"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
      tapOffsetY = (obj["tap_offset_y"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
      minIntervalMs = (obj["min_interval_ms"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 500,
      roi = (obj["roi"] as? JsonObject)?.let { roiObj ->
        me.fleey.futon.domain.automation.models.OcrRoi(
          x = (roiObj["x"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
          y = (roiObj["y"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
          width = (roiObj["width"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
          height = (roiObj["height"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f,
        )
      },
      targetText = (obj["target"] as? JsonPrimitive)?.content,
      exactMatch = (obj["exact_match"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
      caseSensitive = (obj["case_sensitive"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        ?: false,
      tapX = (obj["tap_x"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: -1f,
      tapY = (obj["tap_y"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: -1f,
      priority = index,
    )
  }

  private fun HotPathRuleType.toJsonValue(): String = when (this) {
    HotPathRuleType.DETECTION -> "detection"
    HotPathRuleType.OCR -> "ocr"
  }

  private fun HotPathActionType.toJsonValue(): String = when (this) {
    HotPathActionType.TAP -> "tap"
    HotPathActionType.SWIPE -> "swipe"
    HotPathActionType.WAIT -> "wait"
    HotPathActionType.COMPLETE -> "complete"
  }

  companion object {
    const val DEFAULT_MIN_CONFIDENCE = 0.5f
    const val DEFAULT_MIN_INTERVAL_MS = 500
  }
}
