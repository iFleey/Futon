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
package me.fleey.futon.data.localmodel.storage

import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.localmodel.validation.GgufValidationResult

/**
 * Handles importing local GGUF model files into the app's model directory.
 */
interface ModelImporter {

  /**
   * Import a model from local storage.
   *
   * @param modelPath Absolute path to the main model GGUF file
   * @param mmprojPath Absolute path to the mmproj file (required for VLM, null for text-only)
   * @return Flow emitting import progress and final result
   */
  fun importModel(modelPath: String, mmprojPath: String?): Flow<ImportProgress>

  /**
   * Validate a GGUF file without importing it.
   *
   * @param path Absolute path to the GGUF file
   * @return Validation result with metadata if valid, error otherwise
   */
  suspend fun validateFile(path: String): GgufValidationResult

  /**
   * Check if a model file is a Vision-Language Model requiring mmproj.
   *
   * @param path Absolute path to the GGUF file
   * @return true if the model is a VLM requiring mmproj
   */
  suspend fun requiresMmproj(path: String): Boolean

  /**
   * Get the estimated import size for a model.
   *
   * @param modelPath Absolute path to the main model file
   * @param mmprojPath Absolute path to the mmproj file (optional)
   * @return Total size in bytes that will be copied
   */
  suspend fun getImportSize(modelPath: String, mmprojPath: String?): Long

  /**
   * Check if there is sufficient storage for the import.
   *
   * @param modelPath Absolute path to the main model file
   * @param mmprojPath Absolute path to the mmproj file (optional)
   * @return true if sufficient storage available
   */
  suspend fun hasStorageForImport(modelPath: String, mmprojPath: String?): Boolean
}
