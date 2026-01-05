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

import me.fleey.futon.domain.perception.models.UIBounds

enum class TextScript(val displayName: String) {
  LATIN("Latin"),
  CHINESE("Chinese"),
  JAPANESE("Japanese"),
  KOREAN("Korean");

  companion object {
    val DEFAULT = setOf(LATIN)
    val ALL = entries.toSet()
    val CJK = setOf(CHINESE, JAPANESE, KOREAN)
  }
}

data class OCRResult(
  val text: String,
  val blocks: List<TextBlock>,
  val confidence: Float,
  val latencyMs: Long,
) {
  val isEmpty: Boolean get() = text.isBlank()
  val blockCount: Int get() = blocks.size
  val lineCount: Int get() = blocks.sumOf { it.lines.size }
  val elementCount: Int get() = blocks.sumOf { block -> block.lines.sumOf { it.elements.size } }

  companion object {
    val EMPTY = OCRResult(
      text = "",
      blocks = emptyList(),
      confidence = 0f,
      latencyMs = 0,
    )
  }
}

data class TextBlock(
  val text: String,
  val boundingBox: UIBounds,
  val confidence: Float,
  val lines: List<TextLine>,
) {
  val isEmpty: Boolean get() = text.isBlank()
  val lineCount: Int get() = lines.size
}

data class TextLine(
  val text: String,
  val boundingBox: UIBounds,
  val confidence: Float,
  val elements: List<TextElement>,
) {
  val isEmpty: Boolean get() = text.isBlank()
  val elementCount: Int get() = elements.size
}

data class TextElement(
  val text: String,
  val boundingBox: UIBounds,
  val confidence: Float,
) {
  val isEmpty: Boolean get() = text.isBlank()
}

data class OCRConfig(
  val scripts: Set<TextScript> = TextScript.DEFAULT,
  val minConfidence: Float = 0.0f,
  val enableBlockDetection: Boolean = true,
) {
  init {
    require(scripts.isNotEmpty()) { "At least one script must be specified" }
    require(minConfidence in 0f..1f) { "minConfidence must be between 0 and 1" }
  }

  companion object {
    val DEFAULT = OCRConfig()
    val LATIN_ONLY = OCRConfig(scripts = setOf(TextScript.LATIN))
    val CJK = OCRConfig(scripts = TextScript.CJK)
    val ALL_SCRIPTS = OCRConfig(scripts = TextScript.ALL)
  }
}

sealed interface OCROperationResult {
  data class Success(val result: OCRResult) : OCROperationResult
  data class Failure(val error: OCRError, val message: String) : OCROperationResult
}

enum class OCRError {
  NOT_INITIALIZED,
  SCRIPT_NOT_SUPPORTED,
  INVALID_INPUT,
  RECOGNITION_FAILED,
  TIMEOUT,
  UNKNOWN
}
