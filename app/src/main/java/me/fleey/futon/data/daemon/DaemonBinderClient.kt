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
package me.fleey.futon.data.daemon

import android.hardware.HardwareBuffer
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.AttestationCertificate
import me.fleey.futon.AuthenticateResult
import me.fleey.futon.CryptoHandshake
import me.fleey.futon.DetectionResult
import me.fleey.futon.FutonConfig
import me.fleey.futon.IBufferReleaseCallback
import me.fleey.futon.IFutonDaemon
import me.fleey.futon.IStatusCallback
import me.fleey.futon.ScreenshotResult
import me.fleey.futon.SessionStatus
import me.fleey.futon.SystemStatus
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.annotations.AidlCallExecutor
import me.fleey.futon.data.daemon.annotations.AidlWrapper
import me.fleey.futon.data.daemon.models.DaemonConnectionResult
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import org.koin.core.annotation.Single
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

@AidlWrapper(aidlClassName = "me.fleey.futon.IFutonDaemon")
interface DaemonBinderClient : Closeable {
  val connectionState: StateFlow<DaemonState>
  val bufferReleaseRequests: SharedFlow<BufferReleaseRequest>

  suspend fun connect(): DaemonConnectionResult
  suspend fun disconnect()
  fun isConnected(): Boolean

  suspend fun getVersion(): Result<Int>
  suspend fun getCapabilities(): Result<Int>

  suspend fun getChallenge(): Result<ByteArray>
  suspend fun authenticate(signature: ByteArray, instanceId: String): Result<AuthenticateResult>
  suspend fun verifyAttestation(attestationChain: Array<AttestationCertificate>): Result<Unit>
  suspend fun checkSession(instanceId: String): Result<SessionStatus>

  suspend fun initCryptoChannel(clientDhPublic: ByteArray): Result<CryptoHandshake>
  suspend fun sendControlMessage(encryptedMessage: ByteArray): Result<ByteArray>
  suspend fun sendDataMessage(encryptedData: ByteArray): Result<ByteArray>
  suspend fun rotateChannelKeys(): Result<CryptoHandshake>

  suspend fun registerStatusCallback(callback: IStatusCallback): Result<Unit>
  suspend fun unregisterStatusCallback(callback: IStatusCallback): Result<Unit>
  suspend fun registerBufferReleaseCallback(callback: IBufferReleaseCallback): Result<Unit>
  suspend fun unregisterBufferReleaseCallback(callback: IBufferReleaseCallback): Result<Unit>

  suspend fun configure(config: FutonConfig): Result<Unit>
  suspend fun configureHotPath(jsonRules: String): Result<Unit>

  suspend fun getSystemStatus(): Result<SystemStatus>
  suspend fun listInputDevices(): Result<Array<me.fleey.futon.InputDeviceEntry>>

  suspend fun getScreenshot(): Result<ScreenshotResult>
  suspend fun getScreenshotBytes(): Result<ByteArray>
  suspend fun releaseScreenshot(bufferId: Int): Result<Unit>
  suspend fun requestPerception(): Result<Array<DetectionResult>>

  suspend fun tap(x: Int, y: Int): Result<Unit>
  suspend fun longPress(x: Int, y: Int, durationMs: Int): Result<Unit>
  suspend fun doubleTap(x: Int, y: Int): Result<Unit>
  suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit>
  suspend fun scroll(x: Int, y: Int, direction: Int, distance: Int): Result<Unit>
  suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Int,
  ): Result<Unit>

  suspend fun multiTouch(xs: IntArray, ys: IntArray, actions: IntArray): Result<Unit>
  suspend fun inputText(text: String): Result<Unit>
  suspend fun pressKey(keyCode: Int): Result<Unit>

  suspend fun pressBack(): Result<Unit>
  suspend fun pressHome(): Result<Unit>
  suspend fun pressRecents(): Result<Unit>
  suspend fun openNotifications(): Result<Unit>
  suspend fun openQuickSettings(): Result<Unit>
  suspend fun launchApp(packageName: String): Result<Unit>
  suspend fun launchActivity(packageName: String, activityName: String): Result<Unit>

  suspend fun wait(durationMs: Int): Result<Unit>
  suspend fun saveScreenshot(filePath: String): Result<Unit>
  suspend fun requestIntervention(reason: String, actionHint: String): Result<Unit>

  suspend fun call(command: String, argsJson: String): Result<String>

  suspend fun startHotPath(): Result<Unit>
  suspend fun stopAutomation(): Result<Unit>
  suspend fun executeTask(taskJson: String): Result<Long>

  suspend fun reloadModels(): Result<Boolean>

  suspend fun getModelStatus(): Result<String>

  fun trackBuffer(bufferId: Int, buffer: HardwareBuffer)
  fun releaseTrackedBuffer(bufferId: Int): HardwareBuffer?
  fun releaseAllTrackedBuffers()
  fun getTrackedBufferIds(): Set<Int>
}

