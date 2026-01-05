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
package me.fleey.futon.data.localmodel.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.data.localmodel.models.QuantizationType

/** Device hardware capabilities for local model inference. */
@Serializable
data class DeviceCapabilities(
  @SerialName("total_ram_mb")
  val totalRamMb: Int,

  @SerialName("available_ram_mb")
  val availableRamMb: Int,

  /** Processor name from device info */
  @SerialName("processor_name")
  val processorName: String,

  /** Whether the device has a Snapdragon processor */
  @SerialName("is_snapdragon")
  val isSnapdragon: Boolean,

  /** Snapdragon model number if applicable (e.g., 870) */
  @SerialName("snapdragon_model")
  val snapdragonModel: Int? = null,

  /** Whether the device supports Android NNAPI */
  @SerialName("supports_nnapi")
  val supportsNnapi: Boolean,

  /** Recommended quantization based on device capabilities */
  @SerialName("recommended_quantization")
  val recommendedQuantization: QuantizationType,

  /** Warning messages about device limitations */
  val warnings: List<String> = emptyList(),
) {
  val totalRamFormatted: String
    get() = "%.1fGB".format(totalRamMb / 1024.0)

  val availableRamFormatted: String
    get() = "%.1fGB".format(availableRamMb / 1024.0)

  /**
   * Whether the device meets minimum requirements for local models.
   * Minimum: 4GB RAM
   */
  val meetsMinimumRequirements: Boolean
    get() = totalRamMb >= MIN_RAM_MB

  /**
   * Whether the device is recommended for local model usage.
   * Recommended: 6GB+ RAM
   */
  val isRecommendedDevice: Boolean
    get() = totalRamMb >= RECOMMENDED_RAM_MB

  companion object {
    /** Minimum RAM in MB for local model support */
    const val MIN_RAM_MB = 4 * 1024 // 4GB

    /** Recommended RAM in MB for good performance */
    const val RECOMMENDED_RAM_MB = 6 * 1024

    /** RAM threshold for INT8 recommendation */
    const val INT8_THRESHOLD_MB = 6 * 1024
  }
}
