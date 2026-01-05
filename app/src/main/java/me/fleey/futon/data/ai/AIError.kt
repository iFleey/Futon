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

/**
 * Sealed interface representing different types of AI-related errors.
 * Each error type has a message and indicates whether it's retryable.
 */
sealed interface AIError {
  val message: String
  val isRetryable: Boolean

  /**
   * Network connectivity error (e.g., no internet, DNS failure).
   * These errors are typically transient and can be retried.
   */
  data class NetworkError(override val message: String) : AIError {
    override val isRetryable: Boolean = true
  }

  /**
   * Request timeout error. Includes a suggested timeout value for retry.
   * Common with thinking models (o1, Claude thinking) that need longer processing time.
   */
  data class TimeoutError(
    override val message: String,
    val suggestedTimeoutMs: Long,
  ) : AIError {
    override val isRetryable: Boolean = true
  }

  /**
   * Rate limit exceeded error. May include retry-after information.
   */
  data class RateLimitError(
    override val message: String,
    val retryAfterMs: Long? = null,
  ) : AIError {
    override val isRetryable: Boolean = true
  }

  /**
   * Authentication error (invalid API key, expired token).
   * These errors require user intervention and should not be retried automatically.
   */
  data class AuthenticationError(override val message: String) : AIError {
    override val isRetryable: Boolean = false
  }

  /**
   * Invalid response from AI provider (malformed JSON, unexpected format).
   * These errors may be transient and can be retried a limited number of times.
   */
  data class InvalidResponseError(override val message: String) : AIError {
    override val isRetryable: Boolean = true
  }
}
