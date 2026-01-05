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

/**
 * Capability flags aligned with daemon's C++ DaemonCapability enum.
 *
 * Core capabilities (bits 0-5) match daemon bit positions exactly.
 * Extended capabilities (bits 8+) are app-specific.
 */
object CapabilityFlags {
  const val NONE: Int = 0

  // Core capabilities (aligned with daemon's DaemonCapability enum)
  const val SCREEN_CAPTURE: Int = 1 shl 0     // bit 0
  const val INPUT_INJECTION: Int = 1 shl 1    // bit 1
  const val OBJECT_DETECTION: Int = 1 shl 2   // bit 2
  const val OCR: Int = 1 shl 3                // bit 3
  const val HOT_PATH: Int = 1 shl 4           // bit 4
  const val DEBUG_STREAM: Int = 1 shl 5       // bit 5

  // Extended capabilities (app-specific, bits 8+)
  const val GPU_DELEGATE: Int = 1 shl 8
  const val HEXAGON_DSP: Int = 1 shl 9
  const val HARDWARE_BUFFER: Int = 1 shl 10
  const val MULTI_TOUCH: Int = 1 shl 11
  const val TEXT_INPUT: Int = 1 shl 12
  const val LOOP_DETECTION: Int = 1 shl 13

  fun hasCapability(capabilities: Int, flag: Int): Boolean =
    (capabilities and flag) == flag

  // Core capability checks (daemon-aligned)
  fun hasScreenCapture(capabilities: Int): Boolean =
    hasCapability(capabilities, SCREEN_CAPTURE)

  fun hasInputInjection(capabilities: Int): Boolean =
    hasCapability(capabilities, INPUT_INJECTION)

  fun hasObjectDetection(capabilities: Int): Boolean =
    hasCapability(capabilities, OBJECT_DETECTION)

  fun hasOcr(capabilities: Int): Boolean =
    hasCapability(capabilities, OCR)

  fun hasHotPath(capabilities: Int): Boolean =
    hasCapability(capabilities, HOT_PATH)

  fun hasDebugStream(capabilities: Int): Boolean =
    hasCapability(capabilities, DEBUG_STREAM)

  // Extended capability checks (app-specific)
  fun hasGpuDelegate(capabilities: Int): Boolean =
    hasCapability(capabilities, GPU_DELEGATE)

  fun hasHexagonDsp(capabilities: Int): Boolean =
    hasCapability(capabilities, HEXAGON_DSP)

  fun hasHardwareBuffer(capabilities: Int): Boolean =
    hasCapability(capabilities, HARDWARE_BUFFER)

  fun hasMultiTouch(capabilities: Int): Boolean =
    hasCapability(capabilities, MULTI_TOUCH)

  fun hasTextInput(capabilities: Int): Boolean =
    hasCapability(capabilities, TEXT_INPUT)

  fun hasLoopDetection(capabilities: Int): Boolean =
    hasCapability(capabilities, LOOP_DETECTION)

  fun toReadableList(capabilities: Int): List<String> = buildList {
    // Core capabilities (daemon-reported)
    if (hasScreenCapture(capabilities)) add("Screen Capture")
    if (hasInputInjection(capabilities)) add("Input Injection")
    if (hasObjectDetection(capabilities)) add("Object Detection")
    if (hasOcr(capabilities)) add("OCR")
    if (hasHotPath(capabilities)) add("Hot Path")
    if (hasDebugStream(capabilities)) add("Debug Stream")
    // Extended capabilities (app-specific)
    if (hasGpuDelegate(capabilities)) add("GPU")
    if (hasHexagonDsp(capabilities)) add("Hexagon DSP")
    if (hasHardwareBuffer(capabilities)) add("HardwareBuffer")
    if (hasMultiTouch(capabilities)) add("Multi-Touch")
    if (hasTextInput(capabilities)) add("Text Input")
    if (hasLoopDetection(capabilities)) add("Loop Detection")
  }
}
