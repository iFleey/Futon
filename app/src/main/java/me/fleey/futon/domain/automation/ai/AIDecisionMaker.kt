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
package me.fleey.futon.domain.automation.ai

import android.util.Log
import kotlinx.coroutines.delay
import me.fleey.futon.data.ai.AIClient
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIError
import me.fleey.futon.data.ai.RetryStrategy
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.history.models.ErrorType
import org.koin.core.annotation.Single

sealed interface AIDecisionResult {
  data class Success(
    val response: AIResponse,
    val responseTimeMs: Long,
  ) : AIDecisionResult

  data class Failure(
    val reason: String,
    val errorType: ErrorType,
    val isRetryable: Boolean,
  ) : AIDecisionResult
}

/**
 * Handles AI decision making with retry logic and error classification.
 */
interface AIDecisionMaker {
  suspend fun requestDecision(
    screenshot: String?,
    uiContext: String?,
    appContext: String?,
    task: String,
    maxRetries: Int,
    actionHistory: List<String> = emptyList(),
    onRetry: suspend (attempt: Int, reason: String?) -> Unit,
  ): AIDecisionResult
}

@Single(binds = [AIDecisionMaker::class])
class AIDecisionMakerImpl(
  private val aiClient: AIClient,
  private val somActionParser: me.fleey.futon.domain.som.SomActionParser?,
  private val settingsRepository: me.fleey.futon.data.settings.SettingsRepository,
) : AIDecisionMaker {

  override suspend fun requestDecision(
    screenshot: String?,
    uiContext: String?,
    appContext: String?,
    task: String,
    maxRetries: Int,
    actionHistory: List<String>,
    onRetry: suspend (attempt: Int, reason: String?) -> Unit,
  ): AIDecisionResult {
    var lastException: Exception? = null
    val startTime = System.currentTimeMillis()

    // Check if SOM mode is enabled
    val somSettings = settingsRepository.getSomSettings()
    val isSomMode = somSettings.enabled

    for (attempt in 0..maxRetries) {
      try {
        if (attempt > 0) {
          onRetry(attempt, lastException?.message)
          val delayMs = RetryStrategy.calculateDelay(attempt - 1)
          Log.i(TAG, "Waiting ${delayMs}ms before retry")
          delay(delayMs)
        }

        Log.i(TAG, "AI request attempt ${attempt + 1}, history size: ${actionHistory.size}, SOM mode: $isSomMode")

        // Build history context as part of user message
        val historyContext = if (actionHistory.isNotEmpty()) {
          buildString {
            appendLine("\n\n=== PREVIOUS ACTIONS (DO NOT REPEAT FAILED PATTERNS) ===")
            actionHistory.forEachIndexed { index, action ->
              appendLine("Step ${index + 1}: $action")
            }
            appendLine("=== END OF HISTORY ===")
            appendLine("\nIMPORTANT: If you see repeated tap->back patterns above, the tapped element was WRONG. Find a DIFFERENT element!")
          }
        } else ""

        val taskWithHistory = task + historyContext

        val response = aiClient.analyzeScreenshot(
          screenshot, taskWithHistory, uiContext, emptyList(), appContext,
        )
        val responseTimeMs = System.currentTimeMillis() - startTime

        return AIDecisionResult.Success(response, responseTimeMs)

      } catch (e: AIClientException) {
        Log.e(TAG, "AI request failed (attempt ${attempt + 1}): ${e.message}")
        lastException = e

        val aiError = classifyError(e)
        if (!RetryStrategy.shouldRetry(aiError, attempt, maxRetries)) {
          return AIDecisionResult.Failure(
            reason = e.message ?: "Unknown error",
            errorType = classifyErrorType(e),
            isRetryable = false,
          )
        }
      } catch (e: Exception) {
        return AIDecisionResult.Failure(
          reason = e.message ?: "Unknown error",
          errorType = ErrorType.UNKNOWN,
          isRetryable = false,
        )
      }
    }

    return AIDecisionResult.Failure(
      reason = lastException?.message ?: "Unknown error after retries",
      errorType = classifyErrorType(lastException),
      isRetryable = false,
    )
  }

  private fun classifyError(exception: AIClientException): AIError {
    val message = exception.message ?: "Unknown error"

    return when {
      message.containsAny("超时", "timeout") -> {
        AIError.TimeoutError(message, suggestedTimeoutMs = 180_000L)
      }

      message.containsAny(
        "网络", "network", "连接", "connect", "DNS", "解析失败",
        "无法解析", "UnknownHost", "SSL", "socket", "不可达",
        "unreachable", "中断", "abort", "IO 错误", "IOException",
      ) -> {
        AIError.NetworkError(message)
      }

      message.containsAny("429", "rate limit", "频繁") -> {
        AIError.RateLimitError(message)
      }

      message.containsAny("401", "API Key", "authentication") -> {
        AIError.AuthenticationError(message)
      }

      else -> {
        AIError.InvalidResponseError(message)
      }
    }
  }

  private fun classifyErrorType(exception: Exception?): ErrorType {
    val message = exception?.message ?: return ErrorType.UNKNOWN

    return when {
      message.containsAny("超时", "timeout") -> ErrorType.TIMEOUT_ERROR
      message.containsAny(
        "网络", "network", "连接", "connect", "DNS", "解析失败",
        "无法解析", "UnknownHost", "SSL", "socket", "不可达",
        "unreachable", "中断", "abort", "IO 错误", "IOException",
      ) -> ErrorType.NETWORK_ERROR

      message.containsAny("429", "rate limit", "401", "API") -> ErrorType.API_ERROR
      message.containsAny("parse", "invalid") -> ErrorType.INVALID_RESPONSE
      else -> ErrorType.UNKNOWN
    }
  }

  private fun String.containsAny(vararg keywords: String): Boolean {
    return keywords.any { this.contains(it, ignoreCase = true) }
  }

  companion object {
    private const val TAG = "AIDecisionMaker"
  }
}
