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

/**
 * SemanticMatcher implementation using weighted multi-criteria scoring.
 */
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree
import org.koin.core.annotation.Single
import kotlin.math.min

@Single(binds = [SemanticMatcher::class])
class SemanticMatcherImpl : SemanticMatcher {

  override fun findMatches(tree: UITree, query: MatchQuery): List<MatchResult> {
    if (!query.hasCriteria()) {
      return emptyList()
    }

    val allNodes = tree.flatten()
    return allNodes
      .asSequence()
      .filter { node -> passesConstraints(node, query) }
      .map { node ->
        calculateMatch(node, query, tree.screenWidth, tree.screenHeight)
      }
      .filter { it.exceedsThreshold() }
      .sortedByDescending { it.score }
      .toList()
  }

  override fun findBestMatch(tree: UITree, query: MatchQuery): MatchResult? {
    return findMatches(tree, query).firstOrNull()
  }

  override fun calculateMatch(
    node: UINode,
    query: MatchQuery,
    screenWidth: Int,
    screenHeight: Int,
  ): MatchResult {
    var totalScore = 0f
    var totalWeight = 0f

    // Resource ID matching (weight: 1.0)
    val resourceIdScore = query.resourceId?.let { queryId ->
      totalWeight += MatchWeights.RESOURCE_ID
      calculateResourceIdScore(node.resourceId, queryId)
    } ?: 0f
    totalScore += resourceIdScore * MatchWeights.RESOURCE_ID

    // Text matching (weight: 0.8)
    val textScore = query.text?.let { queryText ->
      totalWeight += MatchWeights.TEXT
      calculateTextScore(node.text, queryText)
    } ?: 0f
    totalScore += textScore * MatchWeights.TEXT

    // Content description matching (weight: 0.7)
    val contentDescScore = query.contentDesc?.let { queryDesc ->
      totalWeight += MatchWeights.CONTENT_DESC
      calculateTextScore(node.contentDesc, queryDesc)
    } ?: 0f
    totalScore += contentDescScore * MatchWeights.CONTENT_DESC

    // Class name matching (weight: 0.5)
    val classNameScore = query.className?.let { queryClass ->
      totalWeight += MatchWeights.CLASS_NAME
      calculateClassNameScore(node.className, queryClass)
    } ?: 0f
    totalScore += classNameScore * MatchWeights.CLASS_NAME

    // Bounds ratio matching (weight: 0.6)
    val boundsScore = query.boundsRatio?.let { queryBounds ->
      totalWeight += MatchWeights.BOUNDS_RATIO
      val nodeBounds = node.boundsRatio(screenWidth, screenHeight)
      nodeBounds.similarity(queryBounds)
    } ?: 0f
    totalScore += boundsScore * MatchWeights.BOUNDS_RATIO

    // Hierarchy path matching (weight: 0.4)
    val hierarchyScore = query.hierarchyPath?.let { queryPath ->
      totalWeight += MatchWeights.HIERARCHY_PATH
      calculateHierarchyScore(node.hierarchySignature(), queryPath)
    } ?: 0f
    totalScore += hierarchyScore * MatchWeights.HIERARCHY_PATH

    // Calculate final normalized score
    val finalScore = if (totalWeight > 0f) totalScore / totalWeight else 0f

    return MatchResult(
      node = node,
      score = finalScore,
      details = MatchDetails(
        resourceIdScore = resourceIdScore,
        textScore = textScore,
        contentDescScore = contentDescScore,
        classNameScore = classNameScore,
        boundsScore = boundsScore,
        hierarchyScore = hierarchyScore,
      ),
    )
  }

  /**
   * Check if a node passes the hard constraints (mustBeClickable, mustBeEnabled).
   */
  private fun passesConstraints(node: UINode, query: MatchQuery): Boolean {
    if (query.mustBeClickable && !node.isClickable) return false
    if (query.mustBeEnabled && !node.isEnabled) return false
    return true
  }

