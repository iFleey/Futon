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
import me.fleey.futon.data.routing.models.Action
import me.fleey.futon.data.trace.models.CachedAction
import me.fleey.futon.data.trace.models.Trace
import me.fleey.futon.data.trace.models.TraceId
import me.fleey.futon.data.trace.models.TraceStats

/**
 * Records successful operation traces for imitation learning and hot path optimization.
 */
interface TraceRecorder {
  /**
   * Start recording a new trace.
   *
   * @param taskDescription Description of the task being recorded
   * @return TraceId for the new trace
   */
  fun startRecording(taskDescription: String): TraceId

  /**
   * Record a step in the current trace.
   *
   * @param uiHash Hash of the current UI state
   * @param action The action performed
   * @param success Whether the action succeeded
   */
  fun recordStep(uiHash: String, action: Action, success: Boolean)

  suspend fun stopRecording(success: Boolean): Result<TraceId>

  fun isRecording(): Boolean

  fun cancelRecording()

  /**
   * Lookup a cached action for a UI state hash.
   *
   * @param uiHash Hash of the UI state
   * @return CachedAction if found, null otherwise
   */
  suspend fun lookupAction(uiHash: String): CachedAction?

  /**
   * Record a successful action execution for a UI state.
   * This updates the hot path cache.
   *
   * @param uiHash Hash of the UI state
   * @param action The action that was executed
   */
  suspend fun recordSuccessfulAction(uiHash: String, action: Action)

  /**
   * Promote a trace to hot path rules.
   *
   * @param traceId ID of the trace to promote
   * @return Result indicating success or failure
   */
  suspend fun promoteToHotPath(traceId: TraceId): Result<Unit>

  suspend fun getTrace(traceId: TraceId): Trace?

  suspend fun getRecentTraces(limit: Int = 100): List<Trace>

  suspend fun getStats(): TraceStats

  /**
   * Observe high-confidence cached actions.
   *
   * @return Flow of high-confidence cached actions
   */
  fun observeHotPathEntries(): Flow<List<CachedAction>>

  /**
   * Prune old traces to stay within storage limits.
   *
   * @param maxStorageBytes Maximum storage size in bytes
   */
  suspend fun pruneStorage(maxStorageBytes: Long = 100 * 1024 * 1024)

  suspend fun clearAll()
}
