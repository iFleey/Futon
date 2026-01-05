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
 * Hardware delegate types for ML inference acceleration.
 *
 * Ordered by typical performance (best to worst for supported hardware):
 * HEXAGON_DSP > GPU > NNAPI > XNNPACK > NONE
 */
enum class DelegateType(val value: Int) {
  NONE(0),

  XNNPACK(1),

  NNAPI(2),

  /** GPU delegate via OpenGL/OpenCL */
  GPU(3),

  HEXAGON_DSP(4);

  val displayName: String
    get() = when (this) {
      NONE -> "None"
      XNNPACK -> "XNNPACK (CPU)"
      NNAPI -> "NNAPI"
      GPU -> "GPU"
      HEXAGON_DSP -> "Hexagon DSP"
    }

  val isHardwareAccelerated: Boolean
    get() = this != NONE && this != XNNPACK

  companion object {
    fun fromInt(value: Int): DelegateType = entries.find { it.value == value } ?: NONE
  }
}