data class BufferReleaseRequest(
  val bufferId: Int,
  val timeoutMs: Int,
)

data class AuthenticateResponse(
  val success: Boolean,
  val requiresAttestation: Boolean = false,
  val keyId: String? = null,
)

data class CryptoHandshakeResponse(
  val dhPublicKey: ByteArray,
  val sessionId: String,
  val keyGeneration: Long,
  val capabilities: Int,
  val errorCode: Int,
  val errorMessage: String?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CryptoHandshakeResponse) return false
    return dhPublicKey.contentEquals(other.dhPublicKey) && sessionId == other.sessionId
  }

  override fun hashCode(): Int = dhPublicKey.contentHashCode() * 31 + sessionId.hashCode()
}

@Single(binds = [DaemonBinderClient::class])
class DaemonBinderClientImpl : DaemonBinderClient, AidlCallExecutor<IFutonDaemon> {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  private var daemon: IFutonDaemon? = null
  private var binder: IBinder? = null
  private var wrapper: DaemonBinderClient? = null

  private val _connectionState = MutableStateFlow<DaemonState>(DaemonState.Stopped)
  override val connectionState: StateFlow<DaemonState> = _connectionState.asStateFlow()

  private val _bufferReleaseRequests = MutableSharedFlow<BufferReleaseRequest>()
  override val bufferReleaseRequests: SharedFlow<BufferReleaseRequest> =
    _bufferReleaseRequests.asSharedFlow()

  private val trackedBuffers = ConcurrentHashMap<Int, HardwareBuffer>()

  private var reconnectAttempts = 0
  private var isReconnecting = false

  private val deathRecipient = IBinder.DeathRecipient {
    scope.launch {
      handleBinderDeath()
    }
  }

  private val internalBufferReleaseCallback = object : IBufferReleaseCallback.Stub() {
    override fun onBufferReleaseRequested(bufferId: Int, timeoutMs: Int) {
      scope.launch {
        _bufferReleaseRequests.emit(BufferReleaseRequest(bufferId, timeoutMs))
      }
    }
  }

  override suspend fun connect(): DaemonConnectionResult = withContext(Dispatchers.IO) {
    mutex.withLock {
      _connectionState.value = DaemonState.Connecting

      try {
        val serviceBinder = getServiceBinder(DaemonConfig.SERVICE_NAME)
        if (serviceBinder == null) {
          _connectionState.value = DaemonState.Stopped
          return@withContext DaemonConnectionResult.NotRunning
        }

        serviceBinder.linkToDeath(deathRecipient, 0)
        binder = serviceBinder
        val currentDaemon = IFutonDaemon.Stub.asInterface(serviceBinder)
        daemon = currentDaemon
        wrapper = DaemonBinderClientWrapper(currentDaemon, this@DaemonBinderClientImpl)

        reconnectAttempts = 0
        isReconnecting = false

        val version = daemon?.version ?: -1
        val capabilities = daemon?.capabilities ?: 0

        _connectionState.value = DaemonState.Ready(version, capabilities)
        DaemonConnectionResult.Connected(version, capabilities)
      } catch (e: Exception) {
        Log.e(TAG, "Connection failed", e)
        val error = createConnectionError(e)
        _connectionState.value = DaemonState.Error(
          error.message,
          error.code,
          error.recoverable,
        )
        DaemonConnectionResult.Failed(error)
      }
    }
  }

