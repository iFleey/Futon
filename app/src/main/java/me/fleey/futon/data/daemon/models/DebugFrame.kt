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
package me.fleey.futon.data.daemon.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.domain.perception.models.UIBounds

/**
 * A debug frame received from the daemon's WebSocket debug stream.
 * Contains real-time detection results and performance metrics.
 */
@Serializable
data class DebugFrame(
  @SerialName("timestamp_ns")
  val timestampNs: Long,
  val fps: Float,
  @SerialName("latency_ms")
  val latencyMs: Float,
  @SerialName("frame_count")
  val frameCount: Long,
  @SerialName("active_delegate")
  val activeDelegate: Int,
  val detections: List<DebugDetection>,
) {
  /**
   * Get the active delegate as enum type.
   */
  fun getActiveDelegateType(): DelegateType = DelegateType.fromInt(activeDelegate)

  /**
   * Timestamp in milliseconds for easier use.
   */
  val timestampMs: Long get() = timestampNs / 1_000_000

  companion object {
    val EMPTY = DebugFrame(
      timestampNs = 0,
      fps = 0f,
      latencyMs = 0f,
      frameCount = 0,
      activeDelegate = DelegateType.NONE.value,
      detections = emptyList(),
    )
  }
}

/**
 * A detection result within a debug frame.
 */
@Serializable
data class DebugDetection(
  @SerialName("class_id")
  val classId: Int,
  val confidence: Float,
  @SerialName("bbox")
  val boundingBox: DebugBoundingBox,
  val text: String? = null,
  @SerialName("text_confidence")
  val textConfidence: Float? = null,
) {
  val hasText: Boolean get() = !text.isNullOrBlank()

  /**
   * Convert to UIBounds for compatibility with existing perception models.
   */
  fun toUIBounds(): UIBounds = UIBounds(
    left = boundingBox.x,
    top = boundingBox.y,
    right = boundingBox.x + boundingBox.width,
    bottom = boundingBox.y + boundingBox.height,
  )
}

/**
 * Bounding box coordinates from daemon debug stream.
 */
@Serializable
data class DebugBoundingBox(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
) {
  val centerX: Int get() = x + width / 2
  val centerY: Int get() = y + height / 2
}
