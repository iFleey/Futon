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
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIErrorType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Base adapter with shared utilities for AI provider adapters.
 */
abstract class BaseAdapter(
  protected val httpClient: HttpClient,
  protected val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {

  protected suspend fun handleResponse(response: HttpResponse): String {
    if (!response.status.isSuccess()) {
      val errorBody = runCatching { response.bodyAsText() }.getOrNull()
      throw AIClientException(parseHttpError(response.status.value, errorBody))
    }
    return response.bodyAsText()
  }

  protected fun parseHttpError(statusCode: Int, errorBody: String?): AIErrorType {
    val detail = errorBody?.take(200) ?: "No details"
    return when (statusCode) {
      400 -> AIErrorType.BadRequest
      401, 403 -> AIErrorType.InvalidApiKey
      404 -> AIErrorType.ModelNotFound
      429 -> AIErrorType.RateLimitExceeded
      in 500..599 -> AIErrorType.ServerError(statusCode, detail)
      else -> AIErrorType.HttpError(statusCode, detail)
    }
  }

  protected fun wrapNetworkException(e: Exception, baseUrl: String, timeoutMs: Long): Nothing {
    val host = baseUrl.removePrefix("https://").removePrefix("http://")
      .substringBefore("/").substringBefore(":")
    val timeoutSec = timeoutMs / 1000
    val suggestedSec = (timeoutMs * 1.5).toLong().coerceAtMost(900_000L) / 1000

    val errorType = when (e) {
      is HttpRequestTimeoutException -> AIErrorType.RequestTimeout(timeoutSec, suggestedSec)
      is SocketTimeoutException -> AIErrorType.ConnectionTimeout(suggestedSec)
      is UnknownHostException -> AIErrorType.DnsResolutionFailed(host)
      is NoRouteToHostException -> AIErrorType.NetworkUnreachable(host)
      is SSLHandshakeException -> AIErrorType.SslHandshakeFailed(e.message ?: "Unknown")
      is SSLException -> AIErrorType.SslError(e.message ?: "Unknown")
      is SocketException -> AIErrorType.ConnectionAborted(e.message ?: "Unknown")
      is ConnectException -> AIErrorType.ConnectionFailed(host, e.message ?: "Unknown")
      is IOException -> AIErrorType.IoError(e.javaClass.simpleName, e.message ?: "Unknown")
      else -> AIErrorType.UnknownError(e.javaClass.simpleName, e.message ?: "Unknown")
    }
    throw AIClientException(errorType, e)
  }

  companion object {
    /** Extract base64 data from data URL (e.g., "data:image/jpeg;base64,/Fleey...") */
    fun extractBase64(dataUrl: String): String =
      if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl

    /** Extract MIME type from data URL */
    fun extractMimeType(dataUrl: String): String =
      if (dataUrl.startsWith("data:") && dataUrl.contains(";"))
        dataUrl.substringAfter("data:").substringBefore(";")
      else "image/jpeg"
  }
}
