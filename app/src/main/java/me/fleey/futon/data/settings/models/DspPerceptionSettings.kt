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

import kotlinx.serialization.Serializable
import me.fleey.futon.platform.input.models.InjectionMode

/**
 * Settings for DSP-accelerated perception system (LiteRT + OCR).
 * Root-Only architecture: Uses root-based input injection.
 *
 * @property enabled Master switch for DSP perception in automation
 * @property injectionMode Touch injection mode (Root uinput only in Root-Only architecture)
 * @property downscaleFactor Capture downscale factor (1 = full res, 2 = half res)
 * @property targetLatencyMs Target latency for perception loop in milliseconds
 * @property enableOcr Whether to enable OCR for text recognition
 * @property minConfidence Minimum confidence threshold for detection results
 * @property maxConcurrentBuffers Maximum number of concurrent capture buffers
 * @property touchDevicePath User-selected touch input device path. Empty string means auto-detect.
 */
@Serializable
data class DspPerceptionSettings(
  val enabled: Boolean = false,
  val injectionMode: InjectionMode = InjectionMode.ROOT_UINPUT,
  val downscaleFactor: Int = DEFAULT_DOWNSCALE_FACTOR,
  val targetLatencyMs: Long = DEFAULT_TARGET_LATENCY_MS,
  val enableOcr: Boolean = true,
  val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
  val maxConcurrentBuffers: Int = DEFAULT_MAX_BUFFERS,
  val touchDevicePath: String = AUTO_DETECT_PATH,
) {
  fun isDownscaleValid(): Boolean = downscaleFactor in MIN_DOWNSCALE..MAX_DOWNSCALE
  fun isLatencyValid(): Boolean = targetLatencyMs in MIN_LATENCY_MS..MAX_LATENCY_MS
  fun isConfidenceValid(): Boolean = minConfidence in 0f..1f
  fun isBuffersValid(): Boolean = maxConcurrentBuffers in MIN_BUFFERS..MAX_BUFFERS

  fun withValidDownscale(): DspPerceptionSettings = copy(
    downscaleFactor = downscaleFactor.coerceIn(MIN_DOWNSCALE, MAX_DOWNSCALE),
  )

  fun withValidLatency(): DspPerceptionSettings = copy(
    targetLatencyMs = targetLatencyMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS),
  )

  fun withValidConfidence(): DspPerceptionSettings = copy(
    minConfidence = minConfidence.coerceIn(0f, 1f),
  )

  fun withValidBuffers(): DspPerceptionSettings = copy(
    maxConcurrentBuffers = maxConcurrentBuffers.coerceIn(MIN_BUFFERS, MAX_BUFFERS),
  )

  companion object {
    const val MIN_DOWNSCALE = 1
    const val MAX_DOWNSCALE = 4
    const val DEFAULT_DOWNSCALE_FACTOR = 1

    const val MIN_LATENCY_MS = 10L
    const val MAX_LATENCY_MS = 100L
    const val DEFAULT_TARGET_LATENCY_MS = 30L

    const val DEFAULT_MIN_CONFIDENCE = 0.5f

    const val MIN_BUFFERS = 1
    const val MAX_BUFFERS = 5
    const val DEFAULT_MAX_BUFFERS = 3

    /** Auto-detect sentinel value for device path */
    const val AUTO_DETECT_PATH = ""
  }
}
