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

/**
 * Validates GGUF model files and extracts metadata.
 */
interface GgufValidator {

  /**
   * Validates a GGUF file and extracts its metadata.
   *
   * @param filePath Absolute path to the GGUF file
   * @return Validation result with metadata if valid, error otherwise
   */
  suspend fun validate(filePath: String): GgufValidationResult

  /**
   * Checks if a file has a valid GGUF magic number without full validation.
   *
   * This is a quick check that only reads the first 4 bytes.
   *
   * @param filePath Absolute path to the file
   * @return true if the file starts with GGUF magic number
   */
  suspend fun hasValidMagicNumber(filePath: String): Boolean

  suspend fun isVisionLanguageModel(filePath: String): Boolean

  companion object {
    /**
     * GGUF magic number: "GGUF" in little-endian (0x46554747)
     * G = 0x47, G = 0x47, U = 0x55, F = 0x46
     */
    const val GGUF_MAGIC_NUMBER: Int = 0x46554747

    /**
     * Minimum supported GGUF version
     */
    const val MIN_GGUF_VERSION: Int = 2

    const val MAX_GGUF_VERSION: Int = 3
  }
}
