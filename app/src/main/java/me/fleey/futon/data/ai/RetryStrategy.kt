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
 * Retry strategy implementation with exponential backoff.
 * Used for handling transient errors in AI requests.
 */
object RetryStrategy {

  private const val DEFAULT_BASE_DELAY_MS = 1000L
  private const val MAX_DELAY_MS = 30_000L // Cap at 30 seconds

  /**
   * Calculates the delay before the next retry attempt using exponential backoff.
   *
   * Formula: baseDelayMs * 2^attempt
   * Example with baseDelayMs=1000: attempt 0 -> 1s, attempt 1 -> 2s, attempt 2 -> 4s
   *
   * @param attempt The current retry attempt (0-indexed)
   * @param baseDelayMs The base delay in milliseconds (default: 1000ms)
   * @return The calculated delay in milliseconds, capped at MAX_DELAY_MS
   */
  fun calculateDelay(attempt: Int, baseDelayMs: Long = DEFAULT_BASE_DELAY_MS): Long {
    require(attempt >= 0) { "Attempt must be non-negative" }
    require(baseDelayMs > 0) { "Base delay must be positive" }

    // Calculate exponential backoff: baseDelay * 2^attempt
    val delay = baseDelayMs * (1L shl attempt.coerceAtMost(30)) // Prevent overflow
    return delay.coerceAtMost(MAX_DELAY_MS)
  }

  /**
   * Determines whether a retry should be attempted based on the error type and attempt count.
   *
   * @param error The AIError that occurred
   * @param currentAttempt The current attempt number (0-indexed, so 0 means first attempt failed)
   * @param maxRetries The maximum number of retry attempts allowed
   * @return true if a retry should be attempted, false otherwise
   */
  fun shouldRetry(error: AIError, currentAttempt: Int, maxRetries: Int): Boolean {
    require(currentAttempt >= 0) { "Current attempt must be non-negative" }
    require(maxRetries >= 0) { "Max retries must be non-negative" }

    return error.isRetryable && currentAttempt < maxRetries
  }
}
