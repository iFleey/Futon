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

import me.fleey.futon.data.perception.models.CachedPattern
import me.fleey.futon.domain.perception.MatchQuery

/**
 * Repository for managing cached element patterns.
 * Stores successful matching patterns for adaptive learning.
 */
interface ElementCacheRepository {

  /** Store a pattern. Evicts LRU if cache full (1000 entries). */
  suspend fun cachePattern(pattern: CachedPattern)

  suspend fun findPattern(query: MatchQuery): CachedPattern?

  suspend fun findPatternById(patternId: String): CachedPattern?

  /** Update existing pattern (e.g., increment success count). */
  suspend fun updatePattern(pattern: CachedPattern): Boolean

  suspend fun removePattern(patternId: String): Boolean

  suspend fun clearAll()

  suspend fun size(): Int

  suspend fun getAllPatterns(): List<CachedPattern>

  suspend fun removeStalePatterns(maxIdleTimeMs: Long): Int

  companion object {
    const val MAX_CACHE_SIZE = 1000

    /** Default maximum idle time before pattern is considered stale (7 days) */
    const val DEFAULT_MAX_IDLE_TIME_MS = 7 * 24 * 60 * 60 * 1000L
  }
}
