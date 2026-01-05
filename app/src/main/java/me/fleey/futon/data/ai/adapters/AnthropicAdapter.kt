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
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIErrorType
import me.fleey.futon.data.ai.models.ApiProtocol
import me.fleey.futon.data.ai.models.ContentPart
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider
import org.koin.core.annotation.Single

private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Adapter for Anthropic Claude API.
 */
@Single
class AnthropicAdapter(
  httpClient: HttpClient,
  json: Json = Json { ignoreUnknownKeys = true },
) : BaseAdapter(httpClient, json) {

  override val protocol: ApiProtocol = ApiProtocol.ANTHROPIC

  override suspend fun sendRequest(
    provider: Provider,
    modelId: String,
    messages: List<Message>,
    maxTokens: Int,
    timeoutMs: Long,
  ): AdapterResponse {
    val (systemPrompt, anthropicMessages) = convertMessages(messages)

    val response = try {
      httpClient.post("${provider.baseUrl}/messages") {
        timeout { requestTimeoutMillis = timeoutMs }
        header("x-api-key", provider.apiKey)
        header("anthropic-version", ANTHROPIC_VERSION)
        contentType(ContentType.Application.Json)
        setBody(
          AnthropicRequest(
            model = modelId,
            maxTokens = maxTokens,
            system = systemPrompt,
            messages = anthropicMessages,
          ),
        )
      }
    } catch (e: Exception) {
      wrapNetworkException(e, provider.baseUrl, timeoutMs)
    }

    val body = handleResponse(response)
    val anthropicResponse = json.decodeFromString<AnthropicResponse>(body)

    val textContent = anthropicResponse.content
      .filterIsInstance<AnthropicResponseContent.Text>()
      .firstOrNull()
      ?: throw AIClientException(AIErrorType.NoTextContent)

    if (textContent.text.isBlank()) throw AIClientException(AIErrorType.NoTextContent)

    return AdapterResponse(
      content = textContent.text,
      inputTokens = anthropicResponse.usage?.inputTokens?.toLong() ?: 0,
      outputTokens = anthropicResponse.usage?.outputTokens?.toLong() ?: 0,
    )
  }

  private fun convertMessages(messages: List<Message>): Pair<String?, List<AnthropicMessage>> {
    var systemPrompt: String? = null
    val anthropicMessages = mutableListOf<AnthropicMessage>()

    for (message in messages) {
      when (message.role) {
        "system" -> {
          systemPrompt = message.content
            .filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
        }

        "user", "assistant" -> {
          val content = message.content.map { part ->
            when (part) {
              is ContentPart.Text -> AnthropicContent.Text(part.text)
              is ContentPart.ImageUrl -> {
                val url = part.imageUrl.url
                AnthropicContent.Image(
                  source = AnthropicImageSource(
                    type = "base64",
                    mediaType = extractMimeType(url),
                    data = extractBase64(url),
                  ),
                )
              }
            }
          }
          anthropicMessages.add(AnthropicMessage(role = message.role, content = content))
        }
      }
    }
    return systemPrompt to anthropicMessages
  }
}

@Serializable
private data class AnthropicRequest(
  val model: String,
  @SerialName("max_tokens") val maxTokens: Int,
  val system: String? = null,
  val messages: List<AnthropicMessage>,
)

@Serializable
private data class AnthropicMessage(
  val role: String,
  val content: List<AnthropicContent>,
)

@Serializable
private sealed class AnthropicContent {
  @Serializable
  @SerialName("text")
  data class Text(val text: String) : AnthropicContent()

  @Serializable
  @SerialName("image")
  data class Image(val source: AnthropicImageSource) : AnthropicContent()
}

@Serializable
private data class AnthropicImageSource(
  val type: String,
  @SerialName("media_type") val mediaType: String,
  val data: String,
)

@Serializable
private data class AnthropicResponse(
  val id: String,
  val content: List<AnthropicResponseContent>,
  @SerialName("stop_reason") val stopReason: String? = null,
  val usage: AnthropicUsage? = null,
)

@Serializable
private sealed class AnthropicResponseContent {
  @Serializable
  @SerialName("text")
  data class Text(val text: String) : AnthropicResponseContent()
}

@Serializable
private data class AnthropicUsage(
  @SerialName("input_tokens") val inputTokens: Int,
  @SerialName("output_tokens") val outputTokens: Int,
)
