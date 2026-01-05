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
package me.fleey.futon.service.gateway.models

/**
 * Represents the current state of the LAN HTTP server.
 */
sealed interface ServerState {
  /**
   * Server is running and accepting connections.
   * @param ipAddress IP address the server is bound to
   * @param port Port number the server is listening on
   * @param useTls Whether TLS is enabled
   */
  data class Running(
    val ipAddress: String,
    val port: Int,
    val useTls: Boolean = false,
  ) : ServerState {
    val fullAddress: String get() = "$ipAddress:$port"
    val url: String get() = "${if (useTls) "https" else "http"}://$fullAddress"
  }

  data class Stopped(val reason: StopReason = StopReason.USER_REQUESTED) : ServerState

  data object Starting : ServerState

  data class Error(
    val reason: String,
    val exception: Throwable? = null,
  ) : ServerState
}

enum class StopReason {
  /** User explicitly stopped the server */
  USER_REQUESTED,

  /** Idle timeout expired */
  IDLE_TIMEOUT,

  /** Network became unavailable */
  NETWORK_UNAVAILABLE,

  /** Network is not trusted */
  UNTRUSTED_NETWORK,

  /** Service is being destroyed */
  SERVICE_DESTROYED,

  /** Server was never started */
  NOT_STARTED
}

val ServerState.isRunning: Boolean
  get() = this is ServerState.Running

val ServerState.serverUrl: String?
  get() = (this as? ServerState.Running)?.url
