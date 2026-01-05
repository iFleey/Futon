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
package me.fleey.futon.data.trace

import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.PerceptionResult
import org.koin.core.annotation.Single
import java.security.MessageDigest

/**
 * Computes deterministic hashes for UI states.
 *
 * The hash is based on:
 * - Element positions (bounding boxes)
 * - Element types
 * - Text content
 */
interface UIHashComputer {
  /**
   * Compute a hash for a perception result.
   *
   * @param result The perception result to hash
   * @return Deterministic hash string
   */
  fun computeHash(result: PerceptionResult): String

  /**
   * Compute a hash for a list of detected elements.
   *
   * @param elements The elements to hash
   * @param imageWidth Width of the source image
   * @param imageHeight Height of the source image
   * @return Deterministic hash string
   */
  fun computeHash(
    elements: List<DetectedElement>,
    imageWidth: Int,
    imageHeight: Int,
  ): String
}

@Single(binds = [UIHashComputer::class])
class UIHashComputerImpl : UIHashComputer {

  override fun computeHash(result: PerceptionResult): String {
    return computeHash(result.elements, result.imageWidth, result.imageHeight)
  }

  override fun computeHash(
    elements: List<DetectedElement>,
    imageWidth: Int,
    imageHeight: Int,
  ): String {
    if (elements.isEmpty()) {
      return EMPTY_HASH
    }

    val sortedElements = elements.sortedWith(
      compareBy(
        { it.boundingBox.top },
        { it.boundingBox.left },
        { it.elementType.ordinal },
      ),
    )

    val hashInput = buildString {
      append("$imageWidth,$imageHeight|")

      sortedElements.forEach { element ->
        val normalizedLeft = normalizeCoordinate(element.boundingBox.left, imageWidth)
        val normalizedTop = normalizeCoordinate(element.boundingBox.top, imageHeight)
        val normalizedRight = normalizeCoordinate(element.boundingBox.right, imageWidth)
        val normalizedBottom = normalizeCoordinate(element.boundingBox.bottom, imageHeight)

        append("${element.elementType.name}:")
        append("$normalizedLeft,$normalizedTop,$normalizedRight,$normalizedBottom:")
        append("${element.text?.normalizeText() ?: ""}|")
      }
    }

    return sha256(hashInput)
  }

  private fun normalizeCoordinate(value: Int, max: Int): Int {
    if (max <= 0) return 0
    return ((value.toFloat() / max) * GRID_SIZE).toInt().coerceIn(0, GRID_SIZE)
  }

  private fun String.normalizeText(): String {
    return this.trim()
      .lowercase()
      .replace(Regex("\\s+"), " ")
      .take(MAX_TEXT_LENGTH)
  }

  private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
  }

  companion object {
    private const val GRID_SIZE = 100
    private const val MAX_TEXT_LENGTH = 50
    private const val EMPTY_HASH = "empty_ui_state"
  }
}
