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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.fleey.futon.BuildConfig
import me.fleey.futon.service.gateway.models.DiscoveryState
import me.fleey.futon.service.gateway.models.UnregisteredReason
import org.koin.core.annotation.Single

/**
 * Manages mDNS/NSD service discovery for the LAN HTTP server.
 * Allows clients to discover the Futon server on the local network.
 */
interface ServiceDiscovery {
  /** Current discovery state */
  val discoveryState: StateFlow<DiscoveryState>

  /** Register the service with the given port */
  fun registerService(port: Int)

  fun unregisterService()

  fun updatePort(port: Int)
}

@Single(binds = [ServiceDiscovery::class])
class ServiceDiscoveryImpl(
  private val context: Context,
  private val gatewayConfig: GatewayConfig,
) : ServiceDiscovery {

  companion object {
    private const val TAG = "ServiceDiscovery"
    private const val SERVICE_TYPE = "_futon._tcp"
    private const val API_VERSION = "1"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

  private val _discoveryState = MutableStateFlow<DiscoveryState>(
    DiscoveryState.Unregistered(UnregisteredReason.NOT_STARTED),
  )
  override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

  private var currentPort: Int = 0
  private var isRegistered = false

  private val registrationListener = object : NsdManager.RegistrationListener {
    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
      Log.i(TAG, "Service registered: ${serviceInfo.serviceName}")
      _discoveryState.value = DiscoveryState.Registered(
        serviceName = serviceInfo.serviceName,
        serviceType = SERVICE_TYPE,
        port = currentPort,
      )
      isRegistered = true
    }

    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      Log.e(TAG, "Registration failed: errorCode=$errorCode")
      _discoveryState.value = DiscoveryState.Failed(
        errorCode = errorCode,
        message = getErrorMessage(errorCode),
      )
      isRegistered = false
    }

    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
      Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
      _discoveryState.value = DiscoveryState.Unregistered(UnregisteredReason.UNREGISTERED)
      isRegistered = false
    }

    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      Log.e(TAG, "Unregistration failed: errorCode=$errorCode")
      // Still mark as unregistered since we tried
      isRegistered = false
    }
  }

  init {
    scope.launch {
      gatewayConfig.config.collect { config ->
        if (!config.enableMdns && isRegistered) {
          unregisterService()
        }
      }
    }
  }

  override fun registerService(port: Int) {
    scope.launch {
      val config = gatewayConfig.config.first()
      if (!config.enableMdns) {
        Log.d(TAG, "mDNS disabled, not registering service")
        _discoveryState.value = DiscoveryState.Unregistered(UnregisteredReason.DISABLED)
        return@launch
      }

      if (isRegistered) {
        unregisterService()
      }

      currentPort = port
      _discoveryState.value = DiscoveryState.Registering

      val serviceInfo = NsdServiceInfo().apply {
        serviceName = generateServiceName()
        serviceType = SERVICE_TYPE
        setPort(port)

        // Add TXT records with service metadata
        setAttribute("version", BuildConfig.VERSION_NAME)
        setAttribute("api", API_VERSION)
      }

      try {
        nsdManager.registerService(
          serviceInfo,
          NsdManager.PROTOCOL_DNS_SD,
          registrationListener,
        )
        Log.i(TAG, "Registering service on port $port")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to register service", e)
        _discoveryState.value = DiscoveryState.Failed(
          errorCode = -1,
          message = e.message ?: "Unknown error",
        )
      }
    }
  }

  override fun unregisterService() {
    if (!isRegistered) return

    try {
      nsdManager.unregisterService(registrationListener)
      Log.i(TAG, "Unregistering service")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to unregister service", e)
    }
    isRegistered = false
  }

  override fun updatePort(port: Int) {
    if (currentPort == port && isRegistered) return

    if (isRegistered) {
      unregisterService()
    }
    registerService(port)
  }

  private fun generateServiceName(): String {
    // Generate a short device ID from Android ID
    val deviceId = try {
      android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID,
      )?.take(6) ?: "unknown"
    } catch (e: Exception) {
      "unknown"
    }
    return "futon-$deviceId"
  }

  private fun getErrorMessage(errorCode: Int): String {
    return when (errorCode) {
      NsdManager.FAILURE_ALREADY_ACTIVE -> "Service already active"
      NsdManager.FAILURE_INTERNAL_ERROR -> "Internal error"
      NsdManager.FAILURE_MAX_LIMIT -> "Maximum limit reached"
      else -> "Unknown error ($errorCode)"
    }
  }
}
