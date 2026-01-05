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

import me.fleey.futon.data.perception.models.BoundingBox
import me.fleey.futon.data.perception.models.Detection
import me.fleey.futon.data.perception.models.ElementType
import kotlin.math.max
import kotlin.math.min

/**
 * Post-processor for UI detection model outputs.
 */
class DetectionPostProcessor {

  companion object {
    private val LABELS = listOf(
      "button",
      "text_field",
      "checkbox",
      "switch",
      "icon",
      "text_label",
      "image",
      "list_item",
      "card",
      "toolbar",
    )
  }

  /**
   * Process EfficientDet output tensors into Detection objects.
   *
   * @param boxes Bounding boxes tensor [N, 4] in format [ymin, xmin, ymax, xmax] normalized
   * @param classes Class IDs tensor [N]
   * @param scores Confidence scores tensor [N]
   * @param numDetections Number of valid detections
   * @param minConfidence Minimum confidence threshold
   * @return List of Detection objects passing the confidence threshold
   */
  fun process(
    boxes: FloatArray,
    classes: FloatArray,
    scores: FloatArray,
    numDetections: Int,
    minConfidence: Float = 0.5f,
  ): List<Detection> {
    val detections = mutableListOf<Detection>()

    val validCount = minOf(numDetections, scores.size, classes.size, boxes.size / 4)

    for (i in 0 until validCount) {
      val score = scores[i]

      if (score < minConfidence) continue

      val boxOffset = i * 4
      if (boxOffset + 3 >= boxes.size) break

      val yMin = boxes[boxOffset]
      val xMin = boxes[boxOffset + 1]
      val yMax = boxes[boxOffset + 2]
      val xMax = boxes[boxOffset + 3]

      if (!isValidBox(xMin, yMin, xMax, yMax)) continue

      val classId = classes[i].toInt()
      val className = getClassName(classId)

      val boundingBox = BoundingBox.fromNormalized(yMin, xMin, yMax, xMax)

      detections.add(
        Detection(
          boundingBox = boundingBox,
          classId = classId,
          className = className,
          confidence = score,
        ),
      )
    }

    return detections
  }

  /**
   * Process raw inference output (single flattened array) into Detection objects.
   *
   * This handles the case where all outputs are concatenated into a single array.
   *
   * @param output Raw inference output
   * @param maxDetections Maximum number of detections in the output
   * @param minConfidence Minimum confidence threshold
   * @return List of Detection objects
   */
  fun processRawOutput(
    output: FloatArray,
    maxDetections: Int = 25,
    minConfidence: Float = 0.5f,
  ): List<Detection> {
    // EfficientDet-Lite0 output format:
    // - boxes: [1, N, 4] -> N * 4 floats
    // - classes: [1, N] -> N floats
    // - scores: [1, N] -> N floats
    // - count: [1] -> 1 float

    val boxesSize = maxDetections * 4

    val expectedSize = boxesSize + maxDetections + maxDetections + 1
    if (output.size < expectedSize) {
      return emptyList()
    }

    val boxes = output.sliceArray(0 until boxesSize)
    val classes = output.sliceArray(boxesSize until boxesSize + maxDetections)
    val scores =
      output.sliceArray(boxesSize + maxDetections until boxesSize + maxDetections + maxDetections)
    val numDetections = output.getOrElse(boxesSize + maxDetections + maxDetections) { 0f }.toInt()

    return process(boxes, classes, scores, numDetections, minConfidence)
  }

  /**
   * Apply Non-Maximum Suppression to remove overlapping detections.
   *
   * @param detections List of detections to filter
   * @param iouThreshold IoU threshold for suppression
   * @return Filtered list of detections
   */
  fun applyNms(
    detections: List<Detection>,
    iouThreshold: Float = 0.5f,
  ): List<Detection> {
    if (detections.isEmpty()) return emptyList()

    val sortedDetections = detections.sortedByDescending { it.confidence }
    val selected = mutableListOf<Detection>()
    val suppressed = BooleanArray(sortedDetections.size)

    for (i in sortedDetections.indices) {
      if (suppressed[i]) continue

      val current = sortedDetections[i]
      selected.add(current)

      for (j in i + 1 until sortedDetections.size) {
        if (suppressed[j]) continue

        val other = sortedDetections[j]

        if (current.classId == other.classId) {
          val iou = computeIoU(current.boundingBox, other.boundingBox)
          if (iou >= iouThreshold) {
            suppressed[j] = true
          }
        }
      }
    }

    return selected
  }

  /**
   * Compute Intersection over Union (IoU) between two bounding boxes.
   *
   * @param a First bounding box
   * @param b Second bounding box
   * @return IoU value between 0 and 1
   */
  fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
    val intersectionLeft = max(a.left, b.left)
    val intersectionTop = max(a.top, b.top)
    val intersectionRight = min(a.right, b.right)
    val intersectionBottom = min(a.bottom, b.bottom)

    val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
    val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
    val intersectionArea = intersectionWidth * intersectionHeight

    val areaA = a.area
    val areaB = b.area
    val unionArea = areaA + areaB - intersectionArea

    return if (unionArea > 0f) intersectionArea / unionArea else 0f
  }

  /**
   * Filter detections by confidence threshold.
   *
   * @param detections List of detections
   * @param minConfidence Minimum confidence threshold
   * @return Filtered list
   */
  fun filterByConfidence(
    detections: List<Detection>,
    minConfidence: Float,
  ): List<Detection> {
    return detections.filter { it.confidence >= minConfidence }
  }

  /**
   * Filter detections by element type.
   *
   * @param detections List of detections
   * @param types Set of element types to keep
   * @return Filtered list
   */
  fun filterByType(
    detections: List<Detection>,
    types: Set<ElementType>,
  ): List<Detection> {
    val classIds = types.map { it.classId }.toSet()
    return detections.filter { it.classId in classIds }
  }

  /**
   * Sort detections by position (top-to-bottom, left-to-right).
   *
   * @param detections List of detections
   * @return Sorted list
   */
  fun sortByPosition(detections: List<Detection>): List<Detection> {
    return detections.sortedWith(
      compareBy(
        { it.boundingBox.y },
        { it.boundingBox.x },
      ),
    )
  }

  /**
   * Sort detections by confidence (highest first).
   *
   * @param detections List of detections
   * @return Sorted list
   */
  fun sortByConfidence(detections: List<Detection>): List<Detection> {
    return detections.sortedByDescending { it.confidence }
  }

  /**
   * Sort detections by area (largest first).
   *
   * @param detections List of detections
   * @return Sorted list
   */
  fun sortByArea(detections: List<Detection>): List<Detection> {
    return detections.sortedByDescending { it.boundingBox.area }
  }

  private fun isValidBox(xMin: Float, yMin: Float, xMax: Float, yMax: Float): Boolean {
    return xMin < xMax && yMin < yMax &&
      xMin >= 0f && yMin >= 0f &&
      xMax <= 1f && yMax <= 1f
  }

  private fun getClassName(classId: Int): String {
    return LABELS.getOrElse(classId) { "unknown" }
  }
}
