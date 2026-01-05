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
package me.fleey.futon.domain.som

import android.util.Log
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ContentPart
import me.fleey.futon.data.ai.models.ImageUrlData
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.domain.som.models.SomAnnotation
import org.koin.core.annotation.Single

sealed interface SomAIResult {
  data class Success(
    val response: AIResponse,
    val parseResult: SomParseResult.Success,
    val responseTimeMs: Long,
  ) : SomAIResult

  data class Failure(
    val reason: String,
    val isRetryable: Boolean,
  ) : SomAIResult
}

interface SomAIClient {
  suspend fun requestDecision(
    annotatedScreenshot: String,
    annotation: SomAnnotation,
    task: String,
    actionHistory: List<String> = emptyList(),
    appContext: String? = null,
  ): SomAIResult
}

@Single(binds = [SomAIClient::class])
class SomAIClientImpl(
  private val providerRepository: ProviderRepository,
  private val settingsRepository: SettingsRepository,
  private val adapters: Map<ApiProtocol, ProviderAdapter>,
  private val somPromptBuilder: SomPromptBuilder,
  private val somActionParser: SomActionParser,
  private val statsRepository: me.fleey.futon.data.ai.repository.InferenceStatsRepository,
) : SomAIClient {

  override suspend fun requestDecision(
    annotatedScreenshot: String,
    annotation: SomAnnotation,
    task: String,
    actionHistory: List<String>,
    appContext: String?,
  ): SomAIResult {
    val startTime = System.currentTimeMillis()

    try {
      val (provider, modelConfig) = providerRepository.getFirstEnabledProviderWithModel()
        ?: return SomAIResult.Failure("No active AI provider configured", isRetryable = false)

      if (!provider.isConfigured()) {
        return SomAIResult.Failure("API key not configured", isRetryable = false)
      }

      val adapter = getAdapterForProvider(provider)

      val messages = buildSomMessages(
        annotatedScreenshot = annotatedScreenshot,
        annotation = annotation,
        task = task,
        actionHistory = actionHistory,
        appContext = appContext,
      )

      Log.d(TAG, "Sending SoM request with ${annotation.elementCount} elements")

      val settings = settingsRepository.getSettings()
      val adapterResponse = adapter.sendRequest(
        provider = provider,
        modelId = modelConfig.modelId,
        messages = messages,
        maxTokens = settings.maxTokens,
        timeoutMs = settings.requestTimeoutMs,
      )

      val responseTimeMs = System.currentTimeMillis() - startTime

      // Record inference stats
      val cost = modelConfig.calculateCost(adapterResponse.inputTokens, adapterResponse.outputTokens)
      statsRepository.recordInference(
        modelId = modelConfig.modelId,
        providerId = provider.id,
        inputTokens = adapterResponse.inputTokens,
        outputTokens = adapterResponse.outputTokens,
        cost = cost,
        latencyMs = responseTimeMs,
      )

      val parseResult = somActionParser.parse(adapterResponse.content, annotation)

      return when (parseResult) {
        is SomParseResult.Success -> {
          val aiResponse = parseResult.toAIResponse()
          SomAIResult.Success(
            response = aiResponse,
            parseResult = parseResult,
            responseTimeMs = responseTimeMs,
          )
        }

        is SomParseResult.Failure -> {
          Log.w(TAG, "Failed to parse SoM response: ${parseResult.reason}")
          SomAIResult.Failure(
            reason = "Parse error: ${parseResult.reason}",
            isRetryable = true,
          )
        }
      }
    } catch (e: AIClientException) {
      Log.e(TAG, "AI request failed: ${e.message}")
      return SomAIResult.Failure(
        reason = e.message ?: "Unknown AI error",
        isRetryable = isRetryableError(e),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Unexpected error in SoM AI request", e)
      return SomAIResult.Failure(
        reason = "Unexpected error: ${e.message}",
        isRetryable = false,
      )
    }
  }

  private fun buildSomMessages(
    annotatedScreenshot: String,
    annotation: SomAnnotation,
    task: String,
    actionHistory: List<String>,
    appContext: String?,
  ): List<Message> {
    val systemPrompt = somPromptBuilder.buildSystemPrompt()
    val systemMessage = Message(
      role = "system",
      content = listOf(ContentPart.Text(text = systemPrompt)),
    )

    val userText = somPromptBuilder.buildUserMessage(
      task = task,
      annotation = annotation,
      actionHistory = actionHistory,
      appContext = appContext,
    )

    val userContent = buildList {
      add(ContentPart.Text(text = userText))
      add(
        ContentPart.ImageUrl(
          imageUrl = ImageUrlData(
            url = "data:image/jpeg;base64,$annotatedScreenshot",
            detail = "high",
          ),
        ),
      )
    }

    val userMessage = Message(
      role = "user",
      content = userContent,
    )

    return listOf(systemMessage, userMessage)
  }

  private fun getAdapterForProvider(provider: Provider): ProviderAdapter {
    return adapters[provider.protocol]
      ?: throw AIClientException("Adapter not found for protocol: ${provider.protocol}")
  }

  private fun isRetryableError(e: AIClientException): Boolean {
    val message = e.message ?: return false
    return message.contains("timeout", ignoreCase = true) ||
      message.contains("429") ||
      message.contains("rate limit", ignoreCase = true) ||
      message.contains("network", ignoreCase = true)
  }

  companion object {
    private const val TAG = "SomAIClient"
  }
}
