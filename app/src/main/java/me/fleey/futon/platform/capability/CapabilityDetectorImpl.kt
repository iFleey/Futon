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
package me.fleey.futon.platform.capability

import me.fleey.futon.SystemStatus
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.platform.capability.models.CapabilityErrorType
import me.fleey.futon.platform.capability.models.CapabilityQueryResult
import me.fleey.futon.platform.capability.models.DeviceCapabilities
import me.fleey.futon.platform.capability.models.InputDeviceAccess
import me.fleey.futon.platform.capability.models.RootStatus
import me.fleey.futon.platform.capability.models.SELinuxMode
import me.fleey.futon.platform.capability.models.SELinuxStatus
import me.fleey.futon.platform.root.RootType
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.milliseconds

/**
 * Daemon-based implementation of CapabilityDetector.
 */
@Single(binds = [CapabilityDetector::class])
class CapabilityDetectorImpl(
  private val daemonRepository: DaemonRepository,
) : CapabilityDetector {

  private var cachedCapabilities: DeviceCapabilities? = null
  private var cacheTimestamp: Long = 0
  private val cacheTtlMs = 5000L

  private var cachedDaemonStateClass: Class<out DaemonState>? = null

  override suspend fun detectAll(): DeviceCapabilities {
    return when (val result = detectAllWithStatus()) {
      is CapabilityQueryResult.Fresh -> result.capabilities
      is CapabilityQueryResult.Stale -> result.capabilities
      is CapabilityQueryResult.Error -> result.lastKnown ?: createStoppedCapabilities()
    }
  }

  override suspend fun detectAllWithStatus(): CapabilityQueryResult {
    val cached = cachedCapabilities
    val now = System.currentTimeMillis()
    val cacheAge = now - cacheTimestamp
    val currentStateClass = daemonRepository.daemonState.value::class.java

    // Invalidate cache if daemon state changed (e.g., from Stopped to Ready)
    val stateChanged = cachedDaemonStateClass != null && cachedDaemonStateClass != currentStateClass

    if (cached != null && cacheAge < cacheTtlMs && !stateChanged) {
      return CapabilityQueryResult.Fresh(cached)
    }

    return queryCapabilitiesWithStatus(cached, cacheAge)
  }

  override suspend fun refresh() {
    cachedCapabilities = null
    cacheTimestamp = 0
    cachedDaemonStateClass = null
    detectAll()
  }

  override fun getCached(): DeviceCapabilities? = cachedCapabilities

  override suspend fun checkRootAccess(): RootStatus {
    return detectAll().rootStatus
  }

  override suspend fun checkSELinuxStatus(): SELinuxStatus {
    return detectAll().seLinuxStatus
  }

  override suspend fun checkInputDeviceAccess(): InputDeviceAccess {
    return detectAll().inputDeviceAccess
  }

  private suspend fun queryCapabilitiesWithStatus(
    cached: DeviceCapabilities?,
    cacheAgeMs: Long,
  ): CapabilityQueryResult {
    return when (val state = daemonRepository.daemonState.value) {
      is DaemonState.Ready -> queryDaemonCapabilitiesWithStatus(cached, cacheAgeMs)
      is DaemonState.Connecting -> createPendingResult(
        "Connecting to daemon...",
        cached,
        cacheAgeMs,
      )

      is DaemonState.Authenticating -> createPendingResult(
        "Authenticating with daemon...",
        cached,
        cacheAgeMs,
      )

      is DaemonState.Reconciling -> createPendingResult(
        "Reconciling daemon state...",
        cached,
        cacheAgeMs,
      )

      is DaemonState.Starting -> createPendingResult("Starting daemon...", cached, cacheAgeMs)
      is DaemonState.Error -> createErrorResult(state, cached)
      is DaemonState.Stopped -> createStoppedResult(cached)
    }
  }

  private suspend fun queryDaemonCapabilitiesWithStatus(
    cached: DeviceCapabilities?,
    cacheAgeMs: Long,
  ): CapabilityQueryResult {
    val systemStatusResult = daemonRepository.getSystemStatus()
    val daemonState = daemonRepository.daemonState.value
    val capabilityFlags = if (daemonState is DaemonState.Ready) daemonState.capabilities else 0

    return systemStatusResult.fold(
      onSuccess = { status ->
        val capabilities = createCapabilitiesFromSystemStatus(status, capabilityFlags)
        cachedCapabilities = capabilities
        cacheTimestamp = System.currentTimeMillis()
        cachedDaemonStateClass = daemonState::class.java
        CapabilityQueryResult.Fresh(capabilities)
      },
      onFailure = { error ->
        if (cached != null) {
          CapabilityQueryResult.Stale(
            capabilities = cached,
            age = cacheAgeMs.milliseconds,
            reason = "Failed to query daemon: ${error.message}",
          )
        } else {
          CapabilityQueryResult.Error(
            message = "Failed to query daemon system status: ${error.message}",
            errorType = CapabilityErrorType.DAEMON_ERROR,
            lastKnown = null,
          )
        }
      },
    )
  }

  private fun createPendingResult(
    message: String,
    cached: DeviceCapabilities?,
    cacheAgeMs: Long,
  ): CapabilityQueryResult {
    return if (cached != null) {
      CapabilityQueryResult.Stale(
        capabilities = cached,
        age = cacheAgeMs.milliseconds,
        reason = message,
      )
    } else {
      val pendingCapabilities = createPendingCapabilities(message)
      CapabilityQueryResult.Fresh(pendingCapabilities)
    }
  }

  private fun createErrorResult(
    errorState: DaemonState.Error,
    cached: DeviceCapabilities?,
  ): CapabilityQueryResult {
    val errorType = mapErrorCodeToCapabilityErrorType(errorState.code)
    val message = formatErrorMessage(errorState)

    return CapabilityQueryResult.Error(
      message = message,
      errorType = errorType,
      lastKnown = cached,
    )
  }

  private fun createStoppedResult(cached: DeviceCapabilities?): CapabilityQueryResult {
    // Clear cache when daemon is stopped so next query will be fresh
    cachedCapabilities = null
    cacheTimestamp = 0
    cachedDaemonStateClass = DaemonState.Stopped::class.java

    return CapabilityQueryResult.Error(
      message = "Daemon not running - start via root shell",
      errorType = CapabilityErrorType.DAEMON_NOT_RUNNING,
      lastKnown = cached,
    )
  }

  private fun mapErrorCodeToCapabilityErrorType(code: ErrorCode): CapabilityErrorType {
    return when {
      ErrorCode.isConnectionError(code.code) -> CapabilityErrorType.CONNECTION_FAILED
      ErrorCode.isAuthError(code.code) -> CapabilityErrorType.AUTHENTICATION_FAILED
      code == ErrorCode.CONNECTION_TIMEOUT -> CapabilityErrorType.TIMEOUT
      ErrorCode.isRuntimeError(code.code) -> CapabilityErrorType.DAEMON_ERROR
      else -> CapabilityErrorType.UNKNOWN
    }
  }

  private fun formatErrorMessage(errorState: DaemonState.Error): String {
    return when (errorState.code) {
      ErrorCode.AUTH_FAILED -> "Authentication failed: ${errorState.message}"
      ErrorCode.AUTH_CHALLENGE_FAILED -> "Challenge-response authentication failed"
      ErrorCode.AUTH_SIGNATURE_INVALID -> "Invalid signature - app may need reinstall"
      ErrorCode.AUTH_KEY_NOT_FOUND -> "Authentication key not found"
      ErrorCode.AUTH_SESSION_EXPIRED -> "Session expired - reconnecting..."
      ErrorCode.AUTH_SESSION_CONFLICT -> "Session conflict - another instance may be connected"
      ErrorCode.AUTH_ATTESTATION_FAILED -> "Attestation verification failed"
      ErrorCode.AUTH_ATTESTATION_MISMATCH -> "Attestation mismatch - device integrity check failed"
      ErrorCode.AUTH_KEY_CORRUPTED -> "Authentication key corrupted - regenerating..."
      ErrorCode.CONNECTION_FAILED -> "Failed to connect to daemon"
      ErrorCode.SERVICE_NOT_FOUND -> "Daemon service not found - is it running?"
      ErrorCode.BINDER_DIED -> "Daemon connection lost"
      ErrorCode.CONNECTION_TIMEOUT -> "Connection timed out"
      ErrorCode.RECONNECTION_EXHAUSTED -> "Failed to reconnect after multiple attempts"
      ErrorCode.SECURITY_UNAUTHORIZED -> "Unauthorized - UID mismatch or security violation"
      else -> "Daemon error: ${errorState.message}"
    }
  }

  private fun createCapabilitiesFromSystemStatus(
    status: SystemStatus,
    capabilityFlags: Int,
  ): DeviceCapabilities {
    return DeviceCapabilities(
      rootStatus = RootStatus(
        isAvailable = status.rootAvailable,
        rootType = parseRootType(status.rootType),
        version = status.rootVersion?.takeIf { it.isNotEmpty() },
      ),
      seLinuxStatus = SELinuxStatus(
        mode = parseSELinuxMode(status.selinuxMode),
        inputAccessAllowed = status.inputAccessAllowed,
        suggestedPolicy = null,
      ),
      inputDeviceAccess = InputDeviceAccess(
        canAccessDevInput = status.canAccessDevInput,
        touchDevicePath = status.touchDevicePath?.takeIf { it.isNotEmpty() },
        maxTouchPoints = status.maxTouchPoints.coerceAtLeast(1),
        error = status.inputError?.takeIf { it.isNotEmpty() },
      ),
      daemonCapabilities = capabilityFlags,
      timestamp = System.currentTimeMillis(),
    )
  }

  private fun parseRootType(rootType: String?): RootType {
    return when (rootType?.lowercase()) {
      "magisk" -> RootType.MAGISK
      "kernelsu", "ksu" -> RootType.KSU
      "ksu_next" -> RootType.KSU_NEXT
      "sukisu_ultra" -> RootType.SUKISU_ULTRA
      "apatch" -> RootType.APATCH
      "supersu" -> RootType.SUPERSU
      "su" -> RootType.OTHER
      "none", null, "" -> RootType.NONE
      else -> RootType.OTHER
    }
  }

  private fun parseSELinuxMode(mode: Int): SELinuxMode {
    return when (mode) {
      0 -> SELinuxMode.UNKNOWN
      1 -> SELinuxMode.DISABLED
      2 -> SELinuxMode.PERMISSIVE
      3 -> SELinuxMode.ENFORCING
      else -> SELinuxMode.UNKNOWN
    }
  }

  private fun createPendingCapabilities(message: String): DeviceCapabilities {
    return DeviceCapabilities(
      rootStatus = RootStatus(
        isAvailable = false,
        rootType = RootType.NONE,
        version = null,
      ),
      seLinuxStatus = SELinuxStatus(
        mode = SELinuxMode.UNKNOWN,
        inputAccessAllowed = false,
        suggestedPolicy = null,
      ),
      inputDeviceAccess = InputDeviceAccess(
        canAccessDevInput = false,
        touchDevicePath = null,
        maxTouchPoints = 1,
        error = message,
      ),
      daemonCapabilities = 0,
      timestamp = System.currentTimeMillis(),
    )
  }

  private fun createStoppedCapabilities(): DeviceCapabilities {
    return DeviceCapabilities(
      rootStatus = RootStatus(
        isAvailable = false,
        rootType = RootType.NONE,
        version = null,
      ),
      seLinuxStatus = SELinuxStatus(
        mode = SELinuxMode.UNKNOWN,
        inputAccessAllowed = false,
        suggestedPolicy = null,
      ),
      inputDeviceAccess = InputDeviceAccess(
        canAccessDevInput = false,
        touchDevicePath = null,
        maxTouchPoints = 1,
        error = "Daemon not running - start via root shell",
      ),
      daemonCapabilities = 0,
      timestamp = System.currentTimeMillis(),
    )
  }
}
