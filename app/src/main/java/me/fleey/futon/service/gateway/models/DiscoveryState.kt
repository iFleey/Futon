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
 * Represents the current state of mDNS/NSD service discovery.
 */
sealed interface DiscoveryState {
  /**
   * Service is registered and discoverable.
   * @param serviceName The registered service name (e.g., "futon-abc123")
   * @param serviceType The service type (e.g., "_futon._tcp")
   * @param port The port number advertised
   */
  data class Registered(
    val serviceName: String,
    val serviceType: String,
    val port: Int,
  ) : DiscoveryState

  /**
   * Service registration is in progress.
   */
  data object Registering : DiscoveryState

  /**
   * Service is not registered.
   * @param reason Reason for not being registered
   */
  data class Unregistered(val reason: UnregisteredReason) : DiscoveryState

  data class Failed(
    val errorCode: Int,
    val message: String,
  ) : DiscoveryState
}

enum class UnregisteredReason {
  /** mDNS is disabled in settings */
  DISABLED,

  SERVER_NOT_RUNNING,

  /** Network is not available */
  NO_NETWORK,

  /** Service was explicitly unregistered */
  UNREGISTERED,

  NOT_STARTED
}

val DiscoveryState.isDiscoverable: Boolean
  get() = this is DiscoveryState.Registered

val DiscoveryState.serviceName: String?
  get() = (this as? DiscoveryState.Registered)?.serviceName
