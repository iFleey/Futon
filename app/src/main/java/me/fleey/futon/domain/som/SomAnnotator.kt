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
package me.fleey.futon.domain.som

import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree
import me.fleey.futon.domain.som.models.SomAnnotation
import me.fleey.futon.domain.som.models.SomBounds
import me.fleey.futon.domain.som.models.SomElement
import me.fleey.futon.domain.som.models.SomElementType
import org.koin.core.annotation.Single

data class SomAnnotatorConfig(
  val minConfidence: Float = 0.3f,
  val maxElements: Int = 50,
  val mergeIouThreshold: Float = 0.7f,
  val minTextLength: Int = 1,
  val maxTextLength: Int = 100,
  val filterSmallElements: Boolean = true,
  val minElementArea: Int = 100,
)

/**
 * Annotates screen elements with unique IDs for Set-of-Mark prompting.
 * Combines OCR results from daemon and UI tree into unified SoM elements.
 */
interface SomAnnotator {
  /**
   * Create SoM annotation from perception results.
   * @param detectedElements Detection results from daemon (DetectedElement)
   * @param screenWidth Screen width in pixels
   * @param screenHeight Screen height in pixels
   * @param uiTree Optional UI tree from accessibility service
   * @param config Annotation configuration
   */
  fun annotate(
    detectedElements: List<DetectedElement>,
    screenWidth: Int,
    screenHeight: Int,
    uiTree: UITree? = null,
    config: SomAnnotatorConfig = SomAnnotatorConfig(),
  ): SomAnnotation
}

@Single(binds = [SomAnnotator::class])
class SomAnnotatorImpl : SomAnnotator {

  override fun annotate(
    detectedElements: List<DetectedElement>,
    screenWidth: Int,
    screenHeight: Int,
    uiTree: UITree?,
    config: SomAnnotatorConfig,
  ): SomAnnotation {
    val startTime = System.currentTimeMillis()

    // Convert DetectedElement to SoM elements (OCR results)
    val ocrElements = detectedElements
      .filter { it.confidence >= config.minConfidence }
      .mapNotNull { element ->
        convertDetectedElementToSom(element, screenWidth, screenHeight, config)
      }

    val uiTreeElements = uiTree?.let {
      extractElementsFromUITree(it.root, config)
    } ?: emptyList()

    val mergedElements = mergeElements(
      ocrElements,
      uiTreeElements,
      config.mergeIouThreshold,
    )

    val filteredElements = mergedElements
      .filter { element ->
        if (config.filterSmallElements) {
          element.bounds.area >= config.minElementArea
        } else true
      }
      .sortedWith(
        compareBy(
          { it.bounds.top },
          { it.bounds.left },
        ),
      )
      .take(config.maxElements)

    // Assign sequential IDs (1-based for LLM readability)
    val numberedElements = filteredElements.mapIndexed { index, element ->
      element.copy(id = index + 1)
    }

    val latencyMs = System.currentTimeMillis() - startTime

    return SomAnnotation(
      elements = numberedElements,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
      perceptionLatencyMs = latencyMs,
    )
  }

  private fun convertDetectedElementToSom(
    element: DetectedElement,
    screenWidth: Int,
    screenHeight: Int,
    config: SomAnnotatorConfig,
  ): SomElement? {
    val bbox = element.boundingBox
    val bounds = SomBounds(
      left = bbox.left.coerceIn(0, screenWidth),
      top = bbox.top.coerceIn(0, screenHeight),
      right = bbox.right.coerceIn(0, screenWidth),
      bottom = bbox.bottom.coerceIn(0, screenHeight),
    )

    if (bounds.width <= 0 || bounds.height <= 0) return null

    val text = element.text?.takeIf {
      it.length in config.minTextLength..config.maxTextLength
    }

    val somElementType = mapElementTypeToSom(element.elementType, text)

    return SomElement(
      id = 0,
      type = somElementType,
      bounds = bounds,
      text = text,
      confidence = element.confidence,
      isClickable = isClickableType(somElementType),
    )
  }

