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
 * Represents a single node in the UI hierarchy tree.
 *
 * This data class models the structure returned by `uiautomator dump`,
 * capturing all relevant attributes of a UI element for matching and interaction.
 *
 * @property className Fully qualified class name of the UI element (e.g., "android.widget.Button")
 * @property text Text content displayed by the element, if any
 * @property resourceId Resource ID in format "package:id/name", if assigned
 * @property contentDesc Content description for screen readers, if set
 * @property bounds Bounding rectangle of the element in screen coordinates
 * @property isClickable Whether the element responds to click events
 * @property isEnabled Whether the element is currently enabled
 * @property isFocused Whether the element currently has focus
 * @property isSelected Whether the element is in selected state
 * @property isScrollable Whether the element supports scrolling
 * @property isCheckable Whether the element can be checked (checkbox, radio button)
 * @property isChecked Whether the element is currently checked
 * @property isLongClickable Whether the element responds to long click events
 * @property children List of child nodes in the hierarchy
 * @property index Index of this node among its siblings
 * @property packageName Package name of the app owning this element
 */
@Serializable
data class UINode(
  val className: String,
  val text: String? = null,
  val resourceId: String? = null,
  val contentDesc: String? = null,
  val bounds: UIBounds,
  val isClickable: Boolean = false,
  val isEnabled: Boolean = true,
  val isFocused: Boolean = false,
  val isSelected: Boolean = false,
  val isScrollable: Boolean = false,
  val isCheckable: Boolean = false,
  val isChecked: Boolean = false,
  val isLongClickable: Boolean = false,
  val children: List<UINode> = emptyList(),
  val index: Int = 0,
  val packageName: String? = null,
) {
  /**
   * Get the center point of this node's bounds.
   *
   * @return Point at the center of the element
   */
  fun centerPoint(): Point = Point(
    x = bounds.centerX,
    y = bounds.centerY,
  )

  /**
   * Calculate the bounds ratio relative to screen dimensions.
   *
   * @param screenWidth Screen width in pixels
   * @param screenHeight Screen height in pixels
   * @return BoundsRatio with normalized coordinates
   */
  fun boundsRatio(screenWidth: Int, screenHeight: Int): BoundsRatio =
    bounds.toRatio(screenWidth, screenHeight)

  /**
   * Generate a hierarchy signature for this node.
   *
   * The signature is a compact representation of the node's position
   * in the hierarchy, useful for caching and matching.
   *
   * @return String in format "SimpleName[index]"
   */
  fun hierarchySignature(): String {
    val simpleName = className.substringAfterLast('.')
    return "$simpleName[$index]"
  }

  /**
   * Get the simple class name without package prefix.
   *
   * @return Simple class name (e.g., "Button" from "android.widget.Button")
   */
  fun simpleClassName(): String = className.substringAfterLast('.')

  /**
   * Get the resource ID name without package prefix.
   *
   * @return ID name (e.g., "submit_button" from "com.example:id/submit_button")
   */
  fun resourceIdName(): String? = resourceId?.substringAfterLast('/')

  /**
   * Check if this node has any text content (text or contentDesc).
   *
   * @return true if text or contentDesc is non-null and non-blank
   */
  fun hasTextContent(): Boolean =
    !text.isNullOrBlank() || !contentDesc.isNullOrBlank()

  /**
   * Get the primary text content (prefers text over contentDesc).
   *
   * @return text if available, otherwise contentDesc, or null if neither
   */
  fun primaryText(): String? = text?.takeIf { it.isNotBlank() }
    ?: contentDesc?.takeIf { it.isNotBlank() }

  /**
   * Check if this node is interactive (clickable, long-clickable, or checkable).
   *
   * @return true if the node can be interacted with
   */
  fun isInteractive(): Boolean = isClickable || isLongClickable || isCheckable

  /**
   * Check if this node is a leaf node (has no children).
   *
   * @return true if children list is empty
   */
  fun isLeaf(): Boolean = children.isEmpty()

  /**
   * Count total number of descendants (children, grandchildren, etc.).
   *
   * @return Total count of all descendant nodes
   */
  fun descendantCount(): Int {
    var count = children.size
    children.forEach { count += it.descendantCount() }
    return count
  }

  /**
   * Find all descendant nodes matching a predicate.
   *
   * @param predicate Condition to match
   * @return List of matching nodes in depth-first order
   */
  fun findAll(predicate: (UINode) -> Boolean): List<UINode> {
    val result = mutableListOf<UINode>()
    if (predicate(this)) {
      result.add(this)
    }
    children.forEach { child ->
      result.addAll(child.findAll(predicate))
    }
    return result
  }

  /**
   * Find the first descendant node matching a predicate.
   *
   * @param predicate Condition to match
   * @return First matching node, or null if none found
   */
  private fun findFirst(predicate: (UINode) -> Boolean): UINode? {
    if (predicate(this)) return this
    for (child in children) {
      val found = child.findFirst(predicate)
      if (found != null) return found
    }
    return null
  }

  /**
   * Find node by resource ID.
   *
   * @param id Resource ID to search for (can be partial match)
   * @return First node with matching resource ID, or null
   */
  fun findByResourceId(id: String): UINode? = findFirst { node ->
    node.resourceId?.contains(id, ignoreCase = true) == true
  }

  /**
   * Find node by text content.
   *
   * @param text Text to search for (exact match)
   * @return First node with matching text, or null
   */
  fun findByText(text: String): UINode? = findFirst { node ->
    node.text == text
  }

  /**
   * Find node by text content (partial match).
   *
   * @param text Text to search for
   * @return First node containing the text, or null
   */
  fun findByTextContains(text: String): UINode? = findFirst { node ->
    node.text?.contains(text, ignoreCase = true) == true
  }

  /**
   * Find all clickable nodes.
   *
   * @return List of all clickable descendant nodes
   */
  fun findClickable(): List<UINode> = findAll { it.isClickable }

  /**
   * Find all nodes with text content.
   *
   * @return List of all nodes that have text or contentDesc
   */
  fun findWithText(): List<UINode> = findAll { it.hasTextContent() }

  /**
   * Convert to a compact string representation for debugging.
   *
   * @return Compact string with key attributes
   */
  fun toCompactString(): String = buildString {
    append(simpleClassName())
    text?.let { append(" text=\"$it\"") }
    resourceIdName()?.let { append(" id=\"$it\"") }
    contentDesc?.let { append(" desc=\"$it\"") }
    if (isClickable) append(" [clickable]")
    if (isScrollable) append(" [scrollable]")
    append(" bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
  }

  /**
   * Convert to a tree string representation for debugging.
   *
   * @param indent Current indentation level
   * @return Multi-line string showing hierarchy
   */
  private fun toTreeString(indent: Int = 0): String = buildString {
    append("  ".repeat(indent))
    append(toCompactString())
    appendLine()
    children.forEach { child ->
      append(child.toTreeString(indent + 1))
    }
  }

  companion object {
    /**
     * Common Android widget class names for quick reference.
     */
    object ClassNames {
      const val BUTTON = "android.widget.Button"
      const val TEXT_VIEW = "android.widget.TextView"
      const val EDIT_TEXT = "android.widget.EditText"
      const val IMAGE_VIEW = "android.widget.ImageView"
      const val IMAGE_BUTTON = "android.widget.ImageButton"
      const val CHECK_BOX = "android.widget.CheckBox"
      const val RADIO_BUTTON = "android.widget.RadioButton"
      const val SWITCH = "android.widget.Switch"
      const val TOGGLE_BUTTON = "android.widget.ToggleButton"
      const val SPINNER = "android.widget.Spinner"
      const val SEEK_BAR = "android.widget.SeekBar"
      const val PROGRESS_BAR = "android.widget.ProgressBar"
      const val SCROLL_VIEW = "android.widget.ScrollView"
      const val LIST_VIEW = "android.widget.ListView"
      const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
      const val VIEW_PAGER = "androidx.viewpager.widget.ViewPager"
      const val FRAME_LAYOUT = "android.widget.FrameLayout"
      const val LINEAR_LAYOUT = "android.widget.LinearLayout"
      const val RELATIVE_LAYOUT = "android.widget.RelativeLayout"
      const val CONSTRAINT_LAYOUT = "androidx.constraintlayout.widget.ConstraintLayout"
    }
  }
}
