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

/**
 * Validates model metadata from remote API responses.
 */
interface ModelMetadataValidator {

  fun validateModel(model: RemoteModelInfo): ValidationResult

  fun validateQuantization(quantization: RemoteQuantizationInfo): ValidationResult

  fun validateUrl(url: String): ValidationResult

  companion object {
    const val MIN_FILE_SIZE_BYTES: Long = 100L * 1024L * 1024L

    const val MAX_FILE_SIZE_BYTES: Long = 50L * 1024L * 1024L * 1024L

    const val REQUIRED_PROTOCOL = "https://"
  }
}
