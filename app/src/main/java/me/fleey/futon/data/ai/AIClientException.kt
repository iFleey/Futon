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

import androidx.annotation.StringRes
import me.fleey.futon.R

sealed class AIErrorType(
  @param:StringRes val messageRes: Int,
  val formatArgs: Array<out Any> = emptyArray(),
) {
  // Network errors
  data class RequestTimeout(val seconds: Long, val suggestedSeconds: Long) : AIErrorType(
    R.string.error_ai_request_timeout, arrayOf<Any>(seconds, suggestedSeconds),
  )

  data class ConnectionTimeout(val suggestedSeconds: Long) : AIErrorType(
    R.string.error_ai_connection_timeout, arrayOf<Any>(suggestedSeconds),
  )

  data class DnsResolutionFailed(val host: String) : AIErrorType(
    R.string.error_ai_dns_failed, arrayOf<Any>(host),
  )

  data class NetworkUnreachable(val host: String) : AIErrorType(
    R.string.error_ai_network_unreachable, arrayOf<Any>(host),
  )

  data class SslHandshakeFailed(val detail: String) : AIErrorType(
    R.string.error_ai_ssl_handshake, arrayOf<Any>(detail),
  )

  data class SslError(val detail: String) : AIErrorType(
    R.string.error_ai_ssl_error, arrayOf<Any>(detail),
  )

  data class ConnectionAborted(val detail: String) : AIErrorType(
    R.string.error_ai_connection_aborted, arrayOf<Any>(detail),
  )

  data class ConnectionFailed(val host: String, val detail: String) : AIErrorType(
    R.string.error_ai_connection_failed, arrayOf<Any>(host, detail),
  )

  data class IoError(val type: String, val detail: String) : AIErrorType(
    R.string.error_ai_io_error, arrayOf<Any>(type, detail),
  )

  data class UnknownError(val type: String, val detail: String) : AIErrorType(
    R.string.error_ai_unknown, arrayOf<Any>(type, detail),
  )

  // API errors
  data object InvalidApiKey : AIErrorType(R.string.error_ai_invalid_api_key)
  data object RateLimitExceeded : AIErrorType(R.string.error_ai_rate_limit)
  data object BadRequest : AIErrorType(R.string.error_ai_bad_request)
  data object ModelNotFound : AIErrorType(R.string.error_ai_model_not_found)
  data class ServerError(val code: Int, val detail: String) : AIErrorType(
    R.string.error_ai_server_error, arrayOf<Any>(code, detail),
  )

  data class HttpError(val code: Int, val detail: String) : AIErrorType(
    R.string.error_ai_http_error, arrayOf<Any>(code, detail),
  )

  // Response errors
  data object EmptyResponse : AIErrorType(R.string.error_ai_empty_response)
  data object ContentFiltered : AIErrorType(R.string.error_ai_content_filtered)
  data class SafetyBlocked(val reason: String) : AIErrorType(
    R.string.error_ai_safety_blocked, arrayOf<Any>(reason),
  )

  data object RecitationBlocked : AIErrorType(R.string.error_ai_recitation_blocked)
  data object NoTextContent : AIErrorType(R.string.error_ai_no_text_content)
  data class ParseFailed(val detail: String, val content: String) : AIErrorType(
    R.string.error_ai_parse_failed, arrayOf<Any>(detail, content),
  )

  data class ResponseFormatError(val detail: String) : AIErrorType(
    R.string.error_ai_response_format_detail, arrayOf<Any>(detail),
  )

  // Config errors
  data object ApiKeyOrUrlEmpty : AIErrorType(R.string.error_ai_config_empty)
  data class FetchModelsFailed(val detail: String) : AIErrorType(
    R.string.error_ai_fetch_models_failed, arrayOf<Any>(detail),
  )

  // Input validation errors
  data object NoScreenshotOrUiContext : AIErrorType(R.string.error_ai_no_screenshot_or_ui)
  data object NoActiveProvider : AIErrorType(R.string.error_ai_no_active_provider)
  data class ProviderNotConfigured(val providerName: String) : AIErrorType(
    R.string.error_ai_provider_not_configured, arrayOf<Any>(providerName),
  )

  data class ProviderNotFound(val providerId: String) : AIErrorType(
    R.string.error_ai_provider_not_found, arrayOf<Any>(providerId),
  )

  data class UnsupportedProtocol(val protocol: String) : AIErrorType(
    R.string.error_ai_unsupported_protocol, arrayOf<Any>(protocol),
  )

  data object LocalEngineNotConfigured : AIErrorType(R.string.error_ai_local_engine_not_configured)

  // Generic operation errors (for ViewModel use)
  data object FixFailed : AIErrorType(R.string.error_fix_failed)
  data object SaveFailed : AIErrorType(R.string.error_save_failed)
  data object LoadFailed : AIErrorType(R.string.error_load_failed)
  data object RefreshFailed : AIErrorType(R.string.error_refresh_failed)
  data object DetectFailed : AIErrorType(R.string.error_detect_failed)
  data object ClearFailed : AIErrorType(R.string.error_clear_failed)
  data object DownloadFailed : AIErrorType(R.string.error_download_failed)
  data object PauseFailed : AIErrorType(R.string.error_pause_failed)
  data object ResumeFailed : AIErrorType(R.string.error_resume_failed)
  data object CancelFailed : AIErrorType(R.string.error_cancel_failed)
  data object EnableFailed : AIErrorType(R.string.error_enable_failed)
  data object DisableFailed : AIErrorType(R.string.error_disable_failed)
  data object DeleteFailed : AIErrorType(R.string.error_delete_failed)
  data object ImportFailed : AIErrorType(R.string.error_import_failed)
  data object DataLoadError : AIErrorType(R.string.error_data_load)

  // Success messages
  data object DownloadComplete : AIErrorType(R.string.success_download_complete)
  data object DownloadPaused : AIErrorType(R.string.success_download_paused)
  data object DownloadResumed : AIErrorType(R.string.success_download_resumed)
  data object DownloadCancelled : AIErrorType(R.string.success_download_cancelled)
  data object ModelEnabled : AIErrorType(R.string.success_model_enabled)
  data object ModelDisabled : AIErrorType(R.string.success_model_disabled)
  data object ModelDeleted : AIErrorType(R.string.success_model_deleted)
  data object ModelImported : AIErrorType(R.string.success_model_imported)
  data object CatalogRefreshed : AIErrorType(R.string.success_catalog_refreshed)
}

class AIClientException(
  val errorType: AIErrorType,
  cause: Throwable? = null,
) : Exception(errorType.toString(), cause) {

  constructor(message: String, cause: Throwable? = null) : this(
    AIErrorType.UnknownError("Exception", message),
    cause,
  )
}