  override suspend fun disconnect() = withContext(Dispatchers.IO) {
    mutex.withLock {
      disconnectInternal()
    }
  }

  private fun disconnectInternal() {
    try {
      binder?.unlinkToDeath(deathRecipient, 0)
    } catch (_: Exception) {
    }

    releaseAllTrackedBuffers()

    daemon = null
    binder = null
    wrapper = null
    _connectionState.value = DaemonState.Stopped
  }

  override fun isConnected(): Boolean {
    return daemon != null && binder?.isBinderAlive == true
  }

  override suspend fun getChallenge(): Result<ByteArray> = wrapper?.getChallenge() ?: failureNotConnected()
  override suspend fun getVersion(): Result<Int> = wrapper?.getVersion() ?: failureNotConnected()
  override suspend fun getCapabilities(): Result<Int> = wrapper?.getCapabilities() ?: failureNotConnected()

  override suspend fun authenticate(
    signature: ByteArray,
    instanceId: String,
  ): Result<AuthenticateResult> = wrapper?.authenticate(signature, instanceId) ?: failureNotConnected()

  override suspend fun verifyAttestation(attestationChain: Array<AttestationCertificate>): Result<Unit> =
    wrapper?.verifyAttestation(attestationChain) ?: failureNotConnected()

  override suspend fun checkSession(instanceId: String): Result<SessionStatus> =
    wrapper?.checkSession(instanceId) ?: failureNotConnected()

  override suspend fun initCryptoChannel(clientDhPublic: ByteArray): Result<CryptoHandshake> =
    wrapper?.initCryptoChannel(clientDhPublic) ?: failureNotConnected()

  override suspend fun sendControlMessage(encryptedMessage: ByteArray): Result<ByteArray> =
    wrapper?.sendControlMessage(encryptedMessage) ?: failureNotConnected()

  override suspend fun sendDataMessage(encryptedData: ByteArray): Result<ByteArray> =
    wrapper?.sendDataMessage(encryptedData) ?: failureNotConnected()

  override suspend fun rotateChannelKeys(): Result<CryptoHandshake> =
    wrapper?.rotateChannelKeys() ?: failureNotConnected()

  override suspend fun registerStatusCallback(callback: IStatusCallback): Result<Unit> =
    wrapper?.registerStatusCallback(callback) ?: failureNotConnected()

  override suspend fun unregisterStatusCallback(callback: IStatusCallback): Result<Unit> =
    wrapper?.unregisterStatusCallback(callback) ?: failureNotConnected()

  override suspend fun registerBufferReleaseCallback(callback: IBufferReleaseCallback): Result<Unit> =
    wrapper?.registerBufferReleaseCallback(callback) ?: failureNotConnected()

  override suspend fun unregisterBufferReleaseCallback(callback: IBufferReleaseCallback): Result<Unit> =
    wrapper?.unregisterBufferReleaseCallback(callback) ?: failureNotConnected()

  override suspend fun configure(config: FutonConfig): Result<Unit> =
    wrapper?.configure(config) ?: failureNotConnected()

  override suspend fun configureHotPath(jsonRules: String): Result<Unit> =
    wrapper?.configureHotPath(jsonRules) ?: failureNotConnected()

  override suspend fun getSystemStatus(): Result<SystemStatus> =
    wrapper?.getSystemStatus() ?: failureNotConnected()

  override suspend fun listInputDevices(): Result<Array<me.fleey.futon.InputDeviceEntry>> =
    wrapper?.listInputDevices() ?: failureNotConnected()

  override suspend fun getScreenshot(): Result<ScreenshotResult> =
    wrapper?.getScreenshot() ?: failureNotConnected()

  override suspend fun getScreenshotBytes(): Result<ByteArray> =
    wrapper?.getScreenshotBytes() ?: failureNotConnected()

  override suspend fun releaseScreenshot(bufferId: Int): Result<Unit> =
    wrapper?.releaseScreenshot(bufferId) ?: failureNotConnected()

  override suspend fun requestPerception(): Result<Array<DetectionResult>> =
    wrapper?.requestPerception() ?: failureNotConnected()

