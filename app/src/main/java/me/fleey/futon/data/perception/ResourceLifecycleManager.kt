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
package me.fleey.futon.data.perception

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * Manages resource lifecycle for the perception system.
 *
 * Implements Closeable for deterministic resource cleanup.
 */
interface ResourceLifecycleManager : Closeable {

  /**
   * Current lifecycle state.
   */
  val lifecycleState: StateFlow<ResourceLifecycleState>

  /**
   * Register a closeable resource for lifecycle management.
   *
   * @param resource The resource to manage
   * @param priority Cleanup priority (lower = cleanup first)
   */
  fun registerResource(resource: Closeable, priority: Int = DEFAULT_PRIORITY)

  fun unregisterResource(resource: Closeable)

  /**
   * Pause all managed resources.
   * Resources should release non-essential allocations.
   */
  fun pause()

  suspend fun resume(): Boolean

  fun isHealthy(): Boolean

  fun getStats(): ResourceStats

  fun forceCleanup()

  override fun close()

  companion object {
    const val DEFAULT_PRIORITY = 100
    const val HIGH_PRIORITY = 50
    const val LOW_PRIORITY = 150
  }
}

/**
 * State of the resource lifecycle.
 */
enum class ResourceLifecycleState {
  UNINITIALIZED,

  ACTIVE,

  /** Resources are paused (background) */
  PAUSED,

  CLEANING_UP,

  DESTROYED
}

/**
 * Statistics about managed resources.
 */
data class ResourceStats(
  val registeredResources: Int,
  val activeResources: Int,
  val totalMemoryBytes: Long,
  val bufferMemoryBytes: Long,
  val modelMemoryBytes: Long,
  val lastCleanupTimeMs: Long,
) {
  val totalMemoryMb: Float get() = totalMemoryBytes / (1024f * 1024f)
  val bufferMemoryMb: Float get() = bufferMemoryBytes / (1024f * 1024f)
  val modelMemoryMb: Float get() = modelMemoryBytes / (1024f * 1024f)
}
