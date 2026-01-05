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
package me.fleey.futon.domain.som.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Set-of-Mark element representing a detected UI component.
 * Each element has a unique ID that LLM can reference.
 */
@Serializable
data class SomElement(
  val id: Int,
  val type: SomElementType,
  val bounds: SomBounds,
  val text: String? = null,
  val confidence: Float,
  val isClickable: Boolean = true,
  val attributes: Map<String, String> = emptyMap(),
) {
  val centerX: Int get() = bounds.centerX
  val centerY: Int get() = bounds.centerY

  fun toPromptString(): String = buildString {
    append("[$id] ")
    append(type.label)
    if (!text.isNullOrBlank()) {
      append(": \"$text\"")
    }
    append(" @(${centerX},${centerY})")
    if (!isClickable) append(" [non-clickable]")
  }
}

/**
 * Bounding box for SoM elements (absolute pixel coordinates).
 */
@Serializable
data class SomBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  val centerX: Int get() = (left + right) / 2
  val centerY: Int get() = (top + bottom) / 2
  val area: Int get() = width * height

  fun contains(x: Int, y: Int): Boolean =
    x in left..right && y in top..bottom

  fun intersects(other: SomBounds): Boolean =
    left < other.right && right > other.left &&
      top < other.bottom && bottom > other.top

  fun iou(other: SomBounds): Float {
    val intersectLeft = maxOf(left, other.left)
    val intersectTop = maxOf(top, other.top)
    val intersectRight = minOf(right, other.right)
    val intersectBottom = minOf(bottom, other.bottom)

    if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) return 0f

    val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
    val unionArea = area + other.area - intersectArea
    return if (unionArea > 0) intersectArea.toFloat() / unionArea else 0f
  }

  companion object {
    fun fromCenter(centerX: Int, centerY: Int, width: Int, height: Int): SomBounds {
      val halfW = width / 2
      val halfH = height / 2
      return SomBounds(
        left = centerX - halfW,
        top = centerY - halfH,
        right = centerX + halfW,
        bottom = centerY + halfH,
      )
    }
  }
}

/**
 * Element types detected by perception system.
 */
@Serializable
enum class SomElementType(val label: String, val priority: Int) {
  @SerialName("text")
  TEXT("text", 1),

  @SerialName("button")
  BUTTON("button", 2),

  @SerialName("icon")
  ICON("icon", 3),

  @SerialName("input")
  INPUT_FIELD("input", 4),

  @SerialName("checkbox")
  CHECKBOX("checkbox", 5),

  @SerialName("switch")
  SWITCH("switch", 6),

  @SerialName("image")
  IMAGE("image", 7),

  @SerialName("list_item")
  LIST_ITEM("list_item", 8),

  @SerialName("unknown")
  UNKNOWN("unknown", 99);

  companion object {
    fun fromOcrConfidence(confidence: Float, hasClickableParent: Boolean): SomElementType {
      return if (hasClickableParent) BUTTON else TEXT
    }
  }
}

/**
 * Complete SoM annotation result for a screen.
 */
@Serializable
data class SomAnnotation(
  val elements: List<SomElement>,
  val screenWidth: Int,
  val screenHeight: Int,
  val timestamp: Long = System.currentTimeMillis(),
  val perceptionLatencyMs: Long = 0,
) {
  val elementCount: Int get() = elements.size

  fun getElementById(id: Int): SomElement? = elements.find { it.id == id }

  fun getElementsByType(type: SomElementType): List<SomElement> =
    elements.filter { it.type == type }

  fun getClickableElements(): List<SomElement> =
    elements.filter { it.isClickable }

  fun findElementByText(text: String, ignoreCase: Boolean = true): SomElement? =
    elements.find {
      it.text?.contains(text, ignoreCase = ignoreCase) == true
    }

  fun toPromptContext(): String = buildString {
    appendLine("=== UI Elements (${elements.size} detected) ===")
    appendLine("Screen: ${screenWidth}x${screenHeight}")
    appendLine()
    elements.sortedBy { it.id }.forEach { element ->
      appendLine(element.toPromptString())
    }
    appendLine()
    appendLine("To interact with an element, use its [id] number.")
    appendLine("Example: tap element [3]  action: tap, element_id: 3")
  }
}

/**
 * SoM-aware action from LLM.
 */
@Serializable
data class SomAction(
  val action: SomActionType,
  @SerialName("element_id")
  val elementId: Int? = null,
  val parameters: SomActionParameters? = null,
  val reasoning: String? = null,
  @SerialName("task_complete")
  val taskComplete: Boolean = false,
)

@Serializable
enum class SomActionType {
  @SerialName("tap")
  TAP,

  @SerialName("long_press")
  LONG_PRESS,

  @SerialName("double_tap")
  DOUBLE_TAP,

  @SerialName("swipe")
  SWIPE,

  @SerialName("scroll")
  SCROLL,

  @SerialName("input")
  INPUT,

  @SerialName("tap_coordinate")
  TAP_COORDINATE,

  @SerialName("back")
  BACK,

  @SerialName("home")
  HOME,

  @SerialName("launch_app")
  LAUNCH_APP,

  @SerialName("wait")
  WAIT,

  @SerialName("complete")
  COMPLETE,

  @SerialName("error")
  ERROR,
}

@Serializable
data class SomActionParameters(
  val x: Int? = null,
  val y: Int? = null,
  val x1: Int? = null,
  val y1: Int? = null,
  val x2: Int? = null,
  val y2: Int? = null,
  val text: String? = null,
  val direction: String? = null,
  val distance: Int? = null,
  val duration: Int? = null,
  @SerialName("package")
  val packageName: String? = null,
  val message: String? = null,
)
