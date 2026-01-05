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
package me.fleey.futon.platform.input.strategy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.CapabilityFlags
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.platform.input.injector.AndroidInputInjector
import me.fleey.futon.platform.input.injector.DaemonInputInjector
import me.fleey.futon.platform.input.injector.InputInjector
import me.fleey.futon.platform.input.injector.NativeInputInjector
import me.fleey.futon.platform.input.injector.ShellInputInjector
import me.fleey.futon.platform.input.models.GestureErrorCode
import me.fleey.futon.platform.input.models.GestureResult
import me.fleey.futon.platform.input.models.InputMethod
import me.fleey.futon.platform.input.models.MTProtocol
import me.fleey.futon.platform.input.util.InputDeviceDiscovery
import me.fleey.futon.platform.root.RootShell

/**
 * Implementation of InputStrategyFactory that manages input injection strategies.
 * Root-Only architecture: detects available root-based methods and provides fallback logic.
 *
 * @param daemonRepository Repository for daemon state and capabilities
 * @param context Application context for daemon injector
 * @param rootShell Root shell for shell-based injection
 * @param deviceDiscovery Device discovery for finding touchscreen
 */
import org.koin.core.annotation.Single

@Single(binds = [InputStrategyFactory::class])
class InputStrategyFactoryImpl(
  private val daemonRepository: DaemonRepository,
  private val context: Context,
  private val rootShell: RootShell,
  private val deviceDiscovery: InputDeviceDiscovery,
) : InputStrategyFactory {

  companion object {
    private const val TAG = "InputStrategyFactory"
  }

  private var nativeInjector: NativeInputInjector? = null
  private var daemonInjector: DaemonInputInjector? = null
  private var androidInputInjector: AndroidInputInjector? = null
  private var shellInjector: ShellInputInjector? = null

  private val capabilitiesMutex = Mutex()
  private var cachedCapabilities: InputCapabilities? = null
  private var preferredMethod: InputMethod? = null
  private var currentMethod: InputMethod? = null
  private var fallbackEnabled: Boolean = true

  private val _currentMethodFlow = MutableStateFlow<InputMethod?>(null)
  private val _capabilitiesFlow = MutableStateFlow(InputCapabilities.EMPTY)

  override suspend fun detectCapabilities(): InputCapabilities = withContext(Dispatchers.IO) {
    capabilitiesMutex.withLock {
      cachedCapabilities?.let { cached ->
        if (cached.isValid()) {
          Log.d(TAG, "Returning cached capabilities")
          return@withContext cached
        }
      }

      Log.d(TAG, "Detecting input capabilities...")
      val capabilities = detectCapabilitiesInternal()
      cachedCapabilities = capabilities
      _capabilitiesFlow.value = capabilities

      Log.d(
        TAG,
        "Capabilities detected: native=${capabilities.nativeAvailable}, " +
          "android=${capabilities.androidInputAvailable}, " +
          "shell=${capabilities.shellAvailable}",
      )

      if (currentMethod == null && capabilities.hasAnyMethod()) {
        capabilities.getBestMethod()?.let { bestMethod ->
          updateCurrentMethod(bestMethod)
          Log.d(TAG, "Auto-selected active method: $bestMethod")
        }
      }

      capabilities
    }
  }

  override suspend fun refreshCapabilities() {
    capabilitiesMutex.withLock {
      cachedCapabilities = null
    }

    nativeInjector?.release()
    nativeInjector = null

    daemonInjector?.release()
    daemonInjector = null

    detectCapabilities()
  }

  override suspend fun getAvailableMethods(): List<InputMethod> {
    return detectCapabilities().getAvailableMethods()
  }

  override suspend fun selectBestMethod(): InputInjector {
    val capabilities = detectCapabilities()

    preferredMethod?.let { preferred ->
      val injector = getInjectorIfAvailable(preferred, capabilities)
      if (injector != null) {
        updateCurrentMethod(preferred)
        Log.d(TAG, "Using preferred method: $preferred")
        return injector
      }
      Log.w(TAG, "Preferred method $preferred not available, falling back to auto-selection")
    }

    val bestMethod = capabilities.getBestMethod()
      ?: throw IllegalStateException(
        "No input method available. Root access is required for input injection.",
      )

    val injector = getInjectorIfAvailable(bestMethod, capabilities)
      ?: throw IllegalStateException("Failed to get injector for $bestMethod")

    updateCurrentMethod(bestMethod)
    Log.d(TAG, "Auto-selected method: $bestMethod")
    return injector
  }

  override suspend fun getInjector(method: InputMethod): InputInjector? {
    val capabilities = detectCapabilities()
    return getInjectorIfAvailable(method, capabilities)
  }

  override fun setPreferredMethod(method: InputMethod?) {
    Log.d(TAG, "Setting preferred method: $method")
    preferredMethod = method
  }

  override fun getPreferredMethod(): InputMethod? = preferredMethod

  override fun getCurrentMethod(): InputMethod? = currentMethod

  override suspend fun executeWithFallback(
    action: suspend (InputInjector) -> GestureResult,
  ): GestureResult {
    val capabilities = detectCapabilities()
    val availableMethods = capabilities.getAvailableMethods()

    if (availableMethods.isEmpty()) {
      return GestureResult.Failure(
        code = GestureErrorCode.ROOT_DENIED,
        message = "No input method available",
        suggestion = "Grant root access for input injection",
      )
    }

    val startMethod = preferredMethod?.takeIf { it in availableMethods }
      ?: availableMethods.first()

    val methodOrder = buildList {
      add(startMethod)
      if (fallbackEnabled) {
        addAll(availableMethods.filter { it != startMethod })
      }
    }

    var lastResult: GestureResult = GestureResult.Failure(
      code = GestureErrorCode.ROOT_DENIED,
      message = "No input method tried",
    )

    for (method in methodOrder) {
      val injector = getInjectorIfAvailable(method, capabilities) ?: continue

      Log.d(TAG, "Attempting gesture with method: $method")
      updateCurrentMethod(method)

      val result = action(injector)

      if (result is GestureResult.Success) {
        Log.d(TAG, "Gesture succeeded with method: $method")
        return result
      }

      lastResult = result
      Log.w(TAG, "Gesture failed with method $method: $result")

      if (!fallbackEnabled) {
        break
      }
    }

    return lastResult
  }

  override fun setFallbackEnabled(enabled: Boolean) {
    Log.d(TAG, "Setting fallback enabled: $enabled")
    fallbackEnabled = enabled
  }

  override fun isFallbackEnabled(): Boolean = fallbackEnabled

  override fun observeCurrentMethod(): Flow<InputMethod?> = _currentMethodFlow.asStateFlow()

  override fun observeCapabilities(): Flow<InputCapabilities> = _capabilitiesFlow.asStateFlow()

  private suspend fun detectCapabilitiesInternal(): InputCapabilities {
    var nativeAvailable = false
    var nativeError: String? = null
    var androidInputAvailable = false
    var androidInputError: String? = null
    var shellAvailable = false
    var shellError: String? = null
    var detectedDevicePath: String? = null
    var mtProtocol: MTProtocol? = null
    var maxTouchPoints = 1
    var isDaemonBased = false

    val daemonState = daemonRepository.daemonState.value
    if (daemonState is DaemonState.Ready) {
      val hasInputInjection = CapabilityFlags.hasInputInjection(daemonState.capabilities)
      if (hasInputInjection) {
        nativeAvailable = true
        isDaemonBased = true
        Log.i(TAG, "Native available via daemon (INPUT_INJECTION capability)")

        // Try to get device path from SystemStatus
        try {
          val systemStatus = daemonRepository.getSystemStatus().getOrNull()
          if (systemStatus != null) {
            detectedDevicePath = systemStatus.touchDevicePath
            maxTouchPoints = systemStatus.maxTouchPoints.coerceAtLeast(1)
            Log.i(
              TAG,
              "Daemon system status: device=$detectedDevicePath, maxTouchPoints=$maxTouchPoints",
            )
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to get system status from daemon", e)
        }

        // Return early with daemon-based capabilities
        return InputCapabilities(
          nativeAvailable = true,
          nativeError = null,
          androidInputAvailable = false,
          androidInputError = "Android input command not available (daemon handles input)",
          shellAvailable = false,
          shellError = "Shell input injection not available (daemon handles input)",
          detectedDevicePath = detectedDevicePath,
          mtProtocol = MTProtocol.PROTOCOL_B,
          maxTouchPoints = maxTouchPoints.coerceAtLeast(10),
          isDaemonBased = true,
          timestamp = System.currentTimeMillis(),
        )
      } else {
        nativeError = "Daemon connected but INPUT_INJECTION capability not available"
        Log.w(TAG, nativeError)
      }
    } else {
      val stateMessage = when (daemonState) {
        is DaemonState.Stopped -> "Daemon not running"
        is DaemonState.Starting -> "Daemon starting"
        is DaemonState.Connecting -> "Connecting to daemon"
        is DaemonState.Authenticating -> "Authenticating with daemon"
        is DaemonState.Reconciling -> "Reconciling daemon state"
        is DaemonState.Error -> "Daemon error: ${daemonState.message}"
        is DaemonState.Ready -> "Daemon ready but INPUT_INJECTION not available"
      }
      nativeError = stateMessage
      Log.w(TAG, "Daemon not ready for input injection: $stateMessage")
    }

    // Fallback: Check native availability (try daemon first, then direct JNI)
    try {
      val daemon = getOrCreateDaemonInjector()
      if (daemon.isAvailable()) {
        nativeAvailable = true
        detectedDevicePath = daemon.getDevicePath()
        Log.i(TAG, "Native available via daemon: $detectedDevicePath")
      } else {
        val native = getOrCreateNativeInjector()
        if (native.isAvailable()) {
          nativeAvailable = true
          detectedDevicePath = native.getDevicePath()
          mtProtocol = native.getMTProtocol()
          maxTouchPoints = native.getMaxTouchPoints()
          Log.i(TAG, "Native available via JNI: $detectedDevicePath")
        } else {
          val initErr = native.getLastInitError()
          val nativeErr = native.getNativeErrorInfo()
          nativeError = when {
            initErr != null -> "${initErr.message}${initErr.suggestion?.let { " ($it)" } ?: ""}"
            nativeErr.isNotBlank() -> nativeErr
            else -> "Native input injection not available (SELinux blocked)"
          }
          Log.w(TAG, "Native not available: $nativeError")
        }
      }
    } catch (e: Exception) {
      nativeError = e.message ?: "Unknown error"
      Log.w(TAG, "Native capability check failed", e)
    }

    // Check Android input command availability
    try {
      val androidInput = getOrCreateAndroidInputInjector()
      if (androidInput.isAvailable()) {
        androidInputAvailable = true
        Log.d(TAG, "Android input command available")
      } else {
        androidInputError = "Android input command not available (root required)"
      }
    } catch (e: Exception) {
      androidInputError = e.message ?: "Unknown error"
      Log.w(TAG, "Android input capability check failed", e)
    }

    // Check shell availability
    try {
      val shell = getOrCreateShellInjector()
      if (shell.isAvailable()) {
        shellAvailable = true
        Log.d(TAG, "Shell sendevent available")
        if (detectedDevicePath == null) {
          detectedDevicePath = shell.getDevicePath()
          mtProtocol = shell.getMTProtocol()
        }
        if (maxTouchPoints <= 1) {
          val shellMaxPoints = shell.getMaxTouchPoints()
          if (shellMaxPoints > 1) {
            maxTouchPoints = shellMaxPoints
          } else if (mtProtocol != MTProtocol.SINGLE_TOUCH) {
            maxTouchPoints = 10
          }
        }
      } else {
        shellError = "Shell input injection not available (root required)"
      }
    } catch (e: Exception) {
      shellError = e.message ?: "Unknown error"
      Log.w(TAG, "Shell capability check failed", e)
    }

    if (mtProtocol == MTProtocol.PROTOCOL_B && maxTouchPoints <= 1) {
      maxTouchPoints = 10
      Log.d(TAG, "Protocol B detected but maxTouchPoints was 1, using default 10")
    }

    Log.i(
      TAG,
      "Capability detection complete: native=$nativeAvailable, android=$androidInputAvailable, shell=$shellAvailable, isDaemonBased=$isDaemonBased",
    )

    return InputCapabilities(
      nativeAvailable = nativeAvailable,
      nativeError = nativeError,
      androidInputAvailable = androidInputAvailable,
      androidInputError = androidInputError,
      shellAvailable = shellAvailable,
      shellError = shellError,
      detectedDevicePath = detectedDevicePath,
      mtProtocol = mtProtocol,
      maxTouchPoints = maxTouchPoints,
      isDaemonBased = isDaemonBased,
      timestamp = System.currentTimeMillis(),
    )
  }

  private suspend fun getInjectorIfAvailable(
    method: InputMethod,
    capabilities: InputCapabilities,
  ): InputInjector? {
    return when (method) {
      InputMethod.NATIVE_IOCTL -> {
        if (capabilities.nativeAvailable) {
          val daemon = getOrCreateDaemonInjector()
          if (daemon.isAvailable()) {
            daemon
          } else {
            getOrCreateNativeInjector()
          }
        } else null
      }

      InputMethod.ANDROID_INPUT -> {
        if (capabilities.androidInputAvailable) getOrCreateAndroidInputInjector() else null
      }

      InputMethod.SHELL_SENDEVENT -> {
        if (capabilities.shellAvailable) getOrCreateShellInjector() else null
      }
    }
  }

  private fun getOrCreateNativeInjector(): NativeInputInjector {
    return nativeInjector ?: NativeInputInjector().also { nativeInjector = it }
  }

  private fun getOrCreateDaemonInjector(): DaemonInputInjector {
    return daemonInjector ?: DaemonInputInjector(context, rootShell).also { daemonInjector = it }
  }

  private fun getOrCreateAndroidInputInjector(): AndroidInputInjector {
    return androidInputInjector ?: AndroidInputInjector(rootShell).also {
      androidInputInjector = it
    }
  }

  private fun getOrCreateShellInjector(): ShellInputInjector {
    return shellInjector ?: ShellInputInjector(rootShell, deviceDiscovery).also {
      shellInjector = it
    }
  }

  private fun updateCurrentMethod(method: InputMethod) {
    if (currentMethod != method) {
      currentMethod = method
      _currentMethodFlow.value = method
    }
  }
}
