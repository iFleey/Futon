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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.fleey.futon.service.gateway.models.BindingState
import me.fleey.futon.service.gateway.models.NetworkState
import me.fleey.futon.service.gateway.models.UnboundReason
import org.koin.core.annotation.Single

/**
 * Callback interface for binding state changes.
 */
fun interface BindingChangeListener {
  fun onBindingChanged(oldState: BindingState, newState: BindingState)
}

/**
 * Smart IP binding strategy that selects the best network interface for the LAN server.
 * Priorities: wlan0 > eth0 > other non-cellular interfaces
 * Absolutely forbids binding to rmnet (cellular) interfaces.
 */
interface SmartBindingStrategy {
  /** Current binding state */
  val currentBinding: StateFlow<BindingState>

  /** Register a listener for binding changes */
  fun addBindingChangeListener(listener: BindingChangeListener)

  fun removeBindingChangeListener(listener: BindingChangeListener)

  suspend fun getBindingAddress(port: Int): BindingResult

  suspend fun rebind(port: Int): BindingResult

  fun markUnbound(reason: UnboundReason)
}

sealed interface BindingResult {
  data class Success(
    val interfaceName: String,
    val ipAddress: String,
    val port: Int,
  ) : BindingResult

  data class Failure(val reason: UnboundReason) : BindingResult
}

/**
 * Implementation of SmartBindingStrategy.
 */
@Single(binds = [SmartBindingStrategy::class])
class SmartBindingStrategyImpl(
  private val networkStateMonitor: NetworkStateMonitor,
  private val trustedNetworkManager: TrustedNetworkManager,
  private val gatewayConfig: GatewayConfig,
) : SmartBindingStrategy {

  companion object {
    private const val TAG = "SmartBindingStrategy"

    // Interface priority (lower = higher priority)
    private val INTERFACE_PRIORITY = mapOf(
      "wlan" to 1,
      "eth" to 2,
      "usb" to 3,
    )

    // Forbidden interface prefixes (cellular)
    private val FORBIDDEN_PREFIXES = setOf("rmnet", "ccmni", "pdp")
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val listeners = mutableSetOf<BindingChangeListener>()

  private val _currentBinding = MutableStateFlow<BindingState>(
    BindingState.Unbound(UnboundReason.NOT_STARTED),
  )
  override val currentBinding: StateFlow<BindingState> = _currentBinding.asStateFlow()

  private var lastPort: Int = GatewayConfigData.DEFAULT_SERVER_PORT

  init {
    scope.launch {
      combine(
        networkStateMonitor.networkState,
        trustedNetworkManager.isCurrentNetworkTrusted,
      ) { networkState, isTrusted ->
        Pair(networkState, isTrusted)
      }.collect { (networkState, isTrusted) ->
        handleNetworkChange(networkState, isTrusted)
      }
    }
  }

  override fun addBindingChangeListener(listener: BindingChangeListener) {
    listeners.add(listener)
  }

  override fun removeBindingChangeListener(listener: BindingChangeListener) {
    listeners.remove(listener)
  }

  override suspend fun getBindingAddress(port: Int): BindingResult {
    lastPort = port
    val networkState = networkStateMonitor.networkState.value
    val isTrusted = trustedNetworkManager.isCurrentNetworkTrusted.value

    return calculateBinding(networkState, isTrusted, port)
  }

  override suspend fun rebind(port: Int): BindingResult {
    lastPort = port
    networkStateMonitor.refresh()
    return getBindingAddress(port)
  }

  override fun markUnbound(reason: UnboundReason) {
    updateBinding(BindingState.Unbound(reason))
  }

  private fun handleNetworkChange(networkState: NetworkState, isTrusted: Boolean) {
    val currentState = _currentBinding.value

    // If we're currently bound, check if we need to rebind
    if (currentState is BindingState.Bound) {
      val result = calculateBinding(networkState, isTrusted, lastPort)
      when (result) {
        is BindingResult.Success -> {
          if (result.ipAddress != currentState.ipAddress ||
            result.interfaceName != currentState.interfaceName
          ) {
            // IP or interface changed, need to rebind
            updateBinding(BindingState.Rebinding(currentState.interfaceName))
            updateBinding(
              BindingState.Bound(
                interfaceName = result.interfaceName,
                ipAddress = result.ipAddress,
                port = result.port,
              ),
            )
          }
        }

        is BindingResult.Failure -> {
          updateBinding(BindingState.Unbound(result.reason))
        }
      }
    }
  }

  private fun calculateBinding(
    networkState: NetworkState,
    isTrusted: Boolean,
    port: Int,
  ): BindingResult {
    return when (networkState) {
      is NetworkState.Connected -> {
        // Check if interface is forbidden (cellular)
        if (isForbiddenInterface(networkState.interfaceName)) {
          Log.w(TAG, "Refusing to bind to cellular interface: ${networkState.interfaceName}")
          return BindingResult.Failure(UnboundReason.CELLULAR_ONLY)
        }

        if (!isTrusted) {
          Log.w(TAG, "Network not trusted: ${networkState.ssid}")
          return BindingResult.Failure(UnboundReason.UNTRUSTED_NETWORK)
        }

        BindingResult.Success(
          interfaceName = networkState.interfaceName,
          ipAddress = networkState.ipAddress,
          port = port,
        )
      }

      is NetworkState.Cellular -> {
        BindingResult.Failure(UnboundReason.CELLULAR_ONLY)
      }

      is NetworkState.Disconnected -> {
        BindingResult.Failure(UnboundReason.NO_SUITABLE_INTERFACE)
      }

      is NetworkState.Unknown -> {
        BindingResult.Failure(UnboundReason.NO_SUITABLE_INTERFACE)
      }
    }
  }

  private fun isForbiddenInterface(interfaceName: String): Boolean {
    return FORBIDDEN_PREFIXES.any { interfaceName.startsWith(it) }
  }

  private fun updateBinding(newState: BindingState) {
    val oldState = _currentBinding.value
    if (oldState != newState) {
      _currentBinding.value = newState
      Log.i(TAG, "Binding state changed: $oldState -> $newState")
      notifyListeners(oldState, newState)
    }
  }

  private fun notifyListeners(oldState: BindingState, newState: BindingState) {
    listeners.forEach { listener ->
      try {
        listener.onBindingChanged(oldState, newState)
      } catch (e: Exception) {
        Log.e(TAG, "Error notifying binding change listener", e)
      }
    }
  }
}
