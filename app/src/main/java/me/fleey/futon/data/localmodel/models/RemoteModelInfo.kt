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
package me.fleey.futon.data.localmodel.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Model information received from the remote catalog API.
 */
@Serializable
data class RemoteModelInfo(
  val id: String,

  val name: String,

  val description: String,

  val provider: String,

  @SerialName("hugging_face_repo")
  val huggingFaceRepo: String,

  @SerialName("is_vision_language_model")
  val isVisionLanguageModel: Boolean,

  val quantizations: List<RemoteQuantizationInfo>,

  val popularity: Int = 0,

  val tags: List<String> = emptyList(),
) {
  fun toModelInfo(): ModelInfo = ModelInfo(
    id = id,
    name = name,
    description = description,
    provider = provider,
    huggingFaceRepo = huggingFaceRepo,
    isVisionLanguageModel = isVisionLanguageModel,
    quantizations = quantizations.map { it.toQuantizationInfo() },
  )
}


@Serializable
data class RemoteQuantizationInfo(
  val type: String,

  @SerialName("main_model_file")
  val mainModelFile: String,

  @SerialName("main_model_size")
  val mainModelSize: Long,

  @SerialName("mmproj_file")
  val mmprojFile: String? = null,

  @SerialName("mmproj_size")
  val mmprojSize: Long? = null,

  @SerialName("min_ram_mb")
  val minRamMb: Int,
) {
  /**
   * Converts this remote quantization info to the internal QuantizationInfo format.
   */
  fun toQuantizationInfo(): QuantizationInfo = QuantizationInfo(
    type = parseQuantizationType(type),
    mainModelFile = mainModelFile,
    mainModelSize = mainModelSize,
    mmprojFile = mmprojFile,
    mmprojSize = mmprojSize,
    minRamMb = minRamMb,
  )

  companion object {
    /**
     * Parses a string quantization type to the enum.
     * Defaults to INT8 if the type is not recognized.
     */
    fun parseQuantizationType(type: String): QuantizationType = when (type.lowercase()) {
      "int4", "q4_0", "q4_k_m", "q4_k_s" -> QuantizationType.INT4
      "int8", "q8_0" -> QuantizationType.INT8
      "fp16", "f16" -> QuantizationType.FP16
      else -> QuantizationType.INT8
    }
  }
}