  override suspend fun tap(x: Int, y: Int): Result<Unit> =
    wrapper?.tap(x, y) ?: failureNotConnected()

  override suspend fun longPress(x: Int, y: Int, durationMs: Int): Result<Unit> =
    wrapper?.longPress(x, y, durationMs) ?: failureNotConnected()

  override suspend fun doubleTap(x: Int, y: Int): Result<Unit> =
    wrapper?.doubleTap(x, y) ?: failureNotConnected()

  override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit> =
    wrapper?.swipe(x1, y1, x2, y2, durationMs) ?: failureNotConnected()

  override suspend fun scroll(x: Int, y: Int, direction: Int, distance: Int): Result<Unit> =
    wrapper?.scroll(x, y, direction, distance) ?: failureNotConnected()

  override suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Int,
  ): Result<Unit> = wrapper?.pinch(centerX, centerY, startDistance, endDistance, durationMs) ?: failureNotConnected()

  override suspend fun multiTouch(xs: IntArray, ys: IntArray, actions: IntArray): Result<Unit> =
    wrapper?.multiTouch(xs, ys, actions) ?: failureNotConnected()

  override suspend fun inputText(text: String): Result<Unit> =
    wrapper?.inputText(text) ?: failureNotConnected()

  override suspend fun pressKey(keyCode: Int): Result<Unit> =
    wrapper?.pressKey(keyCode) ?: failureNotConnected()

  override suspend fun pressBack(): Result<Unit> =
    wrapper?.pressBack() ?: failureNotConnected()

  override suspend fun pressHome(): Result<Unit> =
    wrapper?.pressHome() ?: failureNotConnected()

  override suspend fun pressRecents(): Result<Unit> =
    wrapper?.pressRecents() ?: failureNotConnected()

  override suspend fun openNotifications(): Result<Unit> =
    wrapper?.openNotifications() ?: failureNotConnected()

  override suspend fun openQuickSettings(): Result<Unit> =
    wrapper?.openQuickSettings() ?: failureNotConnected()

  override suspend fun launchApp(packageName: String): Result<Unit> =
    wrapper?.launchApp(packageName) ?: failureNotConnected()

  override suspend fun launchActivity(packageName: String, activityName: String): Result<Unit> =
    wrapper?.launchActivity(packageName, activityName) ?: failureNotConnected()

  override suspend fun wait(durationMs: Int): Result<Unit> =
    wrapper?.wait(durationMs) ?: failureNotConnected()

  override suspend fun saveScreenshot(filePath: String): Result<Unit> =
    wrapper?.saveScreenshot(filePath) ?: failureNotConnected()

  override suspend fun requestIntervention(reason: String, actionHint: String): Result<Unit> =
    wrapper?.requestIntervention(reason, actionHint) ?: failureNotConnected()

  override suspend fun call(command: String, argsJson: String): Result<String> =
    wrapper?.call(command, argsJson) ?: failureNotConnected()

  override suspend fun startHotPath(): Result<Unit> =
    wrapper?.startHotPath() ?: failureNotConnected()

  override suspend fun stopAutomation(): Result<Unit> =
    wrapper?.stopAutomation() ?: failureNotConnected()

  override suspend fun executeTask(taskJson: String): Result<Long> =
    wrapper?.executeTask(taskJson) ?: failureNotConnected()

  override suspend fun reloadModels(): Result<Boolean> =
    wrapper?.reloadModels() ?: failureNotConnected()

  override suspend fun getModelStatus(): Result<String> = wrapper?.getModelStatus() ?: failureNotConnected()

  private fun <T> failureNotConnected(): Result<T> = Result.failure(
    DaemonBinderException(
      DaemonError.connection(
        ErrorCode.SERVICE_NOT_FOUND,
        "Daemon not connected",
      ),
    ),
  )

  override fun trackBuffer(bufferId: Int, buffer: HardwareBuffer) {
    trackedBuffers[bufferId] = buffer
  }

  override fun releaseTrackedBuffer(bufferId: Int): HardwareBuffer? {
    return trackedBuffers.remove(bufferId)
  }

  override fun releaseAllTrackedBuffers() {
    val bufferIds = trackedBuffers.keys.toList()
    for (bufferId in bufferIds) {
      trackedBuffers.remove(bufferId)?.close()
    }
  }

  override fun getTrackedBufferIds(): Set<Int> {
    return trackedBuffers.keys.toSet()
  }

  override fun close() {
    scope.launch {
      disconnect()
    }
  }

  override suspend fun <R> execute(block: (IFutonDaemon) -> R): Result<R> =
    withContext(Dispatchers.IO) {
      val currentDaemon = daemon ?: return@withContext Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Daemon not connected",
          ),
        ),
      )

      val currentBinder = binder
      if (currentBinder == null || !currentBinder.isBinderAlive) {
        Log.e(TAG, "Binder is null or dead before call")
        handleBinderDeath()
        return@withContext Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.BINDER_DIED,
              "Binder connection lost",
            ),
          ),
        )
      }

      try {
        kotlinx.coroutines.withTimeout(5000L) {
          Result.success(block(currentDaemon))
        }
      } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Log.e(TAG, "Binder call timed out")
        Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.CONNECTION_FAILED,
              "Binder call timed out",
              e,
            ),
            e,
          ),
        )
      } catch (e: DeadObjectException) {
        Log.e(TAG, "DeadObjectException in Binder call", e)
        val binderStillAlive = currentBinder.isBinderAlive && currentBinder.pingBinder()
        if (binderStillAlive) {
          Log.w(TAG, "Binder still alive - likely buffer exhaustion, not daemon death")
          Result.failure(
            DaemonBinderException(
              DaemonError.connection(
                ErrorCode.BINDER_BUFFER_EXHAUSTED,
                "Binder transaction failed",
                e,
              ),
              e,
            ),
          )
        } else {
          handleBinderDeath()
          Result.failure(
            DaemonBinderException(
              DaemonError.connection(
                ErrorCode.BINDER_DIED,
                "Daemon process died",
                e,
              ),
              e,
            ),
          )
        }
      } catch (e: RemoteException) {
        Log.e(TAG, "RemoteException in Binder call: ${e.javaClass.simpleName}", e)
        if (e.message?.contains("FAILED_TRANSACTION") == true ||
          e.message?.contains("TransactionTooLargeException") == true ||
          e.cause is TransactionTooLargeException
        ) {
          handleBinderDeath()
        }
        Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.CONNECTION_FAILED,
              "Remote exception: ${e.message}",
              e,
            ),
            e,
          ),
        )
      } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException in Binder call", e)
        Result.failure(
          DaemonBinderException(
            DaemonError.security(
              ErrorCode.SECURITY_UNAUTHORIZED,
              "Security exception: ${e.message}",
            ),
            e,
          ),
        )
      } catch (e: IllegalStateException) {
        Log.e(TAG, "IllegalStateException in Binder call (possible dead binder)", e)
        if (e.message?.contains("Binder") == true || e.message?.contains("dead") == true) {
          handleBinderDeath()
        }
        Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.CONNECTION_FAILED,
              "Binder state error: ${e.message}",
              e,
            ),
            e,
          ),
        )
      } catch (e: Exception) {
        if (e.javaClass.name == "android.os.ServiceSpecificException") {
          try {
            val errorCodeField = e.javaClass.getDeclaredField("errorCode")
            errorCodeField.isAccessible = true
            val errorCode = errorCodeField.getInt(e)
            val message = e.message ?: "Service error (code: $errorCode)"
            Log.e(TAG, "ServiceSpecificException: code=$errorCode, message=$message")
            Result.failure(
              DaemonBinderException(
                DaemonError.runtime(
                  ErrorCode.fromServiceSpecific(errorCode),
                  message,
                  e,
                ),
                e,
              ),
            )
          } catch (reflectEx: Exception) {
            Log.e(TAG, "Failed to extract ServiceSpecificException details", reflectEx)
            Result.failure(
              DaemonBinderException(
                DaemonError.runtime(
                  ErrorCode.UNKNOWN,
                  "Service error: ${e.message}",
                  e,
                ),
                e,
              ),
            )
          }
        } else {
          Log.e(TAG, "Unexpected exception: ${e.javaClass.name}: ${e.message}", e)
          Result.failure(
            DaemonBinderException(
              DaemonError.runtime(
                ErrorCode.UNKNOWN,
                "Unexpected error: ${e.message}",
                e,
              ),
              e,
            ),
          )
        }
      }
    }

  private suspend fun handleBinderDeath() {
    mutex.withLock {
      _connectionState.value = DaemonState.Error(
        "Daemon process died",
        ErrorCode.BINDER_DIED,
        recoverable = true,
      )

      releaseAllTrackedBuffers()

      daemon = null
      binder = null

      if (!isReconnecting && reconnectAttempts < DaemonConfig.Reconnection.MAX_ATTEMPTS) {
        isReconnecting = true
        attemptReconnect()
      }
    }
  }

  private suspend fun attemptReconnect() {
    while (reconnectAttempts < DaemonConfig.Reconnection.MAX_ATTEMPTS) {
      val delayMs = calculateBackoffDelay(reconnectAttempts)
      delay(delayMs)

      reconnectAttempts++
      _connectionState.value = DaemonState.Connecting

      val result = connectInternal()
      if (result is DaemonConnectionResult.Connected) {
        isReconnecting = false
        return
      }

      if (result is DaemonConnectionResult.NotRunning) {
        break
      }
    }

    isReconnecting = false
    _connectionState.value = DaemonState.Error(
      "Failed to reconnect after ${DaemonConfig.Reconnection.MAX_ATTEMPTS} attempts",
      ErrorCode.RECONNECTION_EXHAUSTED,
      recoverable = false,
    )
  }

  private suspend fun connectInternal(): DaemonConnectionResult =
    withContext(Dispatchers.IO) {
      try {
        val serviceBinder = getServiceBinder(DaemonConfig.SERVICE_NAME)
        if (serviceBinder == null) {
          _connectionState.value = DaemonState.Stopped
          return@withContext DaemonConnectionResult.NotRunning
        }

        serviceBinder.linkToDeath(deathRecipient, 0)
        binder = serviceBinder
        val currentDaemon = IFutonDaemon.Stub.asInterface(serviceBinder)
        daemon = currentDaemon
        wrapper = DaemonBinderClientWrapper(currentDaemon, this@DaemonBinderClientImpl)

        val version = daemon?.version ?: -1
        val capabilities = daemon?.capabilities ?: 0

        _connectionState.value = DaemonState.Ready(version, capabilities)
        DaemonConnectionResult.Connected(version, capabilities)
      } catch (e: Exception) {
        val error = createConnectionError(e)
        _connectionState.value = DaemonState.Error(
          error.message,
          error.code,
          error.recoverable,
        )
        DaemonConnectionResult.Failed(error)
      }
    }

  private fun calculateBackoffDelay(attempt: Int): Long {
    val baseDelay = DaemonConfig.Reconnection.INITIAL_BACKOFF_MS
    val maxDelay = DaemonConfig.Reconnection.MAX_BACKOFF_MS
    val delay = baseDelay * (1 shl attempt)
    return min(delay, maxDelay)
  }

  private fun createConnectionError(e: Exception): DaemonError {
    return when (e) {
      is DeadObjectException -> DaemonError.connection(
        ErrorCode.BINDER_DIED,
        "Daemon process died",
        e,
      )

      is RemoteException -> DaemonError.connection(
        ErrorCode.CONNECTION_FAILED,
        "Remote exception: ${e.message}",
        e,
      )

      is SecurityException -> DaemonError.security(
        ErrorCode.SECURITY_UNAUTHORIZED,
        "Security exception: ${e.message}",
      )

      else -> DaemonError.connection(
        ErrorCode.CONNECTION_FAILED,
        "Connection failed: ${e.message}",
        e,
      )
    }
  }

  companion object {
    private const val TAG = "DaemonBinderClient"

    @Suppress("PrivateApi")
    private fun getServiceBinder(name: String): IBinder? {
      return try {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
        getServiceMethod.invoke(null, name) as? IBinder
      } catch (e: Exception) {
        Log.e(TAG, "Failed to lookup service '$name'", e)
        null
      }
    }
  }
}

class DaemonBinderException(
  val error: DaemonError,
  cause: Throwable? = null,
) : Exception(error.message, cause)
