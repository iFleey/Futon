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

/**
 * Tracks daemon health statistics for monitoring failures and recovery.
 */
data class DaemonHealth(
  val crashCount: Int = 0,
  val restartCount: Int = 0,
  val lastCrashTimestamp: Long? = null,
  val lastRestartTimestamp: Long? = null,
  val consecutiveFailures: Int = 0,
  val totalAiCalls: Int = 0,
  val successfulAiCalls: Int = 0,
  val hotPathHits: Int = 0,
  val hotPathMisses: Int = 0,
  val isHealthy: Boolean = true,
  val healthMessage: String? = null,
) {
  val aiSuccessRate: Float
    get() = if (totalAiCalls > 0) successfulAiCalls.toFloat() / totalAiCalls else 0f

  val hotPathHitRate: Float
    get() {
      val total = hotPathHits + hotPathMisses
      return if (total > 0) hotPathHits.toFloat() / total else 0f
    }

  fun recordCrash(): DaemonHealth = copy(
    crashCount = crashCount + 1,
    lastCrashTimestamp = System.currentTimeMillis(),
    consecutiveFailures = consecutiveFailures + 1,
    isHealthy = false,
    healthMessage = "Daemon crashed",
  )

  fun recordRestart(): DaemonHealth = copy(
    restartCount = restartCount + 1,
    lastRestartTimestamp = System.currentTimeMillis(),
  )

  fun recordRecovery(): DaemonHealth = copy(
    consecutiveFailures = 0,
    isHealthy = true,
    healthMessage = null,
  )

  fun recordAiCall(success: Boolean): DaemonHealth = copy(
    totalAiCalls = totalAiCalls + 1,
    successfulAiCalls = if (success) successfulAiCalls + 1 else successfulAiCalls,
  )

  fun recordHotPathResult(hit: Boolean): DaemonHealth = copy(
    hotPathHits = if (hit) hotPathHits + 1 else hotPathHits,
    hotPathMisses = if (!hit) hotPathMisses + 1 else hotPathMisses,
  )

  fun markUnhealthy(message: String): DaemonHealth = copy(
    isHealthy = false,
    healthMessage = message,
  )

  companion object {
    val INITIAL = DaemonHealth()
  }
}
