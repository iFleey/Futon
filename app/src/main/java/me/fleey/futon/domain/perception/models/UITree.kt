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
package me.fleey.futon.domain.perception.models

import kotlinx.serialization.Serializable

/**
 * Represents the complete UI hierarchy tree captured from a screen.
 *
 * This is the top-level container for UI structure data obtained via
 * `uiautomator dump`. It includes metadata about the capture and provides
 * utility methods for traversing and querying the tree.
 *
 * @property root The root node of the UI hierarchy
 * @property captureTimeMs Timestamp when the UI tree was captured (epoch milliseconds)
 * @property screenWidth Screen width in pixels at capture time
 * @property screenHeight Screen height in pixels at capture time
 * @property packageName Package name of the foreground app, if available
 * @property rotation Screen rotation at capture time (0, 90, 180, 270)
 */
@Serializable
data class UITree(
  val root: UINode,
  val captureTimeMs: Long,
  val screenWidth: Int,
  val screenHeight: Int,
  val packageName: String? = null,
  val rotation: Int = 0,
) {
  /**
   * Flatten the tree into a list of all nodes in depth-first order.
   *
   * @return List of all nodes in the tree
   */
  fun flatten(): List<UINode> {
    val result = mutableListOf<UINode>()
    fun traverse(node: UINode) {
      result.add(node)
      node.children.forEach { traverse(it) }
    }
    traverse(root)
    return result
  }

  /**
   * Get the total number of nodes in the tree.
   *
   * @return Total node count including root
   */
  fun nodeCount(): Int = 1 + root.descendantCount()

  /**
   * Find all clickable nodes in the tree.
   *
   * @return List of all nodes where isClickable is true
   */
  private fun findClickableNodes(): List<UINode> = root.findClickable()

  /**
   * Find all nodes with text content.
   *
   * @return List of all nodes that have text or contentDesc
   */
  fun findNodesWithText(): List<UINode> = root.findWithText()

  /**
   * Find all interactive nodes (clickable, long-clickable, or checkable).
   *
   * @return List of all interactive nodes
   */
  fun findInteractiveNodes(): List<UINode> = root.findAll { it.isInteractive() }

  /**
   * Find all scrollable nodes.
   *
   * @return List of all scrollable nodes
   */
  fun findScrollableNodes(): List<UINode> = root.findAll { it.isScrollable }

  /**
   * Find node by resource ID.
   *
   * @param id Resource ID to search for (can be partial match)
   * @return First node with matching resource ID, or null
   */
  fun findByResourceId(id: String): UINode? = root.findByResourceId(id)

  /**
   * Find node by exact text.
   *
   * @param text Text to search for
   * @return First node with matching text, or null
   */
  fun findByText(text: String): UINode? = root.findByText(text)

  /**
   * Find node by text (partial match).
   *
   * @param text Text to search for
   * @return First node containing the text, or null
   */
  fun findByTextContains(text: String): UINode? = root.findByTextContains(text)

  /**
   * Find node at a specific screen coordinate.
   *
   * Returns the deepest (most specific) node containing the point.
   *
   * @param x X-coordinate in screen pixels
   * @param y Y-coordinate in screen pixels
   * @return Deepest node containing the point, or null if outside all bounds
   */
  fun findNodeAt(x: Int, y: Int): UINode? {
    fun findDeepest(node: UINode): UINode? {
      if (!node.bounds.contains(x, y)) return null

      for (child in node.children) {
        val found = findDeepest(child)
        if (found != null) return found
      }

      return node
    }
    return findDeepest(root)
  }

  /**
   * Find the clickable node at a specific screen coordinate.
   *
   * Returns the smallest (by bounds area) clickable node containing the point.
   * This ensures we get the most precise target element rather than a large container.
   *
   * @param x X-coordinate in screen pixels
   * @param y Y-coordinate in screen pixels
   * @return Smallest clickable node containing the point, or null
   */
  fun findClickableNodeAt(x: Int, y: Int): UINode? {
    val candidates = mutableListOf<UINode>()

    fun collectClickableNodes(node: UINode) {
      if (!node.bounds.contains(x, y)) return

      if (node.isClickable) {
        candidates.add(node)
      }

      // Continue searching children for smaller clickable elements
      for (child in node.children) {
        collectClickableNodes(child)
      }
    }

    collectClickableNodes(root)

    return candidates.minByOrNull { it.bounds.area }
  }

  /**
   * Convert the UI tree to a concise text representation for AI context.
   *
   * This format is optimized for LLM consumption, providing a readable
   * hierarchical view of the UI structure with key attributes.
   *
   * @param maxDepth Maximum depth to include (default: unlimited)
   * @param includeNonInteractive Whether to include non-interactive nodes
   * @return Multi-line string representation
   */
  fun toAIContext(
    maxDepth: Int = Int.MAX_VALUE,
    includeNonInteractive: Boolean = true,
  ): String = buildString {
    appendLine("UI Structure (${screenWidth}x${screenHeight}, rotation=$rotation):")
    if (packageName != null) {
      appendLine("Package: $packageName")
    }
    appendLine()

    fun appendNode(node: UINode, depth: Int) {
      if (depth > maxDepth) return
      if (!includeNonInteractive && !node.isInteractive() && !node.hasTextContent()) {
        // Skip non-interactive nodes without text, but still process children
        node.children.forEach { appendNode(it, depth) }
        return
      }

      val indent = "  ".repeat(depth)
      val info = buildString {
        append(node.simpleClassName())
        node.text?.takeIf { it.isNotBlank() }?.let {
          val truncated = if (it.length > 50) it.take(47) + "..." else it
          append(" text=\"$truncated\"")
        }
        node.resourceIdName()?.let { append(" id=\"$it\"") }
        node.contentDesc?.takeIf { it.isNotBlank() }?.let {
          val truncated = if (it.length > 30) it.take(27) + "..." else it
          append(" desc=\"$truncated\"")
        }
        if (node.isClickable) append(" [clickable]")
        if (node.isScrollable) append(" [scrollable]")
        if (node.isCheckable) {
          append(if (node.isChecked) " [checked]" else " [checkable]")
        }
        append(" @[${node.bounds.centerX},${node.bounds.centerY}]")
      }
      appendLine("$indent$info")
      node.children.forEach { appendNode(it, depth + 1) }
    }

    appendNode(root, 0)
  }

  /**
   * Convert to a compact summary for logging.
   *
   * @return Single-line summary string
   */
  fun toSummary(): String = buildString {
    append("UITree(")
    append("nodes=${nodeCount()}, ")
    append("clickable=${findClickableNodes().size}, ")
    append("screen=${screenWidth}x${screenHeight}, ")
    packageName?.let { append("pkg=$it, ") }
    append("captured=$captureTimeMs")
    append(")")
  }

  /**
   * Get statistics about the tree structure.
   *
   * @return TreeStats with various metrics
   */
  fun getStats(): TreeStats {
    val allNodes = flatten()
    return TreeStats(
      totalNodes = allNodes.size,
      clickableNodes = allNodes.count { it.isClickable },
      textNodes = allNodes.count { it.hasTextContent() },
      interactiveNodes = allNodes.count { it.isInteractive() },
      scrollableNodes = allNodes.count { it.isScrollable },
      maxDepth = calculateMaxDepth(root, 0),
      leafNodes = allNodes.count { it.isLeaf() },
    )
  }

  private fun calculateMaxDepth(node: UINode, currentDepth: Int): Int {
    if (node.children.isEmpty()) return currentDepth
    return node.children.maxOf { calculateMaxDepth(it, currentDepth + 1) }
  }

  companion object {
    /**
     * Create an empty UITree with minimal structure.
     *
     * @param screenWidth Screen width
     * @param screenHeight Screen height
     * @return UITree with empty root node
     */
    fun empty(screenWidth: Int, screenHeight: Int): UITree = UITree(
      root = UINode(
        className = "android.widget.FrameLayout",
        bounds = UIBounds(0, 0, screenWidth, screenHeight),
      ),
      captureTimeMs = System.currentTimeMillis(),
      screenWidth = screenWidth,
      screenHeight = screenHeight,
    )
  }
}

/**
 * Statistics about a UI tree structure.
 *
 * @property totalNodes Total number of nodes in the tree
 * @property clickableNodes Number of clickable nodes
 * @property textNodes Number of nodes with text content
 * @property interactiveNodes Number of interactive nodes
 * @property scrollableNodes Number of scrollable nodes
 * @property maxDepth Maximum depth of the tree
 * @property leafNodes Number of leaf nodes (no children)
 */
@Serializable
data class TreeStats(
  val totalNodes: Int,
  val clickableNodes: Int,
  val textNodes: Int,
  val interactiveNodes: Int,
  val scrollableNodes: Int,
  val maxDepth: Int,
  val leafNodes: Int,
)
