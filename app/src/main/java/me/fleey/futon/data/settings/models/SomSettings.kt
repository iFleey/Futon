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
package me.fleey.futon.data.settings.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings for Set-of-Mark (SoM) prompting mode.
 */
@Serializable
data class SomSettings(
  @SerialName("enabled")
  val enabled: Boolean = true,

  /** Render visual markers on screenshot for LLM */
  @SerialName("render_annotations")
  val renderAnnotations: Boolean = true,

  @SerialName("include_ui_tree")
  val includeUITree: Boolean = true,

  @SerialName("min_confidence")
  val minConfidence: Float = 0.3f,

  @SerialName("max_elements")
  val maxElements: Int = 50,

  /** Marker size in pixels */
  @SerialName("marker_size")
  val markerSize: Int = 24,

  /** Show bounding boxes around elements */
  @SerialName("show_bounding_boxes")
  val showBoundingBoxes: Boolean = true,

  @SerialName("screenshot_quality")
  val screenshotQuality: Int = 85,

  /** Filter out small elements (area < minElementArea) */
  @SerialName("filter_small_elements")
  val filterSmallElements: Boolean = true,

  /** Minimum element area in pixels */
  @SerialName("min_element_area")
  val minElementArea: Int = 100,

  /** IoU threshold for merging overlapping elements */
  @SerialName("merge_iou_threshold")
  val mergeIouThreshold: Float = 0.7f,
) {
  companion object {
    val DEFAULT = SomSettings()

    val HIGH_ACCURACY = SomSettings(
      minConfidence = 0.5f,
      maxElements = 30,
      filterSmallElements = true,
      minElementArea = 200,
    )

    val PERFORMANCE = SomSettings(
      minConfidence = 0.4f,
      maxElements = 25,
      renderAnnotations = true,
      includeUITree = false,
      screenshotQuality = 70,
    )
  }
}

/**
 * SoM perception mode.
 */
@Serializable
enum class SomMode {
  /** Full SoM with annotated screenshots and element lists */
  @SerialName("full")
  FULL,

  @SerialName("elements_only")
  ELEMENTS_ONLY,

  /** Legacy mode (raw screenshot, no SoM) */
  @SerialName("legacy")
  LEGACY,
}
