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

import me.fleey.futon.data.localmodel.models.RemoteModelInfo
import me.fleey.futon.data.localmodel.models.RemoteQuantizationInfo
import org.koin.core.annotation.Single

/**
 * ModelMetadataValidator implementation.
 */
@Single(binds = [ModelMetadataValidator::class])
class ModelMetadataValidatorImpl : ModelMetadataValidator {

  override fun validateModel(model: RemoteModelInfo): ValidationResult {
    val errors = mutableListOf<String>()

    if (model.id.isBlank()) {
      errors.add("Model ID is required")
    }

    if (model.name.isBlank()) {
      errors.add("Model name is required")
    }

    if (model.huggingFaceRepo.isBlank()) {
      errors.add("Hugging Face repository is required")
    }

    if (model.quantizations.isEmpty()) {
      errors.add("At least one quantization option is required")
    } else {
      model.quantizations.forEachIndexed { index, quantization ->
        val quantResult = validateQuantization(quantization)
        if (quantResult is ValidationResult.Invalid) {
          quantResult.reasons.forEach { reason ->
            errors.add("Quantization ${index + 1}: $reason")
          }
        }
      }
    }

    return if (errors.isEmpty()) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errors)
    }
  }

  override fun validateQuantization(quantization: RemoteQuantizationInfo): ValidationResult {
    val errors = mutableListOf<String>()

    if (quantization.mainModelFile.isBlank()) {
      errors.add("Main model file is required")
    }

    if (quantization.mainModelSize < ModelMetadataValidator.MIN_FILE_SIZE_BYTES) {
      errors.add(
        "File size too small: ${formatFileSize(quantization.mainModelSize)} (minimum: ${
          formatFileSize(
            ModelMetadataValidator.MIN_FILE_SIZE_BYTES,
          )
        })",
      )
    }

    if (quantization.mainModelSize > ModelMetadataValidator.MAX_FILE_SIZE_BYTES) {
      errors.add(
        "File size too large: ${formatFileSize(quantization.mainModelSize)} (maximum: ${
          formatFileSize(
            ModelMetadataValidator.MAX_FILE_SIZE_BYTES,
          )
        })",
      )
    }

    if (quantization.minRamMb <= 0) {
      errors.add("Invalid RAM requirement: ${quantization.minRamMb}MB (must be positive)")
    }

    return if (errors.isEmpty()) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errors)
    }
  }

  override fun validateUrl(url: String): ValidationResult {
    return if (url.lowercase().startsWith(ModelMetadataValidator.REQUIRED_PROTOCOL)) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid("URL must use HTTPS protocol: $url")
    }
  }

  private fun formatFileSize(bytes: Long): String {
    return when {
      bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
      bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
      bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
      else -> "$bytes bytes"
    }
  }
}
