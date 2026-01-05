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
package me.fleey.futon.data.daemon

import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.daemon.models.DaemonPerceptionResult
import me.fleey.futon.data.daemon.models.DaemonPerceptionState
import me.fleey.futon.data.perception.models.PerceptionConfig
import java.io.Closeable

/**
 * Proxy for daemon-based perception operations.
 *
 * Delegates all perception calls to the daemon - no local TFLite inference.
 * When daemon is unavailable, returns DaemonUnavailable result.
 */
interface DaemonPerceptionProxy : Closeable {
  /**
   * Current state of the perception proxy.
   */
  val daemonPerceptionState: StateFlow<DaemonPerceptionState>

  /**
   * Execute a perception operation via the daemon.
   *
   * @return DaemonPerceptionResult with detected elements or error
   */
  suspend fun perceive(): DaemonPerceptionResult

  /**
   * Configure the daemon perception system.
   *
   * @param config Perception configuration to apply
   * @return Result indicating success or failure
   */
  suspend fun configure(config: PerceptionConfig): Result<Unit>

  /**
   * Check if the daemon is available for perception.
   */
  fun isDaemonAvailable(): Boolean
}
