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
package me.fleey.futon.service.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.fleey.futon.service.gateway.models.NetworkState
import org.koin.core.annotation.Single

/**
 * Manages the trusted WiFi network whitelist.
 * When the whitelist is empty, all WiFi networks are trusted (backward compatibility).
 */
interface TrustedNetworkManager {
  /** Set of trusted WiFi SSIDs */
  val trustedNetworks: StateFlow<Set<String>>

  val isCurrentNetworkTrusted: StateFlow<Boolean>

  /** Add a network to the trusted list */
  suspend fun addTrustedNetwork(ssid: String)

  suspend fun removeTrustedNetwork(ssid: String)

  suspend fun trustCurrentNetwork(): Boolean

  suspend fun clearTrustedNetworks()

  fun isTrusted(ssid: String?): Boolean
}

@Single(binds = [TrustedNetworkManager::class])
class TrustedNetworkManagerImpl(
  private val gatewayConfig: GatewayConfig,
  private val networkStateMonitor: NetworkStateMonitor,
) : TrustedNetworkManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _trustedNetworks = MutableStateFlow<Set<String>>(emptySet())
  override val trustedNetworks: StateFlow<Set<String>> = _trustedNetworks.asStateFlow()

  override val isCurrentNetworkTrusted: StateFlow<Boolean> = combine(
    networkStateMonitor.networkState,
    _trustedNetworks,
  ) { networkState, trusted ->
    when (networkState) {
      is NetworkState.Connected -> {
        // Ethernet is always trusted
        if (!networkState.isWifi) return@combine true
        // Empty whitelist means trust all WiFi
        if (trusted.isEmpty()) return@combine true
        networkState.ssid?.let { it in trusted } ?: false
      }
      // Cellular is never trusted for LAN server
      is NetworkState.Cellular -> false
      // No network or unknown state is not trusted
      else -> false
    }
  }.stateIn(
    scope = scope,
    started = SharingStarted.Eagerly,
    initialValue = false,
  )

  init {
    scope.launch {
      gatewayConfig.config.collect { config ->
        _trustedNetworks.value = config.trustedSsids
      }
    }
  }

  override suspend fun addTrustedNetwork(ssid: String) {
    if (ssid.isBlank()) return
    gatewayConfig.addTrustedSsid(ssid.trim())
  }

  override suspend fun removeTrustedNetwork(ssid: String) {
    gatewayConfig.removeTrustedSsid(ssid)
  }

  override suspend fun trustCurrentNetwork(): Boolean {
    val currentState = networkStateMonitor.networkState.value
    if (currentState !is NetworkState.Connected || !currentState.isWifi) {
      return false
    }

    val ssid = currentState.ssid ?: return false
    addTrustedNetwork(ssid)
    return true
  }

  override suspend fun clearTrustedNetworks() {
    gatewayConfig.setTrustedSsids(emptySet())
  }

  override fun isTrusted(ssid: String?): Boolean {
    val trusted = _trustedNetworks.value

    if (trusted.isEmpty()) return true

    if (ssid == null) return false
    return ssid in trusted
  }
}
