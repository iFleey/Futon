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
import io.ktor.client.request.header
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
import me.fleey.futon.data.ai.models.ChatCompletionRequest
import me.fleey.futon.data.ai.models.ChatCompletionResponse
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider
import org.koin.core.annotation.Single

@Single
class OpenAIAdapter(
  httpClient: HttpClient,
  json: Json = Json { ignoreUnknownKeys = true },
) : BaseAdapter(httpClient, json) {

  override val protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE

  override suspend fun sendRequest(
    provider: Provider,
    modelId: String,
    messages: List<Message>,
    maxTokens: Int,
    timeoutMs: Long,
  ): AdapterResponse {
    val response = try {
      httpClient.post("${provider.baseUrl}/chat/completions") {
        timeout { requestTimeoutMillis = timeoutMs }
        header("Authorization", "Bearer ${provider.apiKey}")
        contentType(ContentType.Application.Json)
        setBody(ChatCompletionRequest(model = modelId, messages = messages, maxTokens = maxTokens))
      }
    } catch (e: Exception) {
      wrapNetworkException(e, provider.baseUrl, timeoutMs)
    }

    val body = handleResponse(response)
    val completion = json.decodeFromString<ChatCompletionResponse>(body)

    val choice = completion.choices.firstOrNull()
      ?: throw AIClientException(AIErrorType.EmptyResponse)

    if (choice.finishReason?.lowercase() == "content_filter") {
      throw AIClientException(AIErrorType.ContentFiltered)
    }

    val content = choice.message.content
    if (content.isBlank()) throw AIClientException(AIErrorType.NoTextContent)

    return AdapterResponse(
      content = content,
      inputTokens = completion.usage?.promptTokens?.toLong() ?: 0,
      outputTokens = completion.usage?.completionTokens?.toLong() ?: 0,
    )
  }

  override suspend fun fetchAvailableModels(provider: Provider): List<String> {
    if (provider.apiKey.isBlank() || provider.baseUrl.isBlank()) return emptyList()

    return try {
      val response = httpClient.get("${provider.baseUrl}/models") {
        header("Authorization", "Bearer ${provider.apiKey}")
      }
      if (!response.status.isSuccess()) return emptyList()

      response.body<OpenAIModelsResponse>().data
        .map { it.id }
        .sortedDescending()
    } catch (_: Exception) {
      emptyList()
    }
  }
}

@Serializable
private data class OpenAIModelsResponse(val data: List<OpenAIModel>)

@Serializable
private data class OpenAIModel(
  val id: String,
  @SerialName("object") val objectType: String? = null,
  @SerialName("owned_by") val ownedBy: String? = null,
)
