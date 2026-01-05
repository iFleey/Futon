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

import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.HardwareCapabilities

/**
 * Interface for detecting hardware accelerators for ML inference.
 */
interface HardwareDetector {
  fun detectCapabilities(): HardwareCapabilities

  fun hasGpu(): Boolean = detectCapabilities().hasGpu

  fun hasHexagonDsp(): Boolean = detectCapabilities().hasHexagonDsp

  fun hasNnapi(): Boolean = detectCapabilities().hasNnapi

  fun hasNpu(): Boolean = detectCapabilities().hasNpu

  fun isSnapdragon865Plus(): Boolean = detectCapabilities().isSnapdragon865Plus

  fun getRecommendedDelegate(): DelegateType = detectCapabilities().recommendedDelegate

  fun getGpuVendor(): String? = detectCapabilities().gpuVendor

  fun getHexagonVersion(): String? = detectCapabilities().hexagonVersion

  fun getNnapiVersion(): Int? = detectCapabilities().nnapiVersion

  fun supportsDspInt8Inference(): Boolean {
    val caps = detectCapabilities()
    return caps.hasHexagonDsp && caps.isSnapdragon865Plus
  }

  fun getCapabilitySummary(): String {
    val caps = detectCapabilities()
    return buildString {
      append("GPU: ")
      append(if (caps.hasGpu) caps.gpuVendor ?: "Available" else "Not available")
      append("\n")

      append("Hexagon DSP: ")
      append(if (caps.hasHexagonDsp) caps.hexagonVersion ?: "Available" else "Not available")
      append("\n")

      append("NNAPI: ")
      append(if (caps.hasNnapi) "API ${caps.nnapiVersion}" else "Not available")
      append("\n")

      append("NPU: ")
      append(if (caps.hasNpu) "Available" else "Not available")
      append("\n")

      append("Recommended: ")
      append(caps.recommendedDelegate.name)
    }
  }
}
