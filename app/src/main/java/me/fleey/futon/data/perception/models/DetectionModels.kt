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

/**
 * Represents a detected UI element from the detection model.
 */
data class Detection(
  val boundingBox: BoundingBox,
  val classId: Int,
  val className: String,
  val confidence: Float,
) {
  init {
    require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
    require(classId >= 0) { "Class ID must be non-negative" }
  }

  fun toUIBounds(imageWidth: Int, imageHeight: Int): UIBounds {
    return boundingBox.toAbsolute(imageWidth, imageHeight)
  }
}

/**
 * Bounding box with normalized coordinates [0, 1].
 */
data class BoundingBox(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
) {
  val centerX: Float get() = x + width / 2f
  val centerY: Float get() = y + height / 2f
  val area: Float get() = width * height

  val left: Float get() = x
  val top: Float get() = y
  val right: Float get() = x + width
  val bottom: Float get() = y + height

  init {
    require(x in 0f..1f) { "x must be between 0 and 1, got $x" }
    require(y in 0f..1f) { "y must be between 0 and 1, got $y" }
    require(width in 0f..1f) { "width must be between 0 and 1, got $width" }
    require(height in 0f..1f) { "height must be between 0 and 1, got $height" }
  }

  fun toAbsolute(imageWidth: Int, imageHeight: Int): UIBounds {
    return UIBounds(
      left = (x * imageWidth).toInt(),
      top = (y * imageHeight).toInt(),
      right = ((x + width) * imageWidth).toInt(),
      bottom = ((y + height) * imageHeight).toInt(),
    )
  }

  fun intersects(other: BoundingBox): Boolean {
    return left < other.right && right > other.left &&
      top < other.bottom && bottom > other.top
  }

  companion object {
    val EMPTY = BoundingBox(0f, 0f, 0f, 0f)

    fun fromNormalized(
      yMin: Float,
      xMin: Float,
      yMax: Float,
      xMax: Float,
    ): BoundingBox {
      val clampedXMin = xMin.coerceIn(0f, 1f)
      val clampedYMin = yMin.coerceIn(0f, 1f)
      val clampedXMax = xMax.coerceIn(0f, 1f)
      val clampedYMax = yMax.coerceIn(0f, 1f)

      return BoundingBox(
        x = clampedXMin,
        y = clampedYMin,
        width = (clampedXMax - clampedXMin).coerceAtLeast(0f),
        height = (clampedYMax - clampedYMin).coerceAtLeast(0f),
      )
    }
  }
}

/**
 * UI element types detected by the model.
 */
enum class ElementType(val label: String, val classId: Int) {
  BUTTON("button", 0),
  TEXT_FIELD("text_field", 1),
  CHECKBOX("checkbox", 2),
  SWITCH("switch", 3),
  ICON("icon", 4),
  TEXT_LABEL("text_label", 5),
  IMAGE("image", 6),
  LIST_ITEM("list_item", 7),
  CARD("card", 8),
  TOOLBAR("toolbar", 9),
  UNKNOWN("unknown", -1);

  companion object {
    private val byClassId = entries.associateBy { it.classId }
    private val byLabel = entries.associateBy { it.label.lowercase() }

    fun fromClassId(classId: Int): ElementType {
      return byClassId[classId] ?: UNKNOWN
    }

    fun fromLabel(label: String): ElementType {
      return byLabel[label.lowercase()] ?: UNKNOWN
    }
  }
}

/**
 * Result of UI detection operation.
 */
sealed interface DetectionResult {
  data class Success(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val latencyNs: Long,
    val activeDelegate: DelegateType,
  ) : DetectionResult {
    val latencyMs: Float get() = latencyNs / 1_000_000f
    val isEmpty: Boolean get() = detections.isEmpty()
  }

  data class Failure(
    val error: DetectionError,
    val message: String,
  ) : DetectionResult
}

/**
 * Error types for detection operations.
 */
enum class DetectionError {
  NOT_INITIALIZED,
  MODEL_NOT_LOADED,
  INVALID_INPUT,
  INFERENCE_FAILED,
  POST_PROCESSING_FAILED,
  TIMEOUT,
  UNKNOWN
}

/**
 * Configuration for UI detection.
 */
data class DetectionConfig(
  val minConfidence: Float = 0.5f,
  val maxDetections: Int = 25,
  val enableNms: Boolean = true,
  val nmsIouThreshold: Float = 0.5f,
  val inputWidth: Int = 320,
  val inputHeight: Int = 320,
) {
  init {
    require(minConfidence in 0f..1f) { "minConfidence must be between 0 and 1" }
    require(maxDetections > 0) { "maxDetections must be positive" }
    require(nmsIouThreshold in 0f..1f) { "nmsIouThreshold must be between 0 and 1" }
    require(inputWidth > 0) { "inputWidth must be positive" }
    require(inputHeight > 0) { "inputHeight must be positive" }
  }

  companion object {
    val DEFAULT = DetectionConfig()
  }
}
