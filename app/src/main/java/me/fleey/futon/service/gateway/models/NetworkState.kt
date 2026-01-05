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
 * Represents the current network connectivity state.
 */
sealed interface NetworkState {
  /**
   * Connected to a network with full details.
   * @param interfaceName Network interface name (wlan0, eth0, etc.)
   * @param ipAddress Current IP address on this interface
   * @param ssid WiFi SSID if connected to WiFi, null otherwise
   * @param isWifi True if this is a WiFi connection
   */
  data class Connected(
    val interfaceName: String,
    val ipAddress: String,
    val ssid: String? = null,
    val isWifi: Boolean = false,
  ) : NetworkState

  /**
   * Connected to cellular network - not suitable for LAN server.
   */
  data object Cellular : NetworkState

  data object Disconnected : NetworkState

  data object Unknown : NetworkState
}

val NetworkState.allowsServerBinding: Boolean
  get() = this is NetworkState.Connected && !interfaceName.startsWith("rmnet")

val NetworkState.interfaceName: String?
  get() = (this as? NetworkState.Connected)?.interfaceName
