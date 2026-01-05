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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
  @SerialName("tflite")
  TFLITE,

  @SerialName("dictionary")
  DICTIONARY
}

/**
 * Quantization type for TFLite models.
 */
@Serializable
enum class TFLiteQuantizationType {
  @SerialName("FLOAT32")
  FLOAT32,

  @SerialName("FLOAT16")
  FLOAT16,

  @SerialName("INT8")
  INT8,

  @SerialName("UINT8")
  UINT8
}

/**
 * Metadata for a deployable model file.
 */
@Serializable
data class ModelMetadata(
  val name: String,
  val type: ModelType,
  val assetPath: String,
  val targetPath: String,
  val sha256: String,
  val sizeBytes: Long,
  val required: Boolean = true,
  val description: String? = null,
  val inputShape: List<Int>? = null,
  val outputShape: List<Int>? = null,
  val quantization: TFLiteQuantizationType? = null,
)