  /** Resource ID score: exact=1.0, contains=0.8, id_name=0.6 */
  private fun calculateResourceIdScore(nodeId: String?, queryId: String): Float {
    if (nodeId.isNullOrBlank()) return 0f

    // Exact match (case-insensitive)
    if (nodeId.equals(queryId, ignoreCase = true)) return 1.0f

    // Contains match
    if (nodeId.contains(queryId, ignoreCase = true)) return 0.8f

    // Extract ID name (after last '/')
    val nodeIdName = nodeId.substringAfterLast('/')
    val queryIdName = queryId.substringAfterLast('/')

    // ID name exact match
    if (nodeIdName.equals(queryIdName, ignoreCase = true)) return 0.9f

    // ID name contains match
    if (nodeIdName.contains(queryIdName, ignoreCase = true)) return 0.6f

    return 0f
  }

  /** Text score using exact, contains, and Levenshtein fuzzy matching. */
  private fun calculateTextScore(nodeText: String?, queryText: String): Float {
    if (nodeText.isNullOrBlank()) return 0f

    val normalizedNode = nodeText.trim().lowercase()
    val normalizedQuery = queryText.trim().lowercase()

    // Exact match
    if (normalizedNode == normalizedQuery) return 1.0f

    // Contains match (query in node)
    if (normalizedNode.contains(normalizedQuery)) return 0.9f

    // Contains match (node in query)
    if (normalizedQuery.contains(normalizedNode)) return 0.85f

    // Fuzzy match using Levenshtein distance
    return fuzzyMatch(normalizedNode, normalizedQuery)
  }

  /** Class name score: exact=1.0, simple_name=0.9, contains=0.7 */
  private fun calculateClassNameScore(nodeClass: String, queryClass: String): Float {
    // Exact match
    if (nodeClass.equals(queryClass, ignoreCase = true)) return 1.0f

    // Simple name comparison
    val nodeSimple = nodeClass.substringAfterLast('.')
    val querySimple = queryClass.substringAfterLast('.')

    if (nodeSimple.equals(querySimple, ignoreCase = true)) return 0.9f

    // Contains match
    if (nodeSimple.contains(querySimple, ignoreCase = true) ||
      querySimple.contains(nodeSimple, ignoreCase = true)
    ) {
      return 0.7f
    }

    return 0f
  }

  /** Hierarchy score: exact=1.0, contains=0.7 */
  private fun calculateHierarchyScore(nodeSignature: String, queryPath: String): Float {
    if (nodeSignature == queryPath) return 1.0f
    if (nodeSignature.contains(queryPath) || queryPath.contains(nodeSignature)) return 0.7f
    return 0f
  }

  /**
   * Calculate fuzzy match score using normalized Levenshtein distance.
   *
   * @param s1 First string (already normalized)
   * @param s2 Second string (already normalized)
   * @return Similarity score between 0.0 and 1.0
   */
  private fun fuzzyMatch(s1: String, s2: String): Float {
    if (s1.isEmpty() && s2.isEmpty()) return 1.0f
    if (s1.isEmpty() || s2.isEmpty()) return 0f

    val maxLen = maxOf(s1.length, s2.length)
    val distance = levenshteinDistance(s1, s2)

    // Normalize to [0, 1] where 1 is perfect match
    val similarity = 1.0f - (distance.toFloat() / maxLen)

    // Apply threshold - if similarity is too low, return 0
    return if (similarity >= MatchWeights.FUZZY_MATCH_THRESHOLD) similarity else 0f
  }

  /**
   * Calculate Levenshtein distance between two strings.
   *
   * Uses dynamic programming with O(min(m,n)) space optimization.
   */
  private fun levenshteinDistance(s1: String, s2: String): Int {
    // Ensure s1 is the shorter string for space optimization
    val (shorter, longer) = if (s1.length <= s2.length) s1 to s2 else s2 to s1

    var prevRow = IntArray(shorter.length + 1) { it }
    var currRow = IntArray(shorter.length + 1)

    for (i in 1..longer.length) {
      currRow[0] = i

      for (j in 1..shorter.length) {
        val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
        currRow[j] = min(
          min(currRow[j - 1] + 1, prevRow[j] + 1),
          prevRow[j - 1] + cost,
        )
      }

      val temp = prevRow
      prevRow = currRow
      currRow = temp
    }

    return prevRow[shorter.length]
  }

  companion object {
    /**
     * Create a matcher with default configuration.
     */
    fun create(): SemanticMatcher = SemanticMatcherImpl()
  }
}
