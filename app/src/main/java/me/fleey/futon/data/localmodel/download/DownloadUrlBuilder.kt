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

import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo

/**
 * Utility for building download URLs for model files.
 *
 * Constructs URLs in the Hugging Face format:
 * `{baseUrl}/{repo}/resolve/main/{filename}`
 * */
object DownloadUrlBuilder {

  /**
   * Build download URLs for all files of a model quantization.
   *
   * @param modelInfo Model information containing repo path
   * @param quantization Quantization variant to download
   * @param source Download source (Hugging Face or HF-Mirror)
   * @return List of [ModelFile] with constructed URLs
   */
  fun buildModelFiles(
    modelInfo: ModelInfo,
    quantization: QuantizationInfo,
    source: DownloadSource,
  ): List<ModelFile> {
    val files = mutableListOf<ModelFile>()

    // Main model file (required)
    files.add(
      ModelFile(
        filename = quantization.mainModelFile,
        url = source.buildDownloadUrl(modelInfo.huggingFaceRepo, quantization.mainModelFile),
        sizeBytes = quantization.mainModelSize,
        isRequired = true,
      ),
    )

    // mmproj file (optional, for VLM models)
    if (quantization.mmprojFile != null && quantization.mmprojSize != null) {
      files.add(
        ModelFile(
          filename = quantization.mmprojFile,
          url = source.buildDownloadUrl(modelInfo.huggingFaceRepo, quantization.mmprojFile),
          sizeBytes = quantization.mmprojSize,
          isRequired = modelInfo.isVisionLanguageModel,
        ),
      )
    }

    return files
  }

  /**
   * Build a single download URL for a file.
   *
   * @param repo Hugging Face repository path (e.g., "owner/repo-name")
   * @param filename Name of the file to download
   * @param source Download source
   * @return Full download URL
   */
  fun buildUrl(repo: String, filename: String, source: DownloadSource): String {
    return source.buildDownloadUrl(repo, filename)
  }

  /**
   * Extract the domain from a download source.
   *
   * @param source Download source
   * @return Domain name (e.g., "huggingface.co" or "hf-mirror.com")
   */
  fun getDomain(source: DownloadSource): String {
    return source.baseUrl.removePrefix("https://").removePrefix("http://")
  }

  /**
   * Check if a URL is using the HF-Mirror source.
   *
   * @param url URL to check
   * @return true if the URL uses hf-mirror.com
   */
  fun isHfMirrorUrl(url: String): Boolean {
    return url.contains("hf-mirror.com")
  }

  /**
   * Convert a URL from one source to another.
   *
   * @param url Original URL
   * @param targetSource Target download source
   * @return URL with the target source's domain
   */
  fun convertUrl(url: String, targetSource: DownloadSource): String {
    val huggingFaceDomain = DownloadSource.HUGGING_FACE.baseUrl
    val hfMirrorDomain = DownloadSource.HF_MIRROR.baseUrl

    return when (targetSource) {
      DownloadSource.HUGGING_FACE -> url.replace(hfMirrorDomain, huggingFaceDomain)
      DownloadSource.HF_MIRROR -> url.replace(huggingFaceDomain, hfMirrorDomain)
    }
  }
}
