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
package me.fleey.futon.ui.feature.settings.integration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.service.gateway.GatewayConfig
import me.fleey.futon.service.gateway.GatewayConfigData
import me.fleey.futon.service.gateway.IdleTimeoutManager
import me.fleey.futon.service.gateway.LanHttpServer
import me.fleey.futon.service.gateway.NetworkStateMonitor
import me.fleey.futon.service.gateway.ServiceDiscovery
import me.fleey.futon.service.gateway.SmartBindingStrategy
import me.fleey.futon.service.gateway.TlsManager
import me.fleey.futon.service.gateway.TokenManager
import me.fleey.futon.service.gateway.TrustedNetworkManager
import me.fleey.futon.service.gateway.WifiScanner
import me.fleey.futon.service.gateway.models.BindingState
import me.fleey.futon.service.gateway.models.DiscoveryState
import me.fleey.futon.service.gateway.models.NetworkState
import me.fleey.futon.service.gateway.models.ServerState
import me.fleey.futon.service.gateway.models.StopReason
import me.fleey.futon.service.gateway.models.TlsState
import me.fleey.futon.service.gateway.models.UnboundReason
import me.fleey.futon.service.gateway.models.UnregisteredReason
import org.koin.android.annotation.KoinViewModel
import kotlin.time.Duration

data class IntegrationsUiState(
  val config: GatewayConfigData = GatewayConfigData(),
  val networkState: NetworkState = NetworkState.Unknown,
  val serverState: ServerState = ServerState.Stopped(StopReason.NOT_STARTED),
  val bindingState: BindingState = BindingState.Unbound(UnboundReason.NOT_STARTED),
  val discoveryState: DiscoveryState = DiscoveryState.Unregistered(UnregisteredReason.NOT_STARTED),
  val tlsState: TlsState = TlsState.Disabled,
  val lanToken: String = "",
  val taskerToken: String = "",
  val trustedNetworks: Set<String> = emptySet(),
  val isCurrentNetworkTrusted: Boolean = false,
  val currentSsid: String? = null,
  val availableWifiNetworks: List<String> = emptyList(),
  val isScanning: Boolean = false,
  val idleTimeRemaining: Duration? = null,
  val rateLimitedIps: Set<String> = emptySet(),
  val isTokenCopied: Boolean = false,
  val showCertImportNotAvailable: Boolean = false,
  val isSaving: Boolean = false,
)

sealed interface IntegrationsUiEvent {
  data class SetServerEnabled(val enabled: Boolean) : IntegrationsUiEvent
  data class SetServerPort(val port: Int) : IntegrationsUiEvent
  data class SetIdleTimeoutHours(val hours: Int) : IntegrationsUiEvent
  data class SetRateLimitQps(val qps: Int) : IntegrationsUiEvent
  data class SetAutoRotateToken(val enabled: Boolean) : IntegrationsUiEvent
  data class SetTokenRotationIntervalDays(val days: Int) : IntegrationsUiEvent
  data class SetEnableMdns(val enabled: Boolean) : IntegrationsUiEvent
  data class SetEnableTls(val enabled: Boolean) : IntegrationsUiEvent
  data object RegenerateLanToken : IntegrationsUiEvent
  data object RegenerateTaskerToken : IntegrationsUiEvent
  data object AddCurrentNetworkToTrusted : IntegrationsUiEvent
  data class AddNetworkToTrusted(val ssid: String) : IntegrationsUiEvent
  data class RemoveTrustedNetwork(val ssid: String) : IntegrationsUiEvent
  data object RefreshNetworkState : IntegrationsUiEvent
  data object ScanWifiNetworks : IntegrationsUiEvent
  data object ClearTrustedNetworks : IntegrationsUiEvent
  data object ImportTlsCertificate : IntegrationsUiEvent
  data object DismissCertImportNotAvailable : IntegrationsUiEvent
  data object ClearRateLimitedIps : IntegrationsUiEvent
  data object DismissTokenCopied : IntegrationsUiEvent
}

