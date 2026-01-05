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
package me.fleey.futon.data.perception.models

import kotlinx.serialization.Serializable
import me.fleey.futon.domain.perception.models.BoundsRatio
import java.util.UUID

/**
 * Represents a cached element matching pattern for adaptive learning.
 *
 * When a match succeeds and an action completes successfully, the matching
 * pattern is stored in the cache. Future matching operations can use these
 * cached patterns to improve accuracy and speed.
 *
 * @property id Unique identifier for this pattern
 * @property resourceId Resource ID pattern (e.g., "com.example:id/button")
 * @property textPattern Text content pattern for matching
 * @property boundsRatio Normalized bounds ratio relative to screen dimensions
 * @property hierarchySignature Hierarchy path signature (e.g., "Button[0]")
 * @property contentDescPattern Content description pattern for matching
 * @property className Class name of the matched element
 * @property successCount Number of successful matches using this pattern
 * @property lastUsedMs Timestamp of last successful use (milliseconds since epoch)
 * @property createdMs Timestamp when this pattern was created (milliseconds since epoch)
 */
@Serializable
data class CachedPattern(
  val id: String = UUID.randomUUID().toString(),
  val resourceId: String? = null,
  val textPattern: String? = null,
  val boundsRatio: BoundsRatio? = null,
  val hierarchySignature: String? = null,
  val contentDescPattern: String? = null,
  val className: String? = null,
  val successCount: Int = 1,
  val lastUsedMs: Long = System.currentTimeMillis(),
  val createdMs: Long = System.currentTimeMillis(),
) {
  /**
   * Check if this pattern has any matching criteria.
   *
   * @return true if at least one matching criterion is set
   */
  fun hasCriteria(): Boolean =
    resourceId != null ||
      textPattern != null ||
      boundsRatio != null ||
      hierarchySignature != null ||
      contentDescPattern != null ||
      className != null

  /**
   * Create a copy with incremented success count and updated last used time.
   *
   * @return Updated pattern with incremented successCount and current timestamp
   */
  fun recordSuccess(): CachedPattern = copy(
    successCount = successCount + 1,
    lastUsedMs = System.currentTimeMillis(),
  )

  /**
   * Create a copy with only the last used time updated.
   *
   * @return Updated pattern with current timestamp
   */
  fun touch(): CachedPattern = copy(
    lastUsedMs = System.currentTimeMillis(),
  )

  /**
   * Calculate the age of this pattern in milliseconds.
   *
   * @return Time since creation in milliseconds
   */
  fun ageMs(): Long = System.currentTimeMillis() - createdMs

  /**
   * Calculate the time since last use in milliseconds.
   *
   * @return Time since last use in milliseconds
   */
  private fun idleTimeMs(): Long = System.currentTimeMillis() - lastUsedMs

  /**
   * Calculate a priority score for LRU eviction.
   *
   * Higher scores indicate patterns that should be kept longer.
   * Factors considered:
   * - Success count (more successful = higher priority)
   * - Recency of use (more recent = higher priority)
   *
   * @return Priority score (higher = more important to keep)
   */
  fun priorityScore(): Double {
    val recencyFactor = 1.0 / (1.0 + idleTimeMs() / RECENCY_DECAY_MS)
    val successFactor = kotlin.math.ln(successCount.toDouble() + 1.0)
    return recencyFactor * SUCCESS_WEIGHT + successFactor * RECENCY_WEIGHT
  }

  companion object {
    /** Weight for success count in priority calculation */
    private const val SUCCESS_WEIGHT = 0.4

    /** Weight for recency in priority calculation */
    private const val RECENCY_WEIGHT = 0.6

    /** Decay constant for recency (1 hour in milliseconds) */
    private const val RECENCY_DECAY_MS = 3600_000.0

    /**
     * Create a pattern from matching criteria.
     *
     * @param resourceId Resource ID to match
     * @param textPattern Text pattern to match
     * @param boundsRatio Bounds ratio for position matching
     * @param hierarchySignature Hierarchy path signature
     * @param contentDescPattern Content description pattern
     * @param className Class name of the element
     * @return New CachedPattern instance
     */
    fun create(
      resourceId: String? = null,
      textPattern: String? = null,
      boundsRatio: BoundsRatio? = null,
      hierarchySignature: String? = null,
      contentDescPattern: String? = null,
      className: String? = null,
    ): CachedPattern = CachedPattern(
      resourceId = resourceId,
      textPattern = textPattern,
      boundsRatio = boundsRatio,
      hierarchySignature = hierarchySignature,
      contentDescPattern = contentDescPattern,
      className = className,
    )
  }
}
