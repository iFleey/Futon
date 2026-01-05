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
package me.fleey.futon.data.localmodel.registry

/**
 * Model registry fetching models directly from HuggingFace API.
 * Supports search, auto-detect quantizations, and hf-mirror.com for China users.
 */
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.source.HuggingFaceApiClient
import org.koin.core.annotation.Single

@Single(binds = [RemoteModelRegistry::class])
class HuggingFaceModelRegistry(
  private val hfClient: HuggingFaceApiClient,
  private val fallbackRegistry: FallbackModelRegistry = FallbackModelRegistry,
) : RemoteModelRegistry {

  companion object {
    private const val TAG = "HuggingFaceRegistry"

    private val RECOMMENDED_REPOS = listOf(
      "bartowski/Qwen2.5-3B-Instruct-GGUF",
      "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
      "bartowski/SmolLM2-1.7B-Instruct-GGUF",
      "bartowski/Qwen2.5-7B-Instruct-GGUF",
      "bartowski/gemma-2-2b-it-GGUF",
    )

    private val QUANT_PATTERNS = mapOf(
      "Q4_K_M" to Pair(QuantizationType.INT4, 0.5f),
      "Q4_K_S" to Pair(QuantizationType.INT4, 0.45f),
      "Q4_0" to Pair(QuantizationType.INT4, 0.45f),
      "Q5_K_M" to Pair(QuantizationType.INT4, 0.55f),
      "Q8_0" to Pair(QuantizationType.INT8, 0.85f),
      "F16" to Pair(QuantizationType.FP16, 1.0f),
      "f16" to Pair(QuantizationType.FP16, 1.0f),
    )
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val loadMutex = Mutex()

  private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
  private val _searchQuery = MutableStateFlow("")

  private var useMirror = false
  private var catalogLoaded = false

  init {
    scope.launch {
      loadRecommendedModels()
    }
  }

  /**
   * Load recommended models for GUI automation.
   */
  private suspend fun loadRecommendedModels() {
    loadMutex.withLock {
      if (catalogLoaded) return@withLock

      Log.d(TAG, "Loading recommended models...")

      val models = mutableListOf<ModelInfo>()

      // First, add fallback models (always available)
      models.addAll(fallbackRegistry.getFallbackModels())

      // Try to fetch details for recommended repos
      for (repoId in RECOMMENDED_REPOS) {
        try {
          val modelInfo = fetchModelWithQuantizations(repoId)
          if (modelInfo != null && modelInfo.quantizations.isNotEmpty()) {
            // Replace fallback if we got better data
            val existingIndex = models.indexOfFirst {
              it.huggingFaceRepo == repoId
            }
            if (existingIndex >= 0) {
              models[existingIndex] = modelInfo
            } else {
              models.add(modelInfo)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to fetch $repoId: ${e.message}")
        }
      }

      _models.value = models

      catalogLoaded = true
      Log.d(TAG, "Loaded ${models.size} models")
    }
  }

  /**
   * Fetch a model and its quantization options from HuggingFace.
   */
  private suspend fun fetchModelWithQuantizations(repoId: String): ModelInfo? {
    // Get file list to find GGUF files
    val filesResult = hfClient.listModelFiles(repoId, useMirror)
    val files = filesResult.getOrNull() ?: return null

    val ggufFiles = files.filter { it.path.endsWith(".gguf") }
    if (ggufFiles.isEmpty()) return null

    // Parse quantizations from file names
    val quantizations = ggufFiles.mapNotNull { file ->
      parseQuantization(file.path, file.size ?: 0)
    }.distinctBy { it.type }

    if (quantizations.isEmpty()) return null

    val author = repoId.substringBefore("/")
    val modelName = repoId.substringAfter("/")
      .replace("-GGUF", "")
      .replace("-gguf", "")

    // Determine if VLM by checking for mmproj files
    val hasMMProj = files.any { it.path.contains("mmproj") }

    return ModelInfo(
      id = repoId.replace("/", "-").lowercase(),
      name = modelName,
      description = buildDescription(modelName, author, quantizations),
      provider = author,
      huggingFaceRepo = repoId,
      isVisionLanguageModel = hasMMProj,
      quantizations = quantizations.sortedBy { it.type.ordinal },
    )
  }

  /**
   * Parse quantization info from GGUF filename.
   */
  private fun parseQuantization(filename: String, size: Long): QuantizationInfo? {
    for ((pattern, typeAndRatio) in QUANT_PATTERNS) {
      if (filename.contains(pattern, ignoreCase = true)) {
        val (type, _) = typeAndRatio

        // Estimate RAM requirement (model size + ~20% overhead)
        val ramMb = ((size / 1_000_000) * 1.2).toInt().coerceAtLeast(2048)

        return QuantizationInfo(
          type = type,
          mainModelFile = filename,
          mainModelSize = size,
          mmprojFile = null,
          mmprojSize = null,
          minRamMb = ramMb,
        )
      }
    }
    return null
  }

  /**
   * Build a description for the model.
   */
  private fun buildDescription(
    name: String,
    author: String,
    quants: List<QuantizationInfo>,
  ): String {
    val sizeRange = if (quants.isNotEmpty()) {
      val minSize = quants.minOf { it.mainModelSize } / 1_000_000_000.0
      val maxSize = quants.maxOf { it.mainModelSize } / 1_000_000_000.0
      "%.1f-%.1fGB".format(minSize, maxSize)
    } else "Unknown size"

    return "$name from $author. Size: $sizeRange. " +
      "Quantizations: ${quants.joinToString { it.type.name }}"
  }

  override suspend fun refreshCatalog(): Result<List<ModelInfo>> {
    catalogLoaded = false
    loadRecommendedModels()
    return Result.success(_models.value)
  }

  override fun searchModels(query: String): Flow<List<ModelInfo>> {
    _searchQuery.value = query

    // If query is not empty, search HuggingFace
    if (query.isNotBlank() && query.length >= 3) {
      scope.launch {
        searchHuggingFace(query)
      }
    }

    return _models.map { models ->
      if (query.isBlank()) {
        models
      } else {
        models.filter { model ->
          model.name.contains(query, ignoreCase = true) ||
            model.description.contains(query, ignoreCase = true) ||
            model.huggingFaceRepo.contains(query, ignoreCase = true)
        }
      }
    }
  }

  /**
   * Search HuggingFace for GGUF models.
   */
  private suspend fun searchHuggingFace(query: String) {
    Log.d(TAG, "Searching HuggingFace for: $query")

    val result = hfClient.searchGgufModels(query, useMirror)
    result.onSuccess { searchResults ->
      // Merge with existing models
      val currentModels = _models.value.toMutableList()

      for (model in searchResults) {
        if (currentModels.none { it.huggingFaceRepo == model.huggingFaceRepo }) {
          // Fetch quantizations for new model
          val fullModel = fetchModelWithQuantizations(model.huggingFaceRepo)
          if (fullModel != null) {
            currentModels.add(fullModel)
          }
        }
      }

      _models.value = currentModels
    }
  }

  override fun filterModels(filter: ModelFilter): Flow<List<ModelInfo>> {
    return _models.map { models ->
      var filtered = models

      // Filter by type
      filter.isVisionLanguageModel?.let { isVlm ->
        filtered = filtered.filter { it.isVisionLanguageModel == isVlm }
      }

      // Filter by RAM
      filter.maxRamMb?.let { maxRam ->
        filtered = filtered.filter { model ->
          model.quantizations.any { it.minRamMb <= maxRam }
        }
      }

      // Sort
      filtered = when (filter.sortBy) {
        SortOption.NAME -> filtered.sortedBy { it.name.lowercase() }
        SortOption.SIZE -> filtered.sortedBy {
          it.quantizations.minOfOrNull { q -> q.mainModelSize } ?: Long.MAX_VALUE
        }

        SortOption.POPULARITY -> filtered // Already sorted by downloads from API
      }

      filtered
    }
  }

  override fun getApiEndpoint(): String =
    if (useMirror) HuggingFaceApiClient.HF_MIRROR_API_BASE
    else HuggingFaceApiClient.HF_API_BASE

  override fun getModelInfo(modelId: String): ModelInfo? {
    return _models.value.find { it.id == modelId }
  }

  override suspend fun setApiEndpoint(url: String): Result<Unit> {
    // Check if switching to mirror
    useMirror = url.contains("hf-mirror.com")
    catalogLoaded = false
    loadRecommendedModels()
    return Result.success(Unit)
  }

  /**
   * Set whether to use hf-mirror.com (for China users).
   */
  fun setUseMirror(use: Boolean) {
    if (useMirror != use) {
      useMirror = use
      catalogLoaded = false
      scope.launch {
        loadRecommendedModels()
      }
    }
  }
}
