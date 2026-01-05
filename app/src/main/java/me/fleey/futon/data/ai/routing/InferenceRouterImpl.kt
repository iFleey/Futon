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
package me.fleey.futon.data.ai.routing

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIErrorType
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ContentPart
import me.fleey.futon.data.ai.models.ImageUrlData
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.repository.InferenceStatsRepository
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.domain.localmodel.LocalInferenceEngine
import org.koin.core.annotation.Single
import java.io.ByteArrayOutputStream

private const val TAG = "InferenceRouter"

@Single(binds = [InferenceRouter::class])
class InferenceRouterImpl(
  private val providerRepository: ProviderRepository,
  private val settingsRepository: SettingsRepository,
  private val adapters: Map<ApiProtocol, ProviderAdapter>,
  private val localInferenceEngine: LocalInferenceEngine?,
  private val availabilityTracker: SourceAvailabilityTracker,
  private val statsRepository: InferenceStatsRepository,
  private val promptRepository: PromptRepository?,
  private val json: Json,
) : InferenceRouter {

  private val _lastUsedSource = MutableStateFlow<InferenceSource?>(null)
  override val lastUsedSource: StateFlow<InferenceSource?> = _lastUsedSource.asStateFlow()

  override val sourceAvailability: StateFlow<Map<InferenceSource, SourceAvailability>> =
    availabilityTracker.sourceAvailability

  private val _routingConfig = MutableStateFlow(RoutingConfig())
  override val routingConfig: StateFlow<RoutingConfig> = _routingConfig.asStateFlow()

  private val scope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
  )

  init {
    scope.launch {
      initializeRoutingConfig()
    }
  }

  private suspend fun initializeRoutingConfig() {
    val sources = buildSourceList()
    _routingConfig.value = RoutingConfig(
      priority = sources,
      enabledSources = sources.associateWith { true },
    )
  }

  private suspend fun buildSourceList(): List<InferenceSource> {
    val sources = mutableListOf<InferenceSource>()

    val providers = providerRepository.getProviders()
    for (provider in providers.filter { it.enabled && it.isConfigured() }) {
      val selectedModelId = provider.selectedModelId
      if (selectedModelId != null) {
        sources.add(
          InferenceSource.CloudProvider(
            providerId = provider.id,
            providerName = provider.name,
            protocol = provider.protocol,
            modelId = selectedModelId,
          ),
        )
      }
    }

    // Add local model at the end
    if (localInferenceEngine != null) {
      sources.add(InferenceSource.LocalModel)
    }

    return sources
  }

  override suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
  ): AIResponse = analyzeScreenshot(
    screenshot, taskDescription, uiContext, conversationHistory, appContext, null,
  )

  override suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
    sourceOverride: InferenceSource?,
  ): AIResponse {
    if (screenshot.isNullOrBlank() && uiContext.isNullOrBlank()) {
      throw AIClientException(AIErrorType.NoScreenshotOrUiContext)
    }

    val sourcesToTry = sourceOverride?.let { listOf(it) } ?: selectSourcesForRequest()
    if (sourcesToTry.isEmpty()) {
      throw AIClientException(AIErrorType.UnknownError("NoSources", "No available inference sources"))
    }

    val errors = mutableListOf<Pair<InferenceSource, Exception>>()

    for (source in sourcesToTry) {
      try {
        Log.d(TAG, "Trying source: ${source.id}")
        val startTime = System.currentTimeMillis()

        val response = executeRequest(source, screenshot, taskDescription, uiContext, conversationHistory, appContext)
        val latencyMs = System.currentTimeMillis() - startTime

        availabilityTracker.markAvailable(source)
        _lastUsedSource.value = source
        Log.d(TAG, "Success with ${source.id} in ${latencyMs}ms")
        return response
      } catch (e: Exception) {
        Log.w(TAG, "Failed with ${source.id}: ${e.message}")
        errors.add(source to e)
        availabilityTracker.markUnavailable(source, e.message ?: "Unknown error")
      }
    }

    throw aggregateErrors(errors)
  }

  override suspend fun analyzeBuffer(
    buffer: HardwareBuffer,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
    sourceOverride: InferenceSource?,
  ): AIResponse {
    val sourcesToTry = sourceOverride?.let { listOf(it) } ?: selectSourcesForRequest()
    if (sourcesToTry.isEmpty()) {
      throw AIClientException(AIErrorType.UnknownError("NoSources", "No available inference sources"))
    }

    val errors = mutableListOf<Pair<InferenceSource, Exception>>()

    for (source in sourcesToTry) {
      try {
        val startTime = System.currentTimeMillis()
        val response = when (source) {
          is InferenceSource.LocalModel -> {
            localInferenceEngine?.analyzeBuffer(buffer, taskDescription, uiContext)
              ?: throw AIClientException(AIErrorType.LocalEngineNotConfigured)
          }

          is InferenceSource.CloudProvider -> {
            val screenshot = convertBufferToBase64(buffer)
            executeCloudRequest(source, screenshot, taskDescription, uiContext, conversationHistory, appContext)
          }
        }
        val latencyMs = System.currentTimeMillis() - startTime
        availabilityTracker.markAvailable(source)
        _lastUsedSource.value = source
        Log.d(TAG, "Buffer analysis success with ${source.id} in ${latencyMs}ms")
        return response
      } catch (e: Exception) {
        errors.add(source to e)
        availabilityTracker.markUnavailable(source, e.message ?: "Unknown error")
      }
    }

    throw aggregateErrors(errors)
  }

  override suspend fun updateConfig(config: RoutingConfig) {
    _routingConfig.value = config
  }

  override suspend fun updatePriority(sources: List<InferenceSource>) {
    _routingConfig.update { it.copy(priority = sources) }
  }

  override suspend fun updateStrategy(strategy: RoutingStrategy) {
    _routingConfig.update { it.copy(strategy = strategy) }
  }

  override suspend fun setSourceEnabled(source: InferenceSource, enabled: Boolean) {
    _routingConfig.update { config ->
      config.copy(enabledSources = config.enabledSources + (source to enabled))
    }

    if (source == InferenceSource.LocalModel && enabled) {
      Log.d(TAG, "Local model enabled, auto-warming up")
      localInferenceEngine?.loadModel()
    }
  }

  override suspend fun warmupLocalModel() {
    localInferenceEngine?.loadModel()
  }

  override suspend fun refreshAvailability() {
    val sources = buildSourceList()
    _routingConfig.update { config ->
      val newEnabledSources = sources.associateWith { source ->
        config.enabledSources[source] ?: true
      }
      config.copy(
        priority = sources,
        enabledSources = newEnabledSources,
      )
    }

    availabilityTracker.refreshAllAvailability()
  }

  private fun selectSourcesForRequest(): List<InferenceSource> {
    val config = _routingConfig.value
    val enabledSources = config.getEnabledSourcesInOrder()
    val availableSources = availabilityTracker.filterAvailable(enabledSources)

    return when (config.strategy) {
      is RoutingStrategy.PriorityOrder -> availableSources
      is RoutingStrategy.CostOptimized -> sortByCost(availableSources)
      is RoutingStrategy.LatencyOptimized -> availableSources // TODO: implement with stats
      is RoutingStrategy.ReliabilityOptimized -> availableSources // TODO: implement with stats
    }
  }

  private fun sortByCost(sources: List<InferenceSource>): List<InferenceSource> {
    return sources.sortedBy { source ->
      when (source) {
        is InferenceSource.LocalModel -> 0
        is InferenceSource.CloudProvider -> 1
      }
    }
  }

  private suspend fun executeRequest(
    source: InferenceSource,
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
  ): AIResponse = when (source) {
    is InferenceSource.LocalModel -> {
      localInferenceEngine?.analyzeScreenshot(screenshot, taskDescription, uiContext, conversationHistory)
        ?: throw AIClientException(AIErrorType.LocalEngineNotConfigured)
    }

    is InferenceSource.CloudProvider -> {
      executeCloudRequest(source, screenshot, taskDescription, uiContext, conversationHistory, appContext)
    }
  }

  private suspend fun executeCloudRequest(
    source: InferenceSource.CloudProvider,
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
  ): AIResponse {
    val provider = providerRepository.getProvider(source.providerId)
      ?: throw AIClientException(AIErrorType.ProviderNotFound(source.providerId))

    val adapter = adapters[provider.protocol]
      ?: throw AIClientException(AIErrorType.UnsupportedProtocol(provider.protocol.name))

    // Use provider's selected model, falling back to source.modelId for backward compatibility
    val modelId = provider.selectedModelId ?: source.modelId

    val settings = settingsRepository.getSettings()
    val systemPrompt = getActiveSystemPrompt()

    val messages = buildMessages(screenshot, taskDescription, uiContext, appContext, conversationHistory, systemPrompt)

    val response = adapter.sendRequest(
      provider = provider,
      modelId = modelId,
      messages = messages,
      maxTokens = settings.maxTokens,
      timeoutMs = settings.requestTimeoutMs,
    )

    val model = providerRepository.getModels(source.providerId).find { it.modelId == modelId }
    if (model != null) {
      val cost = model.calculateCost(response.inputTokens, response.outputTokens)
      statsRepository.recordInference(
        modelId = modelId,
        providerId = source.providerId,
        inputTokens = response.inputTokens,
        outputTokens = response.outputTokens,
        cost = cost,
        latencyMs = 0, // TODO: track actual latency
      )
    }

    return parseAIResponse(response.content)
  }

  private fun parseAIResponse(content: String): AIResponse {
    var cleaned = content.trim()
    if (cleaned.startsWith("```")) {
      val idx = cleaned.indexOf('\n')
      if (idx in 1..19) cleaned = cleaned.substring(idx + 1)
    }
    if (cleaned.endsWith("```")) cleaned = cleaned.dropLast(3)
    cleaned = cleaned.trim()

    if (!cleaned.startsWith("{")) {
      val start = cleaned.indexOf('{')
      val end = cleaned.lastIndexOf('}')
      if (start != -1 && end > start) cleaned = cleaned.substring(start, end + 1)
    }

    return json.decodeFromString<AIResponse>(cleaned)
  }

  private suspend fun getActiveSystemPrompt(): String {
    val settings = settingsRepository.getSettings()
    return promptRepository?.let {
      try {
        val promptSettings = it.getPromptSettingsOnce()
        val activeId = promptSettings.activeSystemPromptId
        promptSettings.templates.find { t -> t.id == activeId && t.isEnabled }?.content
      } catch (_: Exception) {
        null
      }
    } ?: settings.systemPrompt
  }

  private fun buildMessages(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    appContext: String?,
    history: List<Message>,
    systemPrompt: String,
  ): List<Message> {
    val systemMessage = Message(role = "system", content = listOf(ContentPart.Text(systemPrompt)))
    val userContent = buildList {
      add(ContentPart.Text("Task: $taskDescription"))
      if (!appContext.isNullOrBlank()) add(ContentPart.Text("\n\n$appContext"))
      if (!uiContext.isNullOrBlank()) add(ContentPart.Text("\n\n$uiContext"))
      if (!screenshot.isNullOrBlank()) {
        add(ContentPart.ImageUrl(ImageUrlData("data:image/jpeg;base64,$screenshot")))
      }
    }
    return listOf(systemMessage) + history + listOf(Message(role = "user", content = userContent))
  }

  private fun convertBufferToBase64(buffer: HardwareBuffer): String {
    val bitmap = Bitmap.wrapHardwareBuffer(buffer, null)
      ?: throw AIClientException("Failed to wrap HardwareBuffer")
    val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    bitmap.recycle()
    val outputStream = ByteArrayOutputStream()
    softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
    softwareBitmap.recycle()
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
  }

  private fun aggregateErrors(errors: List<Pair<InferenceSource, Exception>>): AIClientException {
    val msg = errors.joinToString("; ") { (s, e) -> "${s.displayName}: ${e.message}" }
    return AIClientException(AIErrorType.UnknownError("AllSourcesFailed", "All sources failed: $msg"))
  }
}
