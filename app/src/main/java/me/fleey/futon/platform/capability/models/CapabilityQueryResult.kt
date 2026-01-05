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
package me.fleey.futon.platform.capability.models

import kotlin.time.Duration

sealed interface CapabilityQueryResult {
  /**
   * Fresh capabilities successfully retrieved from the daemon.
   *
   * @property capabilities The current device capabilities
   */
  data class Fresh(
    val capabilities: DeviceCapabilities,
  ) : CapabilityQueryResult

  /**
   * Stale cached capabilities returned when fresh query failed.
   * The UI should indicate that data may be outdated.
   *
   * @property capabilities The cached device capabilities
   * @property age How long ago the capabilities were retrieved
   * @property reason Why fresh data could not be obtained
   */
  data class Stale(
    val capabilities: DeviceCapabilities,
    val age: Duration,
    val reason: String,
  ) : CapabilityQueryResult

  data class Error(
    val message: String,
    val errorType: CapabilityErrorType,
    val lastKnown: DeviceCapabilities?,
  ) : CapabilityQueryResult
}

enum class CapabilityErrorType {
  DAEMON_NOT_RUNNING,

  CONNECTION_FAILED,

  AUTHENTICATION_FAILED,

  TIMEOUT,

  DAEMON_ERROR,

  UNKNOWN
}

fun CapabilityQueryResult.getCapabilities(): DeviceCapabilities? = when (this) {
  is CapabilityQueryResult.Fresh -> capabilities
  is CapabilityQueryResult.Stale -> capabilities
  is CapabilityQueryResult.Error -> lastKnown
}

fun CapabilityQueryResult.isFresh(): Boolean = this is CapabilityQueryResult.Fresh

fun CapabilityQueryResult.isStale(): Boolean = this is CapabilityQueryResult.Stale

fun CapabilityQueryResult.isError(): Boolean = this is CapabilityQueryResult.Error