  private fun mapElementTypeToSom(
    elementType: ElementType,
    text: String?,
  ): SomElementType {
    return when (elementType) {
      ElementType.BUTTON -> SomElementType.BUTTON
      ElementType.ICON -> SomElementType.ICON
      ElementType.TEXT_LABEL -> SomElementType.TEXT
      ElementType.TEXT_FIELD -> SomElementType.INPUT_FIELD
      ElementType.CHECKBOX -> SomElementType.CHECKBOX
      ElementType.SWITCH -> SomElementType.SWITCH
      ElementType.IMAGE -> SomElementType.IMAGE
      ElementType.LIST_ITEM -> SomElementType.LIST_ITEM
      ElementType.CARD -> SomElementType.UNKNOWN
      ElementType.TOOLBAR -> SomElementType.UNKNOWN
      ElementType.UNKNOWN -> if (!text.isNullOrBlank()) SomElementType.TEXT else SomElementType.UNKNOWN
    }
  }

  private fun isClickableType(type: SomElementType): Boolean {
    return type in listOf(
      SomElementType.BUTTON,
      SomElementType.ICON,
      SomElementType.INPUT_FIELD,
      SomElementType.CHECKBOX,
      SomElementType.SWITCH,
      SomElementType.LIST_ITEM,
      SomElementType.TEXT,
    )
  }

  private fun extractElementsFromUITree(
    node: UINode,
    config: SomAnnotatorConfig,
  ): List<SomElement> {
    val elements = mutableListOf<SomElement>()

    // Only include nodes with text or that are clickable
    if (node.hasTextContent() || node.isClickable) {
      val bounds = SomBounds(
        left = node.bounds.left,
        top = node.bounds.top,
        right = node.bounds.right,
        bottom = node.bounds.bottom,
      )

      if (bounds.width > 0 && bounds.height > 0) {
        val text = node.text ?: node.contentDesc
        val filteredText = text?.takeIf {
          it.length in config.minTextLength..config.maxTextLength
        }

        val elementType = mapUINodeToElementType(node)

        elements.add(
          SomElement(
            id = 0,
            type = elementType,
            bounds = bounds,
            text = filteredText,
            confidence = 1.0f,
            isClickable = node.isClickable,
            attributes = buildMap {
              node.resourceId?.let { put("resource_id", it) }
              put("class", node.className)
            },
          ),
        )
      }
    }

    node.children.forEach { child ->
      elements.addAll(extractElementsFromUITree(child, config))
    }

    return elements
  }

  private fun mapUINodeToElementType(node: UINode): SomElementType {
    val className = node.className.lowercase()
    return when {
      className.contains("button") -> SomElementType.BUTTON
      className.contains("edittext") || className.contains("textinput") -> SomElementType.INPUT_FIELD
      className.contains("checkbox") -> SomElementType.CHECKBOX
      className.contains("switch") || className.contains("toggle") -> SomElementType.SWITCH
      className.contains("imageview") || className.contains("imagebutton") -> {
        if (node.isClickable) SomElementType.ICON else SomElementType.IMAGE
      }

      className.contains("recyclerview") || className.contains("listview") -> SomElementType.LIST_ITEM
      node.hasTextContent() -> SomElementType.TEXT
      else -> SomElementType.UNKNOWN
    }
  }

  private fun mergeElements(
    detectionElements: List<SomElement>,
    uiTreeElements: List<SomElement>,
    iouThreshold: Float,
  ): List<SomElement> {
    if (uiTreeElements.isEmpty()) return detectionElements
    if (detectionElements.isEmpty()) return uiTreeElements

    val merged = mutableListOf<SomElement>()
    val usedUITreeIndices = mutableSetOf<Int>()

    // For each detection element, find matching UI tree element
    for (detElement in detectionElements) {
      var bestMatch: SomElement? = null
      var bestMatchIndex = -1
      var bestIou = 0f

      uiTreeElements.forEachIndexed { index, uiElement ->
        if (index !in usedUITreeIndices) {
          val iou = detElement.bounds.iou(uiElement.bounds)
          if (iou > bestIou && iou >= iouThreshold) {
            bestIou = iou
            bestMatch = uiElement
            bestMatchIndex = index
          }
        }
      }

      if (bestMatch != null && bestMatchIndex >= 0) {
        // Merge: prefer UI tree attributes, detection confidence
        usedUITreeIndices.add(bestMatchIndex)
        merged.add(
          detElement.copy(
            text = bestMatch.text ?: detElement.text,
            isClickable = bestMatch.isClickable,
            attributes = bestMatch.attributes + detElement.attributes,
          ),
        )
      } else {
        merged.add(detElement)
      }
    }

    uiTreeElements.forEachIndexed { index, element ->
      if (index !in usedUITreeIndices) {
        merged.add(element)
      }
    }

    return merged
  }
}
