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
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.ai.models.Provider
import org.koin.core.annotation.Single

@Single
class OllamaAdapter(
  httpClient: HttpClient,
  json: Json = Json { ignoreUnknownKeys = true },
) : BaseAdapter(httpClient, json) {

  override val protocol: ApiProtocol = ApiProtocol.OLLAMA

  override suspend fun sendRequest(
    provider: Provider,
    modelId: String,
    messages: List<Message>,
    maxTokens: Int,
    timeoutMs: Long,
  ): AdapterResponse {
    val ollamaMessages = messages.map { msg ->
      val images = msg.content.filterIsInstance<ContentPart.ImageUrl>()
        .map { extractBase64(it.imageUrl.url) }
      val text = msg.content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
      OllamaMessage(role = msg.role, content = text, images = images.ifEmpty { null })
    }

    val response = try {
      httpClient.post("${provider.baseUrl}/api/chat") {
        timeout { requestTimeoutMillis = timeoutMs }
        contentType(ContentType.Application.Json)
        setBody(
          OllamaRequest(
            model = modelId,
            messages = ollamaMessages,
            stream = false,
            options = OllamaOptions(numPredict = maxTokens),
          ),
        )
      }
    } catch (e: Exception) {
      wrapNetworkException(e, provider.baseUrl, timeoutMs)
    }

    val body = handleResponse(response)
    val ollamaResponse = json.decodeFromString<OllamaResponse>(body)

    val content = ollamaResponse.message?.content
    if (content.isNullOrBlank()) throw AIClientException(AIErrorType.NoTextContent)

    return AdapterResponse(
      content = content,
      inputTokens = ollamaResponse.promptEvalCount?.toLong() ?: 0,
      outputTokens = ollamaResponse.evalCount?.toLong() ?: 0,
    )
  }

  override suspend fun fetchAvailableModels(provider: Provider): List<String> {
    if (provider.baseUrl.isBlank()) return emptyList()

    return try {
      val response = httpClient.get("${provider.baseUrl}/api/tags")
      if (!response.status.isSuccess()) return emptyList()

      response.body<OllamaTagsResponse>().models
        .map { it.name }
        .sorted()
    } catch (_: Exception) {
      emptyList()
    }
  }
}

@Serializable
private data class OllamaRequest(
  val model: String,
  val messages: List<OllamaMessage>,
  val stream: Boolean = false,
  val options: OllamaOptions? = null,
)

@Serializable
private data class OllamaMessage(
  val role: String,
  val content: String,
  val images: List<String>? = null,
)

@Serializable
private data class OllamaOptions(
  @SerialName("num_predict") val numPredict: Int? = null,
)

@Serializable
private data class OllamaResponse(
  val model: String? = null,
  val message: OllamaResponseMessage? = null,
  @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
  @SerialName("eval_count") val evalCount: Int? = null,
)

@Serializable
private data class OllamaResponseMessage(
  val role: String,
  val content: String,
)

@Serializable
private data class OllamaTagsResponse(val models: List<OllamaModelInfo>)

@Serializable
private data class OllamaModelInfo(
  val name: String,
  val model: String? = null,
  val size: Long? = null,
)
