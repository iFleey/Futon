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
package me.fleey.futon.data.localmodel.source

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationType

/**
 * Client for fetching GGUF models directly from HuggingFace API.
 *
 * This allows discovering models without maintaining a separate catalog.
 * Uses the public HuggingFace Hub API:
 * - https://huggingface.co/api/models?filter=gguf
 * - https://hf-mirror.com/api/models?filter=gguf (China mirror)
 */
import org.koin.core.annotation.Single

@Single
class HuggingFaceApiClient(
  private val httpClient: HttpClient,
) {
  companion object {
    private const val TAG = "HuggingFaceApi"

    // API endpoints
    const val HF_API_BASE = "https://huggingface.co/api/models"
    const val HF_MIRROR_API_BASE = "https://hf-mirror.com/api/models"

    // Common GGUF quantization patterns
    private val QUANT_PATTERNS = mapOf(
      "Q4_K_M" to QuantizationType.INT4,
      "Q4_K_S" to QuantizationType.INT4,
      "Q4_0" to QuantizationType.INT4,
      "Q5_K_M" to QuantizationType.INT4,
      "Q5_K_S" to QuantizationType.INT4,
      "Q8_0" to QuantizationType.INT8,
      "F16" to QuantizationType.FP16,
      "f16" to QuantizationType.FP16,
    )

    // Known good GGUF model providers
    private val TRUSTED_AUTHORS = listOf(
      "bartowski",
      "TheBloke",
      "lmstudio-community",
      "QuantFactory",
      "mradermacher",
    )
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Search for GGUF models on HuggingFace.
   *
   * @param query Search query (e.g., "Qwen2.5", "llama")
   * @param useMirror Use hf-mirror.com instead of huggingface.co
   * @param limit Maximum number of results
   * @return List of ModelInfo objects
   */
  suspend fun searchGgufModels(
    query: String,
    useMirror: Boolean = false,
    limit: Int = 30,
  ): Result<List<ModelInfo>> {
    return try {
      val baseUrl = if (useMirror) HF_MIRROR_API_BASE else HF_API_BASE

      Log.d(TAG, "Searching GGUF models: query=$query, mirror=$useMirror")

      val response: HttpResponse = httpClient.get(baseUrl) {
        parameter("search", query)
        parameter("filter", "gguf")
        parameter("limit", limit)
        parameter("sort", "downloads")
        parameter("direction", "-1")
      }

      if (!response.status.isSuccess()) {
        return Result.failure(Exception("API error: ${response.status}"))
      }

      val responseText: String = response.body()
      val hfModels: List<HfModelResponse> = json.decodeFromString(responseText)

      Log.d(TAG, "Found ${hfModels.size} models")

      val models = hfModels
        .filter { isQualityModel(it) }
        .mapNotNull { convertToModelInfo(it) }

      Result.success(models)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to search models", e)
      Result.failure(e)
    }
  }

  /**
   * Get details of a specific model repository.
   */
  suspend fun getModelDetails(
    repoId: String,
    useMirror: Boolean = false,
  ): Result<HfModelDetails> {
    return try {
      val baseUrl = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
      val url = "$baseUrl/api/models/$repoId"

      val response: HttpResponse = httpClient.get(url)

      if (!response.status.isSuccess()) {
        return Result.failure(Exception("API error: ${response.status}"))
      }

      val responseText: String = response.body()
      val details: HfModelDetails = json.decodeFromString(responseText)

      Result.success(details)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get model details: $repoId", e)
      Result.failure(e)
    }
  }

  /**
   * List files in a model repository to find GGUF files.
   */
  suspend fun listModelFiles(
    repoId: String,
    useMirror: Boolean = false,
  ): Result<List<HfFileInfo>> {
    return try {
      val baseUrl = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
      val url = "$baseUrl/api/models/$repoId/tree/main"

      val response: HttpResponse = httpClient.get(url)

      if (!response.status.isSuccess()) {
        return Result.failure(Exception("API error: ${response.status}"))
      }

      val responseText: String = response.body()
      val files: List<HfFileInfo> = json.decodeFromString(responseText)

      // Filter for GGUF files
      val ggufFiles = files.filter { it.path.endsWith(".gguf") }

      Result.success(ggufFiles)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to list files: $repoId", e)
      Result.failure(e)
    }
  }

  /**
   * Check if a model meets quality criteria.
   */
  private fun isQualityModel(model: HfModelResponse): Boolean {
    // Prefer models from trusted authors
    val author = model.modelId.substringBefore("/")
    if (author in TRUSTED_AUTHORS) return true

    // Require minimum downloads
    if ((model.downloads ?: 0) < 100) return false

    // Require minimum likes
    if ((model.likes ?: 0) < 5) return false

    return true
  }

  /**
   * Convert HuggingFace API response to ModelInfo.
   */
  private fun convertToModelInfo(hfModel: HfModelResponse): ModelInfo? {
    val repoId = hfModel.modelId
    val author = repoId.substringBefore("/")
    val modelName = repoId.substringAfter("/")

    // Determine if it's a VLM based on tags
    val isVlm = hfModel.tags?.any { tag ->
      tag.contains("vision", ignoreCase = true) ||
        tag.contains("vlm", ignoreCase = true) ||
        tag.contains("multimodal", ignoreCase = true)
    } ?: false

    return ModelInfo(
      id = repoId.replace("/", "-").lowercase(),
      name = modelName.replace("-GGUF", "").replace("-gguf", ""),
      description = "GGUF model from $author. Downloads: ${hfModel.downloads ?: 0}",
      provider = author,
      huggingFaceRepo = repoId,
      isVisionLanguageModel = isVlm,
      quantizations = emptyList(),
    )
  }
}

// HuggingFace API Response Models
@Serializable
data class HfModelResponse(
  @SerialName("_id") val id: String? = null,
  @SerialName("id") val modelId: String,
  @SerialName("modelId") val modelIdAlt: String? = null,
  val author: String? = null,
  val downloads: Int? = null,
  val likes: Int? = null,
  val tags: List<String>? = null,
  val pipeline_tag: String? = null,
  val lastModified: String? = null,
)

@Serializable
data class HfModelDetails(
  @SerialName("id") val modelId: String,
  val author: String? = null,
  val downloads: Int? = null,
  val likes: Int? = null,
  val tags: List<String>? = null,
  val siblings: List<HfSibling>? = null,
  val cardData: HfCardData? = null,
)

@Serializable
data class HfSibling(
  val rfilename: String,
  val size: Long? = null,
)

@Serializable
data class HfCardData(
  val license: String? = null,
  val language: List<String>? = null,
  val tags: List<String>? = null,
)

@Serializable
data class HfFileInfo(
  val type: String,
  val path: String,
  val size: Long? = null,
  val oid: String? = null,
)
