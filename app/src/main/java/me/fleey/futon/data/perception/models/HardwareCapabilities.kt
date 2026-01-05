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

/**
 * Hardware capabilities for the perception system.
 *
 * Describes available hardware accelerators for ML inference.
 */
data class HardwareCapabilities(
  val hasGpu: Boolean,
  val gpuVendor: String?,
  val gpuRenderer: String?,
  val hasNnapi: Boolean,
  val nnapiVersion: Int?,
  val hasHexagonDsp: Boolean,
  val hexagonVersion: String?,
  val hasNpu: Boolean,
  val recommendedDelegate: DelegateType,
  val isSnapdragon865Plus: Boolean,
) {
  companion object {
    val UNKNOWN = HardwareCapabilities(
      hasGpu = false,
      gpuVendor = null,
      gpuRenderer = null,
      hasNnapi = false,
      nnapiVersion = null,
      hasHexagonDsp = false,
      hexagonVersion = null,
      hasNpu = false,
      recommendedDelegate = DelegateType.XNNPACK,
      isSnapdragon865Plus = false,
    )
  }
}
