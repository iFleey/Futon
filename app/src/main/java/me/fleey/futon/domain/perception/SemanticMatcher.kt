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
package me.fleey.futon.domain.perception

import kotlinx.serialization.Serializable
import me.fleey.futon.domain.perception.models.BoundsRatio
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree

/**
 * Semantic matcher for finding UI elements based on multiple criteria.
 */
interface SemanticMatcher {
  /**
   * Find all matching elements in the UI tree.
   *
   * @param tree The UI tree to search
   * @param query The match criteria
   * @return List of matches sorted by score in descending order,
   *         filtered by minimum threshold
   */
  fun findMatches(tree: UITree, query: MatchQuery): List<MatchResult>

  /**
   * Find the best matching element in the UI tree.
   *
   * @param tree The UI tree to search
   * @param query The match criteria
   * @return The highest scoring match, or null if no match exceeds threshold
   */
  fun findBestMatch(tree: UITree, query: MatchQuery): MatchResult?

  /**
   * Calculate the match score for a single node.
   *
   * @param node The UI node to evaluate
   * @param query The match criteria
   * @param screenWidth Screen width for bounds ratio calculation
   * @param screenHeight Screen height for bounds ratio calculation
   * @return Match result with score and details
   */
  fun calculateMatch(
    node: UINode,
    query: MatchQuery,
    screenWidth: Int,
    screenHeight: Int,
  ): MatchResult
}

/**
 * Query criteria for semantic matching.
 *
 * At least one criterion should be specified for meaningful matching.
 * Multiple criteria are combined using weighted scoring.
 *
 * @property resourceId Resource ID to match (partial match supported)
 * @property text Text content to match (fuzzy match supported)
 * @property contentDesc Content description to match (fuzzy match supported)
 * @property className Class name to match (partial match supported)
 * @property boundsRatio Expected bounds ratio for position matching
 * @property hierarchyPath Hierarchy path signature to match
 * @property mustBeClickable If true, only match clickable elements
 * @property mustBeEnabled If true, only match enabled elements
 */
@Serializable
data class MatchQuery(
  val resourceId: String? = null,
  val text: String? = null,
  val contentDesc: String? = null,
  val className: String? = null,
  val boundsRatio: BoundsRatio? = null,
  val hierarchyPath: String? = null,
  val mustBeClickable: Boolean = false,
  val mustBeEnabled: Boolean = false,
) {
  /**
   * Check if this query has any matching criteria.
   */
  fun hasCriteria(): Boolean =
    resourceId != null ||
      text != null ||
      contentDesc != null ||
      className != null ||
      boundsRatio != null ||
      hierarchyPath != null

  /**
   * Create a copy with additional constraints.
   */
  fun withClickable(): MatchQuery = copy(mustBeClickable = true)
  fun withEnabled(): MatchQuery = copy(mustBeEnabled = true)

  companion object {
    /**
     * Create a query matching by resource ID.
     */
    fun byResourceId(id: String, clickable: Boolean = false): MatchQuery =
      MatchQuery(resourceId = id, mustBeClickable = clickable)

    /**
     * Create a query matching by text.
     */
    fun byText(text: String, clickable: Boolean = false): MatchQuery =
      MatchQuery(text = text, mustBeClickable = clickable)

    /**
     * Create a query matching by content description.
     */
    fun byContentDesc(desc: String, clickable: Boolean = false): MatchQuery =
      MatchQuery(contentDesc = desc, mustBeClickable = clickable)

    /**
     * Create a query matching by class name.
     */
    fun byClassName(className: String, clickable: Boolean = false): MatchQuery =
      MatchQuery(className = className, mustBeClickable = clickable)

    /**
     * Create a query matching by bounds ratio.
     */
    fun byBoundsRatio(ratio: BoundsRatio, clickable: Boolean = false): MatchQuery =
      MatchQuery(boundsRatio = ratio, mustBeClickable = clickable)
  }
}

/**
 * Result of a semantic match operation.
 *
 * @property node The matched UI node
 * @property score Overall match score (0.0 to 1.0)
 * @property details Breakdown of individual criterion scores
 */
@Serializable
data class MatchResult(
  val node: UINode,
  val score: Float,
  val details: MatchDetails,
) : Comparable<MatchResult> {
  /**
   * Compare by score in descending order.
   */
  override fun compareTo(other: MatchResult): Int =
    other.score.compareTo(this.score)

  /**
   * Check if this match exceeds the minimum threshold.
   */
  fun exceedsThreshold(): Boolean = score >= MatchWeights.MIN_THRESHOLD
}

/**
 * Detailed breakdown of match scores for each criterion.
 *
 * Each score is in the range [0.0, 1.0] where:
 * - 0.0 means no match
 * - 1.0 means perfect match
 *
 * @property resourceIdScore Score for resource ID matching
 * @property textScore Score for text content matching
 * @property contentDescScore Score for content description matching
 * @property classNameScore Score for class name matching
 * @property boundsScore Score for bounds ratio matching
 * @property hierarchyScore Score for hierarchy path matching
 */
@Serializable
data class MatchDetails(
  val resourceIdScore: Float = 0f,
  val textScore: Float = 0f,
  val contentDescScore: Float = 0f,
  val classNameScore: Float = 0f,
  val boundsScore: Float = 0f,
  val hierarchyScore: Float = 0f,
) {
  /**
   * Get the highest individual score.
   */
  fun maxScore(): Float = maxOf(
    resourceIdScore,
    textScore,
    contentDescScore,
    classNameScore,
    boundsScore,
    hierarchyScore,
  )

  /**
   * Get the number of criteria that matched (score > 0).
   */
  fun matchedCriteriaCount(): Int = listOf(
    resourceIdScore,
    textScore,
    contentDescScore,
    classNameScore,
    boundsScore,
    hierarchyScore,
  ).count { it > 0f }

  companion object {
    val EMPTY = MatchDetails()
  }
}

/**
 * Weight constants for semantic matching.
 */
object MatchWeights {
  const val RESOURCE_ID = 1.0f

  const val TEXT = 0.8f

  const val CONTENT_DESC = 0.7f

  const val CLASS_NAME = 0.5f

  const val BOUNDS_RATIO = 0.6f

  const val HIERARCHY_PATH = 0.4f

  const val MIN_THRESHOLD = 0.5f

  const val FUZZY_MATCH_THRESHOLD = 0.6f
}
