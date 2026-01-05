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

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.fleey.futon.service.gateway.models.NetworkState
import org.koin.core.annotation.Single
import java.net.Inet4Address

interface NetworkStateMonitor {
  val networkState: StateFlow<NetworkState>
  fun startMonitoring()
  fun stopMonitoring()
  fun refresh()
}

@Single(binds = [NetworkStateMonitor::class])
class NetworkStateMonitorImpl(
  private val context: Context,
) : NetworkStateMonitor {

  companion object {
    private const val TAG = "NetworkStateMonitor"
  }

  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Unknown)
  override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

  private var isMonitoring = false
  private var wifiCallback: NetworkCallback? = null
  private var defaultCallback: NetworkCallback? = null

  override fun startMonitoring() {
    if (isMonitoring) return
    isMonitoring = true

    registerWifiCallback()
    registerDefaultCallback()

    Log.i(TAG, "Started network monitoring")
  }

  private fun registerWifiCallback() {
    val wifiRequest = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .build()

    val callback = createWifiCallback()
    wifiCallback = callback

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        connectivityManager.registerNetworkCallback(
          wifiRequest,
          callback,
          android.os.Handler(android.os.Looper.getMainLooper()),
        )
      } else {
        connectivityManager.registerNetworkCallback(wifiRequest, callback)
      }
      Log.d(TAG, "WiFi callback registered")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to register WiFi callback", e)
    }
  }

  private fun registerDefaultCallback() {
    val defaultRequest = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

    val callback = object : NetworkCallback() {
      override fun onAvailable(network: Network) {
        Log.d(TAG, "Default network available: $network")
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
          updateNonWifiState(network, caps)
        }
      }

      override fun onLost(network: Network) {
        Log.d(TAG, "Default network lost: $network")
        if (connectivityManager.activeNetwork == null) {
          _networkState.value = NetworkState.Disconnected
        }
      }
    }
    defaultCallback = callback

    try {
      connectivityManager.registerNetworkCallback(defaultRequest, callback)
      Log.d(TAG, "Default callback registered")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to register default callback", e)
    }
  }

  private fun createWifiCallback(): NetworkCallback {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onAvailable(network: Network) {
          Log.d(TAG, "WiFi network available: $network")
        }

        override fun onLost(network: Network) {
          Log.d(TAG, "WiFi network lost: $network")
          checkCurrentNetwork()
        }

        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities,
        ) {
          Log.d(TAG, "WiFi capabilities changed")
          updateWifiState(network, networkCapabilities)
        }
      }
    } else {
      object : NetworkCallback() {
        override fun onAvailable(network: Network) {
          Log.d(TAG, "WiFi network available: $network")
        }

        override fun onLost(network: Network) {
          Log.d(TAG, "WiFi network lost: $network")
          checkCurrentNetwork()
        }

        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities,
        ) {
          Log.d(TAG, "WiFi capabilities changed")
          updateWifiState(network, networkCapabilities)
        }
      }
    }
  }

  override fun stopMonitoring() {
    if (!isMonitoring) return

    try {
      wifiCallback?.let {
        connectivityManager.unregisterNetworkCallback(it)
        wifiCallback = null
      }
      defaultCallback?.let {
        connectivityManager.unregisterNetworkCallback(it)
        defaultCallback = null
      }
      isMonitoring = false
      Log.i(TAG, "Stopped network monitoring")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to unregister network callbacks", e)
    }
  }

  override fun refresh() {
    Log.d(TAG, "Refreshing network state")
    stopMonitoring()
    startMonitoring()
  }

  private fun checkCurrentNetwork() {
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork == null) {
      _networkState.value = NetworkState.Disconnected
      return
    }

    val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
    if (caps == null) {
      _networkState.value = NetworkState.Unknown
      return
    }

    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      updateWifiState(activeNetwork, caps)
    } else {
      updateNonWifiState(activeNetwork, caps)
    }
  }

  private fun updateWifiState(network: Network, capabilities: NetworkCapabilities) {
    val linkProperties = connectivityManager.getLinkProperties(network)
    if (linkProperties == null) {
      Log.w(TAG, "LinkProperties is null for WiFi network")
      return
    }

    val interfaceName = linkProperties.interfaceName ?: "wlan0"
    val ipAddress = getIpv4Address(linkProperties)
    val ssid = extractSsid(capabilities)

    Log.d(TAG, "WiFi state update: interface=$interfaceName, ip=$ipAddress, ssid=$ssid")

    if (ipAddress != null) {
      val newState = NetworkState.Connected(
        interfaceName = interfaceName,
        ipAddress = ipAddress,
        ssid = ssid,
        isWifi = true,
      )
      if (_networkState.value != newState) {
        Log.i(TAG, "Network state changed to: $newState")
        _networkState.value = newState
      }
    }
  }

  private fun updateNonWifiState(network: Network, capabilities: NetworkCapabilities) {
    val linkProperties = connectivityManager.getLinkProperties(network)

    val newState = when {
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
        val interfaceName = linkProperties?.interfaceName ?: "eth0"
        val ipAddress = linkProperties?.let { getIpv4Address(it) }
        if (ipAddress != null) {
          NetworkState.Connected(
            interfaceName = interfaceName,
            ipAddress = ipAddress,
            ssid = null,
            isWifi = false,
          )
        } else {
          NetworkState.Unknown
        }
      }

      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
        NetworkState.Cellular
      }

      else -> NetworkState.Unknown
    }

    if (_networkState.value != newState) {
      Log.i(TAG, "Network state changed to: $newState")
      _networkState.value = newState
    }
  }

  private fun getIpv4Address(linkProperties: LinkProperties): String? {
    return linkProperties.linkAddresses
      .map { it.address }
      .filterIsInstance<Inet4Address>()
      .firstOrNull { !it.isLoopbackAddress }
      ?.hostAddress
  }

  private fun extractSsid(capabilities: NetworkCapabilities): String? {
    return try {
      val wifiInfo = capabilities.transportInfo as? WifiInfo
      if (wifiInfo == null) {
        Log.w(TAG, "transportInfo is null or not WifiInfo")
        return null
      }

      val ssid = wifiInfo.ssid
      Log.d(TAG, "Raw SSID from WifiInfo: '$ssid'")

      if (ssid == null) {
        Log.w(TAG, "SSID is null")
        return null
      }

      val cleanSsid = ssid.removeSurrounding("\"")
      if (cleanSsid == "<unknown ssid>" || cleanSsid.isBlank()) {
        Log.w(TAG, "SSID is unknown or blank: '$cleanSsid'")
        return null
      }

      Log.i(TAG, "Successfully extracted SSID: '$cleanSsid'")
      cleanSsid
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract SSID", e)
      null
    }
  }
}
