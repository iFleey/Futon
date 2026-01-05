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
package me.fleey.futon.domain.localmodel.models

data class MemoryUsage(
  val modelMemoryBytes: Long,

  val totalDeviceRamBytes: Long,

  val availableDeviceRamBytes: Long,

  val timestamp: Long = System.currentTimeMillis(),
) {
  val modelMemoryMb: Int
    get() = (modelMemoryBytes / (1024 * 1024)).toInt()

  val totalDeviceRamMb: Int
    get() = (totalDeviceRamBytes / (1024 * 1024)).toInt()

  val availableDeviceRamMb: Int
    get() = (availableDeviceRamBytes / (1024 * 1024)).toInt()

  val modelMemoryPercentage: Float
    get() = if (totalDeviceRamBytes > 0) {
      (modelMemoryBytes.toFloat() / totalDeviceRamBytes) * 100f
    } else 0f

  val availableRamPercentage: Float
    get() = if (totalDeviceRamBytes > 0) {
      (availableDeviceRamBytes.toFloat() / totalDeviceRamBytes) * 100f
    } else 0f

  val modelMemoryFormatted: String
    get() = formatBytes(modelMemoryBytes)

  val availableRamFormatted: String
    get() = formatBytes(availableDeviceRamBytes)

  val totalRamFormatted: String
    get() = formatBytes(totalDeviceRamBytes)

  companion object {
    private fun formatBytes(bytes: Long): String {
      return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
      }
    }

    /** Empty memory usage when no model is loaded */
    val EMPTY = MemoryUsage(
      modelMemoryBytes = 0,
      totalDeviceRamBytes = 0,
      availableDeviceRamBytes = 0,
    )
  }
}
