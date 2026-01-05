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
package me.fleey.futon.data.ai.adapters

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIErrorType
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ContentPart
import me.fleey.futon.data.ai.models.GeminiContent
import me.fleey.futon.data.ai.models.GeminiGenerationConfig
import me.fleey.futon.data.ai.models.GeminiPart
import me.fleey.futon.data.ai.models.GeminiRequest
import me.fleey.futon.data.ai.models.GeminiResponse
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider
import org.koin.core.annotation.Single

@Single
class GeminiAdapter(
  httpClient: HttpClient,
  json: Json = Json { ignoreUnknownKeys = true },
) : BaseAdapter(httpClient, json) {

  override val protocol: ApiProtocol = ApiProtocol.GEMINI

  override suspend fun sendRequest(
    provider: Provider,
    modelId: String,
    messages: List<Message>,
    maxTokens: Int,
    timeoutMs: Long,
  ): AdapterResponse {
    val geminiRequest = buildGeminiRequest(messages, maxTokens)

    val response = try {
      httpClient.post("${provider.baseUrl}/models/$modelId:generateContent") {
        timeout { requestTimeoutMillis = timeoutMs }
        parameter("key", provider.apiKey)
        contentType(ContentType.Application.Json)
        setBody(geminiRequest)
      }
    } catch (e: Exception) {
      wrapNetworkException(e, provider.baseUrl, timeoutMs)
    }

    val body = handleResponse(response)
    val geminiResponse = json.decodeFromString<GeminiResponse>(body)

    val candidates = geminiResponse.candidates
      ?: throw AIClientException(AIErrorType.EmptyResponse)

    if (candidates.isEmpty()) {
      geminiResponse.promptFeedback?.blockReason?.let { reason ->
        throw AIClientException(AIErrorType.SafetyBlocked(reason))
      }
      throw AIClientException(AIErrorType.EmptyResponse)
    }

    val candidate = candidates.first()
    candidate.finishReason?.uppercase()?.let { reason ->
      when (reason) {
        "SAFETY" -> throw AIClientException(AIErrorType.ContentFiltered)
        "RECITATION" -> throw AIClientException(AIErrorType.RecitationBlocked)
        else -> {}
      }
    }

    val textPart = candidate.content.parts.filterIsInstance<GeminiPart.Text>().firstOrNull()
      ?: throw AIClientException(AIErrorType.NoTextContent)

    if (textPart.text.isBlank()) throw AIClientException(AIErrorType.NoTextContent)

    val usage = geminiResponse.usageMetadata
    return AdapterResponse(
      content = textPart.text,
      inputTokens = usage?.promptTokenCount?.toLong() ?: 0,
      outputTokens = usage?.candidatesTokenCount?.toLong() ?: 0,
    )
  }

  override suspend fun fetchAvailableModels(provider: Provider): List<String> {
    if (provider.apiKey.isBlank() || provider.baseUrl.isBlank()) return emptyList()

    return try {
      val response = httpClient.get("${provider.baseUrl}/models") {
        parameter("key", provider.apiKey)
      }
      if (!response.status.isSuccess()) return emptyList()

      response.body<GeminiModelsResponse>().models
        .filter { it.supportedGenerationMethods?.contains("generateContent") == true }
        .mapNotNull { it.name?.substringAfterLast("/") }
        .filter { it.startsWith("gemini") }
        .sortedDescending()
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun buildGeminiRequest(messages: List<Message>, maxTokens: Int): GeminiRequest {
    var systemInstruction: GeminiContent? = null
    val contents = mutableListOf<GeminiContent>()

    for (message in messages) {
      when (message.role) {
        "system" -> {
          val textParts = message.content
            .filterIsInstance<ContentPart.Text>()
            .map { GeminiPart.Text(it.text) }
          if (textParts.isNotEmpty()) {
            systemInstruction = GeminiContent(role = "user", parts = textParts)
          }
        }

        "assistant" -> contents.add(
          GeminiContent(role = "model", parts = convertParts(message.content)),
        )

        else -> contents.add(
          GeminiContent(role = "user", parts = convertParts(message.content)),
        )
      }
    }

    if (contents.isEmpty()) {
      if (systemInstruction != null) {
        contents.add(systemInstruction)
        systemInstruction = null
      } else {
        contents.add(GeminiContent(role = "user", parts = listOf(GeminiPart.Text("Hello"))))
      }
    }

    return GeminiRequest(
      contents = contents,
      generationConfig = GeminiGenerationConfig(maxOutputTokens = maxTokens),
      systemInstruction = systemInstruction,
    )
  }

  private fun convertParts(parts: List<ContentPart>): List<GeminiPart> = parts.map { part ->
    when (part) {
      is ContentPart.Text -> GeminiPart.Text(part.text)
      is ContentPart.ImageUrl -> {
        val url = part.imageUrl.url
        GeminiPart.InlineData(
          mimeType = extractMimeType(url),
          data = extractBase64(url),
        )
      }
    }
  }
}

@Serializable
private data class GeminiModelsResponse(val models: List<GeminiModelInfo>)

@Serializable
private data class GeminiModelInfo(
  val name: String? = null,
  @SerialName("supportedGenerationMethods")
  val supportedGenerationMethods: List<String>? = null,
)
