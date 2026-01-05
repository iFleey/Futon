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
package me.fleey.futon.data.localmodel.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Download source endpoints for model files.
 */
@Serializable
enum class DownloadSource(val baseUrl: String) {
  /**
   * Official Hugging Face repository.
   * Primary source for model downloads.
   */
  @SerialName("hugging_face")
  HUGGING_FACE("https://huggingface.co"),

  /**
   * HF-Mirror for users in China.
   * Alternative endpoint when Hugging Face is inaccessible.
   */
  @SerialName("hf_mirror")
  HF_MIRROR("https://hf-mirror.com");

  /**
   * Build the full download URL for a model file.
   *
   * @param repo Hugging Face repository path (e.g., "owner/repo-name")
   * @param filename Name of the file to download
   * @return Full download URL
   */
  fun buildDownloadUrl(repo: String, filename: String): String {
    return "$baseUrl/$repo/resolve/main/$filename"
  }
}