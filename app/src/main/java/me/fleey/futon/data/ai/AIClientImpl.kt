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
package me.fleey.futon.data.ai

import android.util.Log
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ContentPart
import me.fleey.futon.data.ai.models.ImageUrlData
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.repository.InferenceStatsRepository
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.prompt.PromptVariableResolver
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.data.settings.models.AISettings
import me.fleey.futon.domain.localmodel.LocalInferenceEngine
import me.fleey.futon.domain.som.SomPromptBuilder
import org.koin.core.annotation.Single

private const val TAG = "AIClientImpl"

/**
 * AI client implementation using the new Provider/Model system.
 */
@Single(binds = [AIClient::class])
class AIClientImpl(
  private val providerRepository: ProviderRepository,
  private val settingsRepository: SettingsRepository,
  private val adapters: Map<ApiProtocol, ProviderAdapter>,
  private val localInferenceEngine: LocalInferenceEngine?,
  private val promptRepository: PromptRepository?,
  private val variableResolver: PromptVariableResolver?,
  private val somPromptBuilder: SomPromptBuilder?,
  private val statsRepository: InferenceStatsRepository,
  private val json: Json,
) : AIClient {

  override suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
  ): AIResponse {
    if (screenshot.isNullOrBlank() && uiContext.isNullOrBlank()) {
      throw AIClientException(AIErrorType.NoScreenshotOrUiContext)
    }

    val (provider, model) = providerRepository.getFirstEnabledProviderWithModel()
      ?: throw AIClientException(AIErrorType.NoActiveProvider)

    Log.d(TAG, "Using provider: ${provider.name}, model: ${model.modelId}")

    if (!provider.isConfigured()) {
      throw AIClientException(AIErrorType.ProviderNotConfigured(provider.name))
    }

    val adapter = adapters[provider.protocol]
      ?: throw AIClientException(AIErrorType.UnsupportedProtocol(provider.protocol.name))

    val settings = settingsRepository.getSettings()
    val somSettings = settingsRepository.getSomSettings()

    val systemPrompt = if (somSettings.enabled && somPromptBuilder != null) {
      somPromptBuilder.buildSystemPrompt()
    } else {
      getActiveSystemPrompt(settings, taskDescription, appContext)
    }

    val messages = buildMessages(
      screenshot = screenshot,
      taskDescription = taskDescription,
      uiContext = uiContext,
      appContext = appContext,
      history = conversationHistory,
      systemPrompt = systemPrompt,
    )

    val startTime = System.currentTimeMillis()
    val response = adapter.sendRequest(
      provider = provider,
      modelId = model.modelId,
      messages = messages,
      maxTokens = settings.maxTokens,
      timeoutMs = settings.requestTimeoutMs,
    )
    val latencyMs = System.currentTimeMillis() - startTime

    // Record inference stats
    val cost = model.calculateCost(response.inputTokens, response.outputTokens)
    statsRepository.recordInference(
      modelId = model.modelId,
      providerId = provider.id,
      inputTokens = response.inputTokens,
      outputTokens = response.outputTokens,
      cost = cost,
      latencyMs = latencyMs,
    )

    return parseAIResponse(response.content)
  }

  private fun parseAIResponse(content: String): AIResponse {
    val cleaned = cleanJsonContent(content)
    if (cleaned.isBlank()) {
      throw AIClientException(AIErrorType.ParseFailed("Empty after cleanup", content.take(200)))
    }
    return try {
      json.decodeFromString<AIResponse>(cleaned)
    } catch (e: Exception) {
      throw AIClientException(AIErrorType.ParseFailed(e.message ?: "Unknown", cleaned.take(500)), e)
    }
  }

  private fun cleanJsonContent(content: String): String {
    if (content.isBlank()) return ""
    var cleaned = content.trim()

    // Remove markdown code blocks
    if (cleaned.startsWith("```")) {
      val firstNewline = cleaned.indexOf('\n')
      val startIndex = if (firstNewline in 1..19) firstNewline + 1
      else cleaned.indexOf('{').takeIf { it != -1 } ?: 3
      if (startIndex < cleaned.length) cleaned = cleaned.substring(startIndex)
    }
    if (cleaned.endsWith("```")) cleaned = cleaned.dropLast(3)
    cleaned = cleaned.trim()

    // Extract JSON object
    if (cleaned.isNotEmpty() && !cleaned.startsWith("{")) {
      val jsonStart = cleaned.indexOf('{')
      val jsonEnd = cleaned.lastIndexOf('}')
      if (jsonStart != -1 && jsonEnd > jsonStart) {
        cleaned = cleaned.substring(jsonStart, jsonEnd + 1)
      }
    }
    return cleaned
  }

  private suspend fun getActiveSystemPrompt(
    settings: AISettings,
    taskDescription: String?,
    appContext: String?,
  ): String {
    val basePrompt = promptRepository?.let {
      try {
        val promptSettings = it.getPromptSettingsOnce()
        val activeId = promptSettings.activeSystemPromptId
        promptSettings.templates.find { t -> t.id == activeId && t.isEnabled }?.content
      } catch (_: Exception) {
        null
      }
    } ?: settings.systemPrompt

    return variableResolver?.let {
      try {
        val context = it.buildContext(
          task = taskDescription,
          maxSteps = settings.maxSteps,
          appName = appContext?.let { ctx -> extractAppName(ctx) },
          appPackage = appContext?.let { ctx -> extractAppPackage(ctx) },
        )
        it.resolve(basePrompt, context)
      } catch (_: Exception) {
        basePrompt
      }
    } ?: basePrompt
  }

  private fun extractAppName(appContext: String): String? {
    val regex = """Current app:\s*([^(]+)""".toRegex()
    return regex.find(appContext)?.groupValues?.getOrNull(1)?.trim()
  }

  private fun extractAppPackage(appContext: String): String? {
    val regex = """\(([a-z][a-z0-9_.]*)\)""".toRegex()
    return regex.find(appContext)?.groupValues?.getOrNull(1)
  }

  private fun buildMessages(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    appContext: String?,
    history: List<Message>,
    systemPrompt: String,
  ): List<Message> {
    val systemMessage = Message(
      role = "system",
      content = listOf(ContentPart.Text(text = systemPrompt)),
    )

    val userContent = buildList {
      add(ContentPart.Text(text = "Task: $taskDescription"))
      if (!appContext.isNullOrBlank()) add(ContentPart.Text(text = "\n\n$appContext"))
      if (!uiContext.isNullOrBlank()) add(ContentPart.Text(text = "\n\n$uiContext"))
      if (!screenshot.isNullOrBlank()) {
        add(ContentPart.ImageUrl(imageUrl = ImageUrlData(url = "data:image/jpeg;base64,$screenshot")))
      }
    }

    val userMessage = Message(role = "user", content = userContent)
    return listOf(systemMessage) + history + listOf(userMessage)
  }
}
