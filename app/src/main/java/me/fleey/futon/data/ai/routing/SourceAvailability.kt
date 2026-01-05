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
package me.fleey.futon.data.ai.routing

/**
 * Represents the availability state of an inference source.
 *
 * The availability follows a state machine:
 * - Unknown -> Available (on success)
 * - Unknown -> Unavailable (on failure)
 * - Available -> Unavailable (on failure)
 * - Unavailable -> Unknown (after cooldown expires)
 */
sealed class SourceAvailability {

  /**
   * Source is available and ready for use.
   *
   * @property lastSuccessTimeMs Timestamp of last successful request
   */
  data class Available(
    val lastSuccessTimeMs: Long = System.currentTimeMillis(),
  ) : SourceAvailability()

  data class Unavailable(
    val reason: String,
    val failureTimeMs: Long = System.currentTimeMillis(),
    val cooldownExpiresMs: Long = System.currentTimeMillis() + DEFAULT_COOLDOWN_MS,
  ) : SourceAvailability() {

    fun isCooldownExpired(): Boolean = System.currentTimeMillis() >= cooldownExpiresMs

    fun remainingCooldownMs(): Long =
      (cooldownExpiresMs - System.currentTimeMillis()).coerceAtLeast(0)
  }

  /**
   * Source availability is unknown (not yet tested or cooldown expired).
   */
  data object Unknown : SourceAvailability()

  companion object {
    const val DEFAULT_COOLDOWN_MS: Long = 30_000L

    const val MIN_COOLDOWN_MS: Long = 5_000L

    const val MAX_COOLDOWN_MS: Long = 300_000L
  }
}
