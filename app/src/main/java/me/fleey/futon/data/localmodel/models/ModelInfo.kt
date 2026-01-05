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
 * Information about an available local AI model.
 *
 * quantization options, and RAM requirements.
 */
@Serializable
data class ModelInfo(
  val id: String,

  val name: String,

  val description: String,

  /** Model provider/creator name */
  val provider: String,

  /** Hugging Face repository path (e.g., "owner/repo-name") */
  @SerialName("hugging_face_repo")
  val huggingFaceRepo: String,

  /** Whether this is a vision-language model requiring mmproj */
  @SerialName("is_vision_language_model")
  val isVisionLanguageModel: Boolean,

  /** Available quantization options for this model */
  val quantizations: List<QuantizationInfo>,

  /** Popularity score for sorting (higher is more popular) */
  val popularity: Int = 0,

  val tags: List<String> = emptyList(),
)

/**
 * Information about a specific quantization variant of a model.
 */
@Serializable
data class QuantizationInfo(
  /** Quantization type (INT4, INT8, FP16) */
  val type: QuantizationType,

  /** Main model GGUF filename */
  @SerialName("main_model_file")
  val mainModelFile: String,

  /** Main model file size in bytes */
  @SerialName("main_model_size")
  val mainModelSize: Long,

  /** Multimodal projector filename (null for text-only models) */
  @SerialName("mmproj_file")
  val mmprojFile: String? = null,

  /** Multimodal projector file size in bytes (null for text-only models) */
  @SerialName("mmproj_size")
  val mmprojSize: Long? = null,

  @SerialName("min_ram_mb")
  val minRamMb: Int,
) {
  /**
   * Total download size in bytes (main model + mmproj if present).
   */
  val totalSize: Long
    get() = mainModelSize + (mmprojSize ?: 0L)

  val totalSizeFormatted: String
    get() = formatBytes(totalSize)

  val minRamFormatted: String
    get() = "${minRamMb / 1024.0}GB"

  companion object {
    private fun formatBytes(bytes: Long): String {
      return when {
        bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
        else -> "${bytes}B"
      }
    }
  }
}

/**
 * Represents a model that has been downloaded to the device.
 */
@Serializable
data class DownloadedModel(
  @SerialName("model_id")
  val modelId: String,

  val quantization: QuantizationType,

  @SerialName("main_model_path")
  val mainModelPath: String,

  @SerialName("mmproj_path")
  val mmprojPath: String? = null,

  @SerialName("downloaded_at")
  val downloadedAt: Long,

  @SerialName("size_bytes")
  val sizeBytes: Long,
) {
  val sizeFormatted: String
    get() = when {
      sizeBytes >= 1_000_000_000 -> "%.1fGB".format(sizeBytes / 1_000_000_000.0)
      sizeBytes >= 1_000_000 -> "%.1fMB".format(sizeBytes / 1_000_000.0)
      else -> "%.1fKB".format(sizeBytes / 1_000.0)
    }
}

@Serializable
enum class ModelStatus {
  @SerialName("available")
  AVAILABLE,

  @SerialName("downloading")
  DOWNLOADING,

  @SerialName("downloaded")
  DOWNLOADED,

  @SerialName("active")
  ACTIVE
}
