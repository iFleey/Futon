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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Represents the bounding rectangle of a UI element.
 *
 * Coordinates are in screen pixels, with origin at top-left corner.
 * This matches the format from uiautomator dump: bounds="[left,top][right,bottom]"
 *
 * @property left Left edge x-coordinate
 * @property top Top edge y-coordinate
 * @property right Right edge x-coordinate
 * @property bottom Bottom edge y-coordinate
 */
@Serializable
data class UIBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  /**
   * Width of the bounding rectangle in pixels.
   */
  val width: Int
    get() = right - left

  val height: Int
    get() = bottom - top

  /**
   * X-coordinate of the center point.
   */
  val centerX: Int
    get() = (left + right) / 2

  val centerY: Int
    get() = (top + bottom) / 2

  /**
   * Area of the bounding rectangle in square pixels.
   */
  val area: Int
    get() = width * height

  /**
   * Check if this bounds contains a point.
   *
   * @param x X-coordinate of the point
   * @param y Y-coordinate of the point
   * @return true if the point is within bounds (inclusive)
   */
  fun contains(x: Int, y: Int): Boolean =
    x in left..right && y in top..bottom

  /**
   * Check if this bounds contains another bounds completely.
   *
   * @param other The other bounds to check
   * @return true if other is completely contained within this bounds
   */
  fun contains(other: UIBounds): Boolean =
    left <= other.left && top <= other.top &&
      right >= other.right && bottom >= other.bottom

  /**
   * Check if this bounds intersects with another bounds.
   *
   * @param other The other bounds to check
   * @return true if there is any overlap
   */
  fun intersects(other: UIBounds): Boolean =
    left < other.right && right > other.left &&
      top < other.bottom && bottom > other.top

  /**
   * Check if this bounds represents a valid rectangle.
   * A valid rectangle has positive width and height.
   *
   * @return true if width > 0 and height > 0
   */
  fun isValid(): Boolean = width > 0 && height > 0

  /**
   * Calculate the bounds ratio relative to screen dimensions.
   *
   * @param screenWidth Screen width in pixels
   * @param screenHeight Screen height in pixels
   * @return BoundsRatio with values normalized to [0, 1]
   */
  fun toRatio(screenWidth: Int, screenHeight: Int): BoundsRatio {
    require(screenWidth > 0) { "screenWidth must be positive" }
    require(screenHeight > 0) { "screenHeight must be positive" }
    return BoundsRatio(
      leftRatio = left.toFloat() / screenWidth,
      topRatio = top.toFloat() / screenHeight,
      rightRatio = right.toFloat() / screenWidth,
      bottomRatio = bottom.toFloat() / screenHeight,
    )
  }

  companion object {
    /**
     * Create UIBounds from a bounds string in uiautomator format.
     * Format: "[left,top][right,bottom]"
     *
     * @param boundsString The bounds string to parse
     * @return UIBounds if parsing succeeds, null otherwise
     */
    fun fromString(boundsString: String): UIBounds? {
      return try {
        // Pattern: [left,top][right,bottom]
        val regex = """\[(\d+),(\d+)\]\[(\d+),(\d+)\]""".toRegex()
        val match = regex.find(boundsString) ?: return null
        val (left, top, right, bottom) = match.destructured
        UIBounds(
          left = left.toInt(),
          top = top.toInt(),
          right = right.toInt(),
          bottom = bottom.toInt(),
        )
      } catch (e: NumberFormatException) {
        null
      }
    }

    /**
     * Empty bounds at origin with zero size.
     */
    val EMPTY = UIBounds(0, 0, 0, 0)
  }
}

/**
 * Represents bounds as ratios relative to screen dimensions.
 *
 * All values are normalized to the range [0, 1], where:
 * - 0.0 represents the left/top edge of the screen
 * - 1.0 represents the right/bottom edge of the screen
 *
 * This is useful for matching elements across different screen sizes
 * and for caching element positions in a resolution-independent way.
 *
 * @property leftRatio Left edge ratio (0.0 to 1.0)
 * @property topRatio Top edge ratio (0.0 to 1.0)
 * @property rightRatio Right edge ratio (0.0 to 1.0)
 * @property bottomRatio Bottom edge ratio (0.0 to 1.0)
 */
@Serializable
data class BoundsRatio(
  val leftRatio: Float,
  val topRatio: Float,
  val rightRatio: Float,
  val bottomRatio: Float,
) {
  /**
   * Width ratio (rightRatio - leftRatio).
   */
  val widthRatio: Float
    get() = rightRatio - leftRatio

  /**
   * Height ratio (bottomRatio - topRatio).
   */
  val heightRatio: Float
    get() = bottomRatio - topRatio

  /**
   * Center X ratio.
   */
  val centerXRatio: Float
    get() = (leftRatio + rightRatio) / 2f

  /**
   * Center Y ratio.
   */
  val centerYRatio: Float
    get() = (topRatio + bottomRatio) / 2f

  /**
   * Convert back to absolute UIBounds given screen dimensions.
   *
   * @param screenWidth Screen width in pixels
   * @param screenHeight Screen height in pixels
   * @return UIBounds with absolute pixel coordinates
   */
  fun toAbsolute(screenWidth: Int, screenHeight: Int): UIBounds {
    require(screenWidth > 0) { "screenWidth must be positive" }
    require(screenHeight > 0) { "screenHeight must be positive" }
    return UIBounds(
      left = (leftRatio * screenWidth).toInt(),
      top = (topRatio * screenHeight).toInt(),
      right = (rightRatio * screenWidth).toInt(),
      bottom = (bottomRatio * screenHeight).toInt(),
    )
  }

  /**
   * Calculate similarity with another BoundsRatio.
   *
   * Uses the average of absolute differences for each edge.
   * Returns a value between 0 (completely different) and 1 (identical).
   *
   * @param other The other BoundsRatio to compare
   * @return Similarity score between 0 and 1
   */
  fun similarity(other: BoundsRatio): Float {
    val leftDiff = abs(leftRatio - other.leftRatio)
    val topDiff = abs(topRatio - other.topRatio)
    val rightDiff = abs(rightRatio - other.rightRatio)
    val bottomDiff = abs(bottomRatio - other.bottomRatio)
    val avgDiff = (leftDiff + topDiff + rightDiff + bottomDiff) / 4f
    return (1f - avgDiff).coerceIn(0f, 1f)
  }

  companion object {
    /**
     * Empty ratio at origin with zero size.
     */
    val EMPTY = BoundsRatio(0f, 0f, 0f, 0f)

    /**
     * Full screen ratio.
     */
    val FULL_SCREEN = BoundsRatio(0f, 0f, 1f, 1f)
  }
}

/**
 * Represents a 2D point with integer coordinates.
 *
 * @property x X-coordinate in pixels
 * @property y Y-coordinate in pixels
 */
@Serializable
data class Point(
  val x: Int,
  val y: Int,
) {
  /**
   * Calculate distance to another point.
   *
   * @param other The other point
   * @return Euclidean distance
   */
  fun distanceTo(other: Point): Float {
    val dx = (x - other.x).toFloat()
    val dy = (y - other.y).toFloat()
    return sqrt(dx * dx + dy * dy)
  }

  /**
   * Check if this point is within the given bounds.
   *
   * @param bounds The bounds to check against
   * @return true if point is within bounds
   */
  fun isWithin(bounds: UIBounds): Boolean = bounds.contains(x, y)

  companion object {
    /**
     * Origin point (0, 0).
     */
    val ORIGIN = Point(0, 0)
  }
}
