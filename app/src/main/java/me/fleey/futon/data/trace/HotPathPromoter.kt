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
package me.fleey.futon.data.trace

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.trace.db.HotPathCacheDao
import me.fleey.futon.data.trace.db.HotPathCacheEntity
import me.fleey.futon.data.trace.db.TraceDao
import me.fleey.futon.data.trace.models.CachedAction
import me.fleey.futon.data.trace.models.TraceId
import org.koin.core.annotation.Single

/**
 * Manages hot path promotion and storage pruning.
 *
 * Traces with success count >= 3 are eligible for hot path promotion.
 * Storage is pruned when it exceeds the configured limit (default 100MB).
 */
interface HotPathPromoter {
  /**
   * Check if a trace is eligible for hot path promotion.
   *
   * @param traceId The trace to check
   * @return true if eligible (success count >= 3)
   */
  suspend fun isEligibleForPromotion(traceId: TraceId): Boolean

  /**
   * Promote a trace to hot path rules.
   *
   * @param traceId The trace to promote
   * @return Result indicating success or failure
   */
  suspend fun promote(traceId: TraceId): Result<Unit>

  /**
   * Auto-promote all eligible traces.
   *
   * @return Number of traces promoted
   */
  suspend fun autoPromoteEligible(): Int

  /**
   * Get all high-confidence hot path entries.
   *
   * @return List of cached actions with success count >= 3
   */
  suspend fun getHighConfidenceEntries(): List<CachedAction>

  /**
   * Observe high-confidence entries as a Flow.
   *
   * @return Flow of high-confidence cached actions
   */
  fun observeHighConfidenceEntries(): Flow<List<CachedAction>>

  /**
   * Prune storage to stay within limits.
   *
   * @param maxStorageBytes Maximum storage size (default 100MB)
   */
  suspend fun pruneStorage(maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES)

  /**
   * Get current storage usage.
   *
   * @return Storage size in bytes
   */
  suspend fun getStorageUsage(): Long

  /**
   * Check if storage pruning is needed.
   *
   * @param maxStorageBytes Maximum storage size
   * @return true if current storage exceeds limit
   */
  suspend fun needsPruning(maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES): Boolean

  companion object {
    const val DEFAULT_MAX_STORAGE_BYTES = 100L * 1024 * 1024
    const val PROMOTION_THRESHOLD = 3
  }
}

@Single(binds = [HotPathPromoter::class])
class HotPathPromoterImpl(
  private val traceRecorder: TraceRecorder,
  private val traceDao: TraceDao,
  private val hotPathCacheDao: HotPathCacheDao,
  private val json: kotlinx.serialization.json.Json,
) : HotPathPromoter {

  override suspend fun isEligibleForPromotion(traceId: TraceId): Boolean {
    val trace = traceRecorder.getTrace(traceId) ?: return false
    if (!trace.success) return false

    val successCounts = trace.steps.map { step ->
      val cached = hotPathCacheDao.getByUiHash(step.uiHash)
      cached?.successCount ?: 0
    }

    return successCounts.all { it >= HotPathPromoter.PROMOTION_THRESHOLD - 1 }
  }

  override suspend fun promote(traceId: TraceId): Result<Unit> {
    return traceRecorder.promoteToHotPath(traceId)
  }

  override suspend fun autoPromoteEligible(): Int {
    val successfulTraces = traceDao.getSuccessfulTraces()
    var promotedCount = 0

    for (traceEntity in successfulTraces) {
      val traceId = TraceId(traceEntity.id)
      if (isEligibleForPromotion(traceId)) {
        val result = promote(traceId)
        if (result.isSuccess) {
          promotedCount++
        }
      }
    }

    return promotedCount
  }

  override suspend fun getHighConfidenceEntries(): List<CachedAction> {
    return hotPathCacheDao.getHighConfidenceEntries().map { entity ->
      entity.toCachedAction()
    }
  }

  override fun observeHighConfidenceEntries(): Flow<List<CachedAction>> {
    return traceRecorder.observeHotPathEntries()
  }

  override suspend fun pruneStorage(maxStorageBytes: Long) {
    traceRecorder.pruneStorage(maxStorageBytes)
  }

  override suspend fun getStorageUsage(): Long {
    return traceRecorder.getStats().storageSizeBytes
  }

  override suspend fun needsPruning(maxStorageBytes: Long): Boolean {
    return getStorageUsage() > maxStorageBytes
  }

  private fun HotPathCacheEntity.toCachedAction(): CachedAction {
    val actionData = json.decodeFromString<me.fleey.futon.data.trace.models.ActionData>(actionData)
    return CachedAction(
      uiHash = uiHash,
      action = actionData.toAction(),
      confidence = confidence,
      successCount = successCount,
      lastUsed = lastUsed,
    )
  }
}
