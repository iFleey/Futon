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

import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.trace.models.CachedAction
import org.koin.core.annotation.Single

/**
 * Service for looking up cached actions based on UI state.
 *
 * Combines UI hash computation with trace lookup for a unified API.
 */
interface ActionLookupService {
  /**
   * Lookup a cached action for the given perception result.
   *
   * @param result The current perception result
   * @return CachedAction if a matching action is found, null otherwise
   */
  suspend fun lookupAction(result: PerceptionResult): CachedAction?

  /**
   * Lookup a cached action by UI hash.
   *
   * @param uiHash The UI state hash
   * @return CachedAction if found, null otherwise
   */
  suspend fun lookupActionByHash(uiHash: String): CachedAction?

  /**
   * Compute the UI hash for a perception result.
   *
   * @param result The perception result
   * @return Deterministic hash string
   */
  fun computeHash(result: PerceptionResult): String

  /**
   * Check if a high-confidence action exists for the given UI state.
   *
   * @param result The perception result
   * @return true if a high-confidence cached action exists
   */
  suspend fun hasHighConfidenceAction(result: PerceptionResult): Boolean
}

@Single(binds = [ActionLookupService::class])
class ActionLookupServiceImpl(
  private val traceRecorder: TraceRecorder,
  private val uiHashComputer: UIHashComputer,
) : ActionLookupService {

  override suspend fun lookupAction(result: PerceptionResult): CachedAction? {
    val hash = uiHashComputer.computeHash(result)
    return traceRecorder.lookupAction(hash)
  }

  override suspend fun lookupActionByHash(uiHash: String): CachedAction? {
    return traceRecorder.lookupAction(uiHash)
  }

  override fun computeHash(result: PerceptionResult): String {
    return uiHashComputer.computeHash(result)
  }

  override suspend fun hasHighConfidenceAction(result: PerceptionResult): Boolean {
    val action = lookupAction(result)
    return action?.isHighConfidence == true
  }
}
