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
package me.fleey.futon.data.localmodel.validation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed class GgufValidationResult {
  data class Valid(val metadata: GgufMetadata) : GgufValidationResult()

  data class Invalid(val error: GgufValidationError) : GgufValidationResult()
}

sealed class GgufValidationError {
  data class FileNotFound(val path: String) : GgufValidationError() {
    override fun toString(): String = "File not found: $path"
  }

  data class FileNotReadable(val path: String, val reason: String) : GgufValidationError() {
    override fun toString(): String = "Cannot read file: $reason"
  }

  data class FileTooSmall(val actualSize: Long, val minimumSize: Long) : GgufValidationError() {
    override fun toString(): String =
      "File too small: $actualSize bytes (minimum: $minimumSize bytes)"
  }

  data class InvalidMagicNumber(val actual: Int, val expected: Int) : GgufValidationError() {
    override fun toString(): String =
      "Invalid magic number: 0x${actual.toString(16).uppercase()} (expected: 0x${
        expected.toString(
          16,
        ).uppercase()
      })"
  }

  data class UnsupportedVersion(val version: Int, val minVersion: Int, val maxVersion: Int) :
    GgufValidationError() {
    override fun toString(): String =
      "Unsupported GGUF version: $version (supported: $minVersion-$maxVersion)"
  }

  data class HeaderReadError(val reason: String) : GgufValidationError() {
    override fun toString(): String = "Error reading header: $reason"
  }

  data class IoError(val reason: String) : GgufValidationError() {
    override fun toString(): String = "I/O error: $reason"
  }
}

/**
 * Metadata extracted from a GGUF file header.
 *
 * Contains basic information about the model stored in the file.
 */
@Serializable
data class GgufMetadata(
  /** GGUF format version (2 or 3) */
  val version: Int,

  /** Number of tensors in the model */
  @SerialName("tensor_count")
  val tensorCount: Long,

  /** Number of key-value metadata entries */
  @SerialName("metadata_kv_count")
  val metadataKvCount: Long,

  /** Model architecture (e.g., "llama", "qwen2vl", "minicpmv") */
  val architecture: String? = null,

  @SerialName("model_name")
  val modelName: String? = null,

  @SerialName("quantization_type")
  val quantizationType: String? = null,

  /** Context length from metadata */
  @SerialName("context_length")
  val contextLength: Int? = null,

  @SerialName("is_vision_model")
  val isVisionModel: Boolean = false,

  @SerialName("is_mmproj")
  val isMmproj: Boolean = false,

  @SerialName("file_size")
  val fileSize: Long = 0,
) {
  companion object {
    val VLM_ARCHITECTURES = setOf(
      "qwen2vl",
      "minicpmv",
      "llava",
      "bakllava",
      "moondream",
      "nanollava",
      "obsidian",
      "llama-vision",
      "mllama",
    )

    val MMPROJ_ARCHITECTURES = setOf(
      "clip",
      "mmproj",
    )
  }
}