@KoinViewModel
class IntegrationsSettingsViewModel(
  private val gatewayConfig: GatewayConfig,
  private val networkStateMonitor: NetworkStateMonitor,
  private val lanHttpServer: LanHttpServer,
  private val tokenManager: TokenManager,
  private val trustedNetworkManager: TrustedNetworkManager,
  private val idleTimeoutManager: IdleTimeoutManager,
  private val serviceDiscovery: ServiceDiscovery,
  private val tlsManager: TlsManager,
  private val smartBindingStrategy: SmartBindingStrategy,
  private val wifiScanner: WifiScanner,
) : ViewModel() {

  private val _localState = MutableStateFlow(IntegrationsUiState())

  val uiState: StateFlow<IntegrationsUiState> = combine(
    gatewayConfig.config,
    networkStateMonitor.networkState,
    lanHttpServer.serverState,
    smartBindingStrategy.currentBinding,
    tokenManager.lanToken,
    tokenManager.taskerToken,
    trustedNetworkManager.trustedNetworks,
    trustedNetworkManager.isCurrentNetworkTrusted,
    idleTimeoutManager.idleTimeRemaining,
    serviceDiscovery.discoveryState,
    tlsManager.tlsState,
    _localState,
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    val config = values[0] as GatewayConfigData
    val network = values[1] as NetworkState
    val server = values[2] as ServerState
    val binding = values[3] as BindingState
    val lanToken = values[4] as String
    val taskerToken = values[5] as String
    val trusted = values[6] as Set<String>
    val isTrusted = values[7] as Boolean
    val idleTime = values[8] as Duration?
    val discovery = values[9] as DiscoveryState
    val tls = values[10] as TlsState
    val local = values[11] as IntegrationsUiState

    local.copy(
      config = config,
      networkState = network,
      serverState = server,
      bindingState = binding,
      lanToken = lanToken,
      taskerToken = taskerToken,
      trustedNetworks = trusted,
      isCurrentNetworkTrusted = isTrusted,
      currentSsid = (network as? NetworkState.Connected)?.ssid,
      idleTimeRemaining = idleTime,
      discoveryState = discovery,
      tlsState = tls,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = IntegrationsUiState(),
  )

  init {
    viewModelScope.launch {
      networkStateMonitor.refresh()
      delay(100)
      scanWifiNetworks()
    }
  }

  fun onEvent(event: IntegrationsUiEvent) {
    when (event) {
      is IntegrationsUiEvent.SetServerEnabled -> setServerEnabled(event.enabled)
      is IntegrationsUiEvent.SetServerPort -> setServerPort(event.port)
      is IntegrationsUiEvent.SetIdleTimeoutHours -> setIdleTimeoutHours(event.hours)
      is IntegrationsUiEvent.SetRateLimitQps -> setRateLimitQps(event.qps)
      is IntegrationsUiEvent.SetAutoRotateToken -> setAutoRotateToken(event.enabled)
      is IntegrationsUiEvent.SetTokenRotationIntervalDays -> setTokenRotationIntervalDays(event.days)
      is IntegrationsUiEvent.SetEnableMdns -> setEnableMdns(event.enabled)
      is IntegrationsUiEvent.SetEnableTls -> setEnableTls(event.enabled)
      IntegrationsUiEvent.RegenerateLanToken -> regenerateLanToken()
      IntegrationsUiEvent.RegenerateTaskerToken -> regenerateTaskerToken()
      IntegrationsUiEvent.AddCurrentNetworkToTrusted -> addCurrentNetworkToTrusted()
      is IntegrationsUiEvent.AddNetworkToTrusted -> addNetworkToTrusted(event.ssid)
      is IntegrationsUiEvent.RemoveTrustedNetwork -> removeTrustedNetwork(event.ssid)
      IntegrationsUiEvent.ClearTrustedNetworks -> clearTrustedNetworks()
      IntegrationsUiEvent.ImportTlsCertificate -> importTlsCertificate()
      IntegrationsUiEvent.DismissCertImportNotAvailable -> dismissCertImportNotAvailable()
      IntegrationsUiEvent.ClearRateLimitedIps -> clearRateLimitedIps()
      IntegrationsUiEvent.DismissTokenCopied -> dismissTokenCopied()
      IntegrationsUiEvent.RefreshNetworkState -> refreshNetworkState()
      IntegrationsUiEvent.ScanWifiNetworks -> scanWifiNetworks()
    }
  }

  private fun refreshNetworkState() {
    networkStateMonitor.refresh()
  }

  private fun setServerEnabled(enabled: Boolean) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setServerEnabled(enabled)
      if (enabled) {
        lanHttpServer.start()
      } else {
        lanHttpServer.stop()
      }
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setServerPort(port: Int) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setServerPort(port)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setIdleTimeoutHours(hours: Int) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setIdleTimeoutHours(hours)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setRateLimitQps(qps: Int) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setRateLimitQps(qps)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setAutoRotateToken(enabled: Boolean) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setAutoRotateTokenOnIpChange(enabled)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setTokenRotationIntervalDays(days: Int) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setTokenRotationIntervalDays(days)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setEnableMdns(enabled: Boolean) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setEnableMdns(enabled)
      val config = uiState.value.config
      if (enabled) {
        serviceDiscovery.registerService(config.serverPort)
      } else {
        serviceDiscovery.unregisterService()
      }
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun setEnableTls(enabled: Boolean) {
    viewModelScope.launch {
      _localState.update { it.copy(isSaving = true) }
      gatewayConfig.setEnableTls(enabled)
      _localState.update { it.copy(isSaving = false) }
    }
  }

  private fun regenerateLanToken() {
    viewModelScope.launch {
      tokenManager.regenerateLanToken()
      _localState.update { it.copy(isTokenCopied = true) }
    }
  }

  private fun regenerateTaskerToken() {
    viewModelScope.launch {
      tokenManager.regenerateTaskerToken()
      _localState.update { it.copy(isTokenCopied = true) }
    }
  }

  private fun addCurrentNetworkToTrusted() {
    viewModelScope.launch {
      val ssid = (uiState.value.networkState as? NetworkState.Connected)?.ssid
      if (ssid != null) {
        trustedNetworkManager.addTrustedNetwork(ssid)
      }
    }
  }

  private fun addNetworkToTrusted(ssid: String) {
    viewModelScope.launch {
      trustedNetworkManager.addTrustedNetwork(ssid)
    }
  }

  private fun scanWifiNetworks() {
    viewModelScope.launch {
      _localState.update { it.copy(isScanning = true) }
      wifiScanner.scanWifiNetworks().collect { networks ->
        _localState.update { it.copy(availableWifiNetworks = networks, isScanning = false) }
      }
    }
  }

  private fun removeTrustedNetwork(ssid: String) {
    viewModelScope.launch {
      trustedNetworkManager.removeTrustedNetwork(ssid)
    }
  }

  private fun clearTrustedNetworks() {
    viewModelScope.launch {
      trustedNetworkManager.clearTrustedNetworks()
    }
  }

  private fun importTlsCertificate() {
    _localState.update { it.copy(showCertImportNotAvailable = true) }
  }

  private fun clearRateLimitedIps() {
    // Clear rate limited IPs through the rate limiter
  }

  private fun dismissTokenCopied() {
    _localState.update { it.copy(isTokenCopied = false) }
  }

  private fun dismissCertImportNotAvailable() {
    _localState.update { it.copy(showCertImportNotAvailable = false) }
  }
}
