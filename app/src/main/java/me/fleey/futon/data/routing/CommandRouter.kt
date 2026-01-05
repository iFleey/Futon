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
package me.fleey.futon.data.routing

import me.fleey.futon.data.perception.models.PerceptionResult
import me.fleey.futon.data.routing.models.HotPathPattern
import me.fleey.futon.data.routing.models.RoutingResult
import me.fleey.futon.data.routing.models.RoutingStats

/**
 * Routes commands to hot path (regex + cache) or cold path (LLM) based on complexity.
 */
interface CommandRouter {
  /**
   * Route a command to the appropriate execution path.
   *
   * @param command The user command to route
   * @param uiState Current perception result with detected UI elements
   * @return RoutingResult indicating hot path (direct action) or cold path (LLM)
   */
  suspend fun route(command: String, uiState: PerceptionResult): RoutingResult

  /**
   * Register a new hot path pattern for direct command matching.
   *
   * @param pattern The hot path pattern to register
   */
  fun registerHotPath(pattern: HotPathPattern)

  fun unregisterHotPath(patternId: String): Boolean

  fun getRegisteredPatterns(): List<HotPathPattern>

  fun getStats(): RoutingStats

  fun resetStats()
}
