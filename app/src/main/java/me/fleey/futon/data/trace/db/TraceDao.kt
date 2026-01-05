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
package me.fleey.futon.data.trace.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TraceDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTrace(trace: TraceEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSteps(steps: List<TraceStepEntity>)

  @Transaction
  suspend fun insertTraceWithSteps(trace: TraceEntity, steps: List<TraceStepEntity>) {
    insertTrace(trace)
    insertSteps(steps)
  }

  @Query("SELECT * FROM traces WHERE id = :traceId")
  suspend fun getTrace(traceId: String): TraceEntity?

  @Query("SELECT * FROM trace_steps WHERE trace_id = :traceId ORDER BY step_order ASC")
  suspend fun getStepsForTrace(traceId: String): List<TraceStepEntity>

  @Query("SELECT * FROM traces ORDER BY created_at DESC LIMIT :limit")
  suspend fun getRecentTraces(limit: Int = 100): List<TraceEntity>

  @Query("SELECT * FROM traces WHERE success = 1 ORDER BY created_at DESC")
  suspend fun getSuccessfulTraces(): List<TraceEntity>

  @Query("SELECT COUNT(*) FROM traces")
  suspend fun getTraceCount(): Int

  @Query("SELECT COUNT(*) FROM traces WHERE success = 1")
  suspend fun getSuccessfulTraceCount(): Int

  @Query("DELETE FROM traces WHERE id = :traceId")
  suspend fun deleteTrace(traceId: String)

  @Query(
    """
        DELETE FROM traces WHERE id IN (
            SELECT id FROM traces
            WHERE success = 0
            ORDER BY created_at ASC
            LIMIT :count
        )
    """,
  )
  suspend fun deleteOldestFailedTraces(count: Int)

  @Query("DELETE FROM traces")
  suspend fun deleteAllTraces()
}

@Dao
interface HotPathCacheDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(entry: HotPathCacheEntity)

  @Query("SELECT * FROM hot_path_cache WHERE ui_hash = :uiHash")
  suspend fun getByUiHash(uiHash: String): HotPathCacheEntity?

  @Query("SELECT * FROM hot_path_cache WHERE success_count >= :minSuccessCount ORDER BY confidence DESC")
  suspend fun getHighConfidenceEntries(minSuccessCount: Int = 3): List<HotPathCacheEntity>

  @Query("SELECT * FROM hot_path_cache ORDER BY confidence DESC LIMIT :limit")
  suspend fun getTopEntries(limit: Int = 100): List<HotPathCacheEntity>

  @Query("SELECT COUNT(*) FROM hot_path_cache")
  suspend fun getEntryCount(): Int

  @Query("SELECT COUNT(*) FROM hot_path_cache WHERE success_count >= :minSuccessCount")
  suspend fun getHighConfidenceCount(minSuccessCount: Int = 3): Int

  @Query("UPDATE hot_path_cache SET last_used = :timestamp WHERE ui_hash = :uiHash")
  suspend fun updateLastUsed(uiHash: String, timestamp: Long)

  @Query(
    """
        UPDATE hot_path_cache
        SET success_count = success_count + 1,
            confidence = CAST(success_count + 1 AS REAL) / (success_count + 2),
            last_used = :timestamp
        WHERE ui_hash = :uiHash
    """,
  )
  suspend fun incrementSuccessCount(uiHash: String, timestamp: Long)

  @Query("DELETE FROM hot_path_cache WHERE ui_hash = :uiHash")
  suspend fun delete(uiHash: String)

  @Query(
    """
        DELETE FROM hot_path_cache WHERE ui_hash IN (
            SELECT ui_hash FROM hot_path_cache
            WHERE success_count < :minSuccessCount
            ORDER BY last_used ASC
            LIMIT :count
        )
    """,
  )
  suspend fun deleteOldestLowConfidence(count: Int, minSuccessCount: Int = 3)

  @Query("DELETE FROM hot_path_cache")
  suspend fun deleteAll()

  @Query("SELECT * FROM hot_path_cache WHERE success_count >= :minSuccessCount")
  fun observeHighConfidenceEntries(minSuccessCount: Int = 3): Flow<List<HotPathCacheEntity>>
}
