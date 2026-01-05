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
 * Represents the current IP binding state for the LAN HTTP server.
 */
sealed interface BindingState {
  /**
   * Successfully bound to a network interface.
   * @param interfaceName Network interface name (wlan0, eth0)
   * @param ipAddress IP address bound to
   * @param port Port number bound to
   */
  data class Bound(
    val interfaceName: String,
    val ipAddress: String,
    val port: Int,
  ) : BindingState {
    val fullAddress: String get() = "$ipAddress:$port"
  }

  data class Unbound(val reason: UnboundReason) : BindingState

  /**
   * Currently rebinding to a new interface (network change in progress).
   * @param previousInterface Previous interface name, if any
   */
  data class Rebinding(val previousInterface: String? = null) : BindingState
}

enum class UnboundReason {
  /** No suitable network interface available */
  NO_SUITABLE_INTERFACE,

  /** Only cellular network available (rmnet) */
  CELLULAR_ONLY,

  /** Network is not trusted (not in whitelist) */
  UNTRUSTED_NETWORK,

  /** Server is disabled by user */
  DISABLED,

  /** Server has not been started yet */
  NOT_STARTED,

  /** Binding failed due to an error */
  BINDING_ERROR,

  /** Port is already in use */
  PORT_IN_USE
}

val BindingState.isBound: Boolean
  get() = this is BindingState.Bound

val BindingState.boundAddress: String?
  get() = (this as? BindingState.Bound)?.fullAddress
