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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.AttestationCertificate
import me.fleey.futon.DaemonStatus
import me.fleey.futon.FutonConfig
import me.fleey.futon.SystemStatus
import me.fleey.futon.data.apps.AppDiscovery
import me.fleey.futon.data.daemon.models.AutomationEvent
import me.fleey.futon.data.daemon.models.DaemonConnectionResult
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.data.daemon.models.ErrorCode
import me.fleey.futon.data.daemon.models.InputDeviceEntry
import me.fleey.futon.data.daemon.models.ReconciliationState
import me.fleey.futon.data.daemon.models.ReconciliationStep
import me.fleey.futon.data.daemon.models.toDomain
import me.fleey.futon.data.perception.models.DetectedElement
import me.fleey.futon.data.perception.models.ElementType
import me.fleey.futon.domain.perception.models.UIBounds
import org.koin.core.annotation.Single

@Single(binds = [DaemonRepository::class])
class DaemonRepositoryImpl(
  private val context: android.content.Context,
  private val binderClient: DaemonBinderClient,
  private val authenticator: DaemonAuthenticator,
  private val keyDeployer: KeyDeployer,
  private val callbackBridge: CallbackBridge,
  private val securityAuditLogger: SecurityAuditLogger,
  private val appDiscovery: AppDiscovery,
  private val aiClient: me.fleey.futon.data.ai.AIClient,
) : DaemonRepository {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val connectionMutex = Mutex()
  private val configMutex = Mutex()

  private val _daemonState = MutableStateFlow<DaemonState>(DaemonState.Stopped)
  override val daemonState: StateFlow<DaemonState> = _daemonState.asStateFlow()

  private val _status = MutableStateFlow<DaemonStatus?>(null)
  override val status: StateFlow<DaemonStatus?> = _status.asStateFlow()

  private val _errors = MutableSharedFlow<DaemonError>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val errors: SharedFlow<DaemonError> = _errors.asSharedFlow()

  private val _automationEvents = MutableSharedFlow<AutomationEvent>(
    extraBufferCapacity = 32,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val automationEvents: SharedFlow<AutomationEvent> = _automationEvents.asSharedFlow()

  private val _reconciliationState =
    MutableStateFlow<ReconciliationState>(ReconciliationState.NotNeeded)
  override val reconciliationState: StateFlow<ReconciliationState> =
    _reconciliationState.asStateFlow()

  private val _detectionResults = MutableSharedFlow<List<DetectedElement>>(
    replay = 1,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val detectionResults: SharedFlow<List<DetectedElement>> =
    _detectionResults.asSharedFlow()

  @Volatile
  private var lastKnownConfig: FutonConfig? = null

  @Volatile
  private var lastKnownHotPathRules: String? = null

  @Volatile
  private var lastReconnectAttempt: Long = 0

  @Volatile
  private var connectionJob: Job? = null

  init {
    setupFlowBridges()
  }

  private fun setupFlowBridges() {
    callbackBridge.status
      .onEach { status -> _status.value = status }
      .launchIn(scope)

    callbackBridge.errors
      .onEach { error -> _errors.emit(error) }
      .launchIn(scope)

    callbackBridge.automationEvents
      .onEach { event -> _automationEvents.emit(event) }
      .launchIn(scope)

    callbackBridge.detectionResults
      .onEach { results -> _detectionResults.emit(results) }
      .launchIn(scope)

    binderClient.connectionState
      .onEach { state -> handleBinderStateChange(state) }
      .launchIn(scope)

    callbackBridge.memoryPressureEvents
      .onEach { event -> handleMemoryPressure(event) }
      .launchIn(scope)

    callbackBridge.bufferReleaseRequests
      .onEach { request -> handleBufferReleaseRequest(request) }
      .launchIn(scope)
  }


  private suspend fun handleBinderStateChange(state: DaemonState) {
    when (state) {
      is DaemonState.Error -> {
        if (state.recoverable) {
          _daemonState.value = state
          _errors.emit(
            DaemonError(
              code = state.code,
              message = state.message,
            ),
          )
        } else {
          _daemonState.value = state
        }
      }

      is DaemonState.Stopped -> {
        _daemonState.value = DaemonState.Stopped
        _reconciliationState.value = ReconciliationState.NotNeeded
      }

      else -> {
        // Other states are handled by the connect flow
      }
    }
  }

  private suspend fun handleMemoryPressure(event: MemoryPressureEvent) {
    Log.w(TAG, "Memory pressure event: level=${event.level}")
    if (event.isCritical) {
      binderClient.releaseAllTrackedBuffers()
    }
  }

  private suspend fun handleBufferReleaseRequest(request: BufferReleaseRequest) {
    Log.d(TAG, "Buffer release request: bufferId=${request.bufferId}, timeout=${request.timeoutMs}")
    val buffer = binderClient.releaseTrackedBuffer(request.bufferId)
    if (buffer != null) {
      try {
        binderClient.releaseScreenshot(request.bufferId)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to release screenshot on daemon", e)
      }
      buffer.close()
    }
  }

  override suspend fun connect(): Result<Unit> = connectionMutex.withLock {
    val now = System.currentTimeMillis()
    if (now - lastReconnectAttempt < MIN_RECONNECT_INTERVAL_MS) {
      delay(MIN_RECONNECT_INTERVAL_MS - (now - lastReconnectAttempt))
    }
    lastReconnectAttempt = System.currentTimeMillis()

    connectionJob?.cancel()
    connectionJob = scope.launch {
      executeConnectionSequence()
    }
    connectionJob?.join()

    return when (val state = _daemonState.value) {
      is DaemonState.Ready -> Result.success(Unit)
      is DaemonState.Error -> Result.failure(
        DaemonBinderException(
          DaemonError(code = state.code, message = state.message),
        ),
      )

      else -> Result.failure(
        DaemonBinderException(
          DaemonError(
            code = ErrorCode.CONNECTION_FAILED,
            message = "Connection failed: unexpected state $state",
          ),
        ),
      )
    }
  }

  private suspend fun executeConnectionSequence() {
    try {
      _daemonState.value = DaemonState.Starting
      Log.d(TAG, "Starting connection sequence")

      val keyResult = authenticator.ensureKeyPairExists()
      if (keyResult.isFailure) {
        val error = (keyResult.exceptionOrNull() as? DaemonAuthException)?.error
          ?: DaemonError.authentication(
            ErrorCode.AUTH_KEY_NOT_FOUND,
            "Failed to ensure key pair exists",
          )
        Log.e(TAG, "Key pair check failed: ${error.message}")
        _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
        return
      }
      Log.d(TAG, "Key pair exists")

      val needsRedeploy = keyDeployer.needsRedeployment().getOrElse { true }
      Log.d(TAG, "Needs redeploy: $needsRedeploy")
      if (needsRedeploy) {
        val publicKey = authenticator.getPublicKey().getOrElse {
          val error = DaemonError.authentication(
            ErrorCode.AUTH_KEY_NOT_FOUND,
            "Failed to get public key",
          )
          _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
          return
        }
        val deployResult = keyDeployer.deployPublicKey(publicKey)
        if (deployResult.isFailure) {
          val error = (deployResult.exceptionOrNull() as? KeyDeployException)?.error
            ?: DaemonError.security(
              ErrorCode.SECURITY_PUBKEY_DEPLOY_FAILED,
              "Failed to deploy public key",
            )
          _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
          return
        }
        Log.d(TAG, "Public key deployed")
      }

      _daemonState.value = DaemonState.Connecting
      Log.d(TAG, "Connecting to binder")

      val connectionResult = binderClient.connect()
      Log.d(TAG, "Binder connect result: $connectionResult")
      when (connectionResult) {
        is DaemonConnectionResult.NotRunning -> {
          _daemonState.value = DaemonState.Error(
            "Daemon service not running",
            ErrorCode.SERVICE_NOT_FOUND,
            recoverable = true,
          )
          return
        }

        is DaemonConnectionResult.Failed -> {
          _daemonState.value = DaemonState.Error(
            connectionResult.error.message,
            connectionResult.error.code,
            connectionResult.error.recoverable,
          )
          return
        }

        is DaemonConnectionResult.Connected -> {
          // Continue to authentication
        }
      }

      val instanceId = authenticator.getInstanceId()
      Log.d(TAG, "Checking session for instanceId: $instanceId")
      val sessionStatus = binderClient.checkSession(instanceId).getOrNull()
      Log.d(
        TAG,
        "Session status: hasActive=${sessionStatus?.hasActiveSession}, isOwn=${sessionStatus?.isOwnSession}",
      )

      if (sessionStatus?.hasActiveSession == true && sessionStatus.isOwnSession) {
        Log.d(TAG, "Reconnecting to existing session")
        executeReconciliation(connectionResult)
        return
      }

      if (sessionStatus?.hasActiveSession == true && !sessionStatus.isOwnSession) {
        Log.w(TAG, "Another session is active, waiting for timeout")
        val waitTime = sessionStatus.remainingTimeoutMs.coerceAtMost(SESSION_WAIT_TIMEOUT_MS)
        delay(waitTime)

        val recheckStatus = binderClient.checkSession(instanceId).getOrNull()
        if (recheckStatus?.hasActiveSession == true && !recheckStatus.isOwnSession) {
          _daemonState.value = DaemonState.Error(
            "Session conflict: another client is connected",
            ErrorCode.AUTH_SESSION_CONFLICT,
            recoverable = true,
          )
          return
        }
      }

      _daemonState.value = DaemonState.Authenticating
      Log.d(TAG, "Starting authentication")
      val authResult = executeAuthentication()
      if (authResult.isFailure) {
        val error = (authResult.exceptionOrNull() as? DaemonAuthException)?.error
          ?: DaemonError.authentication(
            ErrorCode.AUTH_CHALLENGE_FAILED,
            "Authentication failed",
          )
        Log.e(TAG, "Authentication failed: ${error.message}")
        _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
        return
      }
      Log.d(TAG, "Authentication successful")

      executeReconciliation(connectionResult)
    } catch (e: Exception) {
      Log.e(TAG, "Connection sequence failed", e)
      _daemonState.value = DaemonState.Error(
        "Connection failed: ${e.message}",
        ErrorCode.CONNECTION_FAILED,
        recoverable = true,
      )
    }
  }

  private suspend fun <T> executeWithReconnect(
    operationName: String,
    block: suspend () -> Result<T>,
  ): Result<T> {
    if (!isConnected()) {
      Log.w(TAG, "$operationName: Not connected, attempting to connect first")
      val connectResult = connect()
      if (connectResult.isFailure) {
        return Result.failure(
          DaemonBinderException(
            DaemonError.connection(
              ErrorCode.SERVICE_NOT_FOUND,
              "Not connected to daemon and connection failed",
            ),
          ),
        )
      }
    }

    var result = block()

    if (result.isSuccess) return result

    val error = result.exceptionOrNull()
    if (error is DaemonBinderException && error.error.code == ErrorCode.BINDER_DIED) {
      Log.w(TAG, "$operationName: Daemon died, attempting reconnect and retry")

      binderClient.disconnect()
      delay(100)

      val connectResult = connect()
      if (connectResult.isFailure) {
        Log.e(TAG, "$operationName: Reconnect failed")
        return result
      }

      Log.d(TAG, "$operationName: Reconnected, retrying operation")
      result = block()
    }

    return result
  }


  private suspend fun executeAuthentication(): Result<Unit> {
    val instanceId = authenticator.getInstanceId()
    securityAuditLogger.logAuthAttempt(instanceId, false, "Starting authentication")

    val challengeResult = binderClient.getChallenge()
    if (challengeResult.isFailure) {
      securityAuditLogger.logAuthAttempt(instanceId, false, "Failed to get challenge")
      return Result.failure(
        DaemonAuthException(
          DaemonError.authentication(
            ErrorCode.AUTH_CHALLENGE_FAILED,
            "Failed to get challenge: ${challengeResult.exceptionOrNull()?.message}",
          ),
        ),
      )
    }

    val challenge = challengeResult.getOrThrow()
    val signatureResult = authenticator.signChallenge(challenge)
    if (signatureResult.isFailure) {
      securityAuditLogger.logAuthAttempt(instanceId, false, "Failed to sign challenge")
      return Result.failure(signatureResult.exceptionOrNull()!!)
    }

    val signature = signatureResult.getOrThrow()

    val authResult = binderClient.authenticate(signature, instanceId)
    if (authResult.isFailure) {
      securityAuditLogger.logAuthAttempt(instanceId, false, "Authentication rejected")
      return Result.failure(
        DaemonAuthException(
          DaemonError.authentication(
            ErrorCode.AUTH_SIGNATURE_INVALID,
            "Authentication rejected: ${authResult.exceptionOrNull()?.message}",
          ),
        ),
      )
    }

    // Check if daemon requires attestation verification
    val authResponse = authResult.getOrNull()
    if (authResponse?.requiresAttestation == true) {
      Log.d(TAG, "Daemon requires attestation verification")

      val attestationResult = authenticator.getAttestationChain()
      if (attestationResult.isFailure) {
        securityAuditLogger.logAuthAttempt(instanceId, false, "Failed to get attestation chain")
        return Result.failure(
          DaemonAuthException(
            DaemonError.authentication(
              ErrorCode.AUTH_ATTESTATION_FAILED,
              "Failed to get attestation chain: ${attestationResult.exceptionOrNull()?.message}",
            ),
          ),
        )
      }

      val attestationChain = attestationResult.getOrThrow()
      val verifyResult = binderClient.verifyAttestation(
        attestationChain.map { certData ->
          val cert = AttestationCertificate()
          cert.data = certData
          cert
        }.toTypedArray(),
      )

      if (verifyResult.isFailure) {
        val errorMsg = verifyResult.exceptionOrNull()?.message ?: "Unknown error"
        Log.e(TAG, "Attestation verification failed: $errorMsg")
        securityAuditLogger.logAuthAttempt(instanceId, false, "Attestation mismatch: $errorMsg")
        return Result.failure(
          DaemonAuthException(
            DaemonError.authentication(
              ErrorCode.AUTH_ATTESTATION_MISMATCH,
              "Attestation Mismatch: $errorMsg",
            ),
          ),
        )
      }

      Log.i(TAG, "Attestation verification successful")
    }

    securityAuditLogger.logAuthAttempt(instanceId, true)
    securityAuditLogger.logSessionEvent(SecurityEventType.SessionCreated, instanceId)
    return Result.success(Unit)
  }

  private suspend fun executeReconciliation(connectionResult: DaemonConnectionResult.Connected) {
    _daemonState.value = DaemonState.Reconciling
    _reconciliationState.value = ReconciliationState.InProgress
    Log.d(TAG, "Starting reconciliation")

    try {
      Log.d(TAG, "Registering status callback")
      val registerResult = binderClient.registerStatusCallback(callbackBridge.statusCallback)
      if (registerResult.isFailure) {
        val error = DaemonError.connection(
          ErrorCode.CONNECTION_FAILED,
          "Failed to register status callback",
        )
        Log.e(
          TAG,
          "Failed to register status callback: ${registerResult.exceptionOrNull()?.message}",
        )
        _reconciliationState.value = ReconciliationState.Failed(
          ReconciliationStep.CALLBACK_REGISTRATION,
          error,
        )
        _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
        return
      }
      Log.d(TAG, "Status callback registered")

      Log.d(TAG, "Registering buffer release callback")
      binderClient.registerBufferReleaseCallback(callbackBridge.bufferReleaseCallback)
      Log.d(TAG, "Buffer release callback registered")

      lastKnownConfig?.let { config ->
        Log.d(TAG, "Restoring configuration")
        val configResult = binderClient.configure(config)
        if (configResult.isFailure) {
          Log.w(TAG, "Failed to restore configuration during reconciliation")
          val error = DaemonError.config(
            ErrorCode.CONFIG_SYNC_FAILED,
            "Failed to restore configuration",
          )
          _reconciliationState.value = ReconciliationState.Failed(
            ReconciliationStep.CONFIGURATION,
            error,
          )
          _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
          return
        }
      }

      lastKnownHotPathRules?.let { rules ->
        Log.d(TAG, "Restoring hot path rules")
        val rulesResult = binderClient.configureHotPath(rules)
        if (rulesResult.isFailure) {
          Log.w(TAG, "Failed to restore hot path rules during reconciliation")
          val error = DaemonError.automation(
            ErrorCode.AUTOMATION_HOT_PATH_INVALID,
            "Failed to restore hot path rules",
          )
          _reconciliationState.value = ReconciliationState.Failed(
            ReconciliationStep.HOT_PATH_RULES,
            error,
          )
          _daemonState.value = DaemonState.Error(error.message, error.code, error.recoverable)
          return
        }
      }

      _reconciliationState.value = ReconciliationState.Completed()
      _daemonState.value = DaemonState.Ready(
        version = connectionResult.version,
        capabilities = connectionResult.capabilities,
      )

      Log.i(TAG, "Connection sequence completed successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Reconciliation failed", e)
      val error = DaemonError.connection(
        ErrorCode.CONNECTION_FAILED,
        "Reconciliation failed: ${e.message}",
      )
      _reconciliationState.value = ReconciliationState.Failed(
        ReconciliationStep.CONFIGURATION,
        error,
      )
      _daemonState.value = DaemonState.Error(error.message, error.code, true)
    }
  }

  override suspend fun disconnect() {
    connectionMutex.withLock {
      connectionJob?.cancel()
      connectionJob = null

      val instanceId = authenticator.getInstanceId()
      securityAuditLogger.logSessionEvent(SecurityEventType.SessionDestroyed, instanceId)

      try {
        binderClient.unregisterStatusCallback(callbackBridge.statusCallback)
        binderClient.unregisterBufferReleaseCallback(callbackBridge.bufferReleaseCallback)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to unregister callbacks", e)
      }

      binderClient.disconnect()
      _daemonState.value = DaemonState.Stopped
      _reconciliationState.value = ReconciliationState.NotNeeded
      _status.value = null
    }
  }

  override fun isConnected(): Boolean {
    return binderClient.isConnected() && _daemonState.value is DaemonState.Ready
  }

  override suspend fun configure(config: FutonConfig): Result<Unit> = configMutex.withLock {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    val result = binderClient.configure(config)
    if (result.isSuccess) {
      lastKnownConfig = config
    }
    return result
  }

  override suspend fun configureHotPath(jsonRules: String): Result<Unit> = configMutex.withLock {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    val result = binderClient.configureHotPath(jsonRules)
    if (result.isSuccess) {
      lastKnownHotPathRules = jsonRules
    }
    return result
  }

  override suspend fun getSystemStatus(): Result<SystemStatus> {
    if (!isConnected()) {
      Log.w(TAG, "getSystemStatus called but not connected to daemon")
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    return binderClient.getSystemStatus().also { result ->
      result.onFailure { e ->
        Log.e(TAG, "Failed to get system status", e)
      }
    }
  }

  override suspend fun listInputDevices(): Result<List<InputDeviceEntry>> {
    if (!isConnected()) {
      Log.w(TAG, "listInputDevices called but not connected to daemon")
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    return binderClient.listInputDevices().map { entries: Array<me.fleey.futon.InputDeviceEntry> ->
      entries.map { it.toDomain() }
    }.also { result: Result<List<InputDeviceEntry>> ->
      result.onFailure { e ->
        Log.e(TAG, "Failed to list input devices", e)
      }
    }
  }


  override suspend fun getScreenshot(): Result<ScreenshotData> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    val result = binderClient.getScreenshot()
    return result.mapCatching { screenshotResult ->
      val buffer = screenshotResult.buffer
        ?: throw DaemonBinderException(
          DaemonError.runtime(
            ErrorCode.RUNTIME_CAPTURE_FAILED,
            "Screenshot buffer is null",
          ),
        )
      binderClient.trackBuffer(screenshotResult.bufferId, buffer)
      ScreenshotData(
        bufferId = screenshotResult.bufferId,
        buffer = buffer,
        timestampNs = screenshotResult.timestampNs,
        width = screenshotResult.width,
        height = screenshotResult.height,
      )
    }
  }

  override suspend fun releaseScreenshot(bufferId: Int): Result<Unit> {
    val buffer = binderClient.releaseTrackedBuffer(bufferId)
    buffer?.close()
    return binderClient.releaseScreenshot(bufferId)
  }

  override suspend fun requestPerception(): Result<List<DetectedElement>> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }

    val result = binderClient.requestPerception()
    return result.map { detectionResults ->
      detectionResults.mapNotNull { detectionResult ->
        try {
          val boundingBox = UIBounds(
            left = detectionResult.x1.toInt(),
            top = detectionResult.y1.toInt(),
            right = detectionResult.x2.toInt(),
            bottom = detectionResult.y2.toInt(),
          )

          if (!boundingBox.isValid()) {
            return@mapNotNull null
          }

          val confidence = detectionResult.confidence.coerceIn(0f, 1f)
          val elementType = ElementType.fromClassId(detectionResult.classId)
          val text = detectionResult.text?.takeIf { it.isNotBlank() }
          val textConfidence = if (text != null) {
            detectionResult.textConfidence.coerceIn(0f, 1f)
          } else {
            null
          }

          DetectedElement(
            boundingBox = boundingBox,
            elementType = elementType,
            confidence = confidence,
            text = text,
            textConfidence = textConfidence,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to convert DetectionResult", e)
          null
        }
      }
    }
  }

  override suspend fun tap(x: Int, y: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.tap(x, y)
  }

  override suspend fun longPress(x: Int, y: Int, durationMs: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.longPress(x, y, durationMs)
  }

  override suspend fun doubleTap(x: Int, y: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.doubleTap(x, y)
  }

  override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.swipe(x1, y1, x2, y2, durationMs)
  }

  override suspend fun scroll(
    x: Int,
    y: Int,
    direction: ScrollDirection,
    distance: Int,
  ): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.scroll(x, y, direction.value, distance)
  }

  override suspend fun pinch(
    centerX: Int,
    centerY: Int,
    startDistance: Int,
    endDistance: Int,
    durationMs: Int,
  ): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.pinch(centerX, centerY, startDistance, endDistance, durationMs)
  }

  override suspend fun multiTouch(xs: IntArray, ys: IntArray, actions: IntArray): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.multiTouch(xs, ys, actions)
  }

  override suspend fun inputText(text: String): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.inputText(text)
  }

  override suspend fun pressKey(keyCode: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.pressKey(keyCode)
  }

  override suspend fun pressBack(): Result<Unit> {
    return executeWithReconnect("pressBack") {
      binderClient.pressBack()
    }
  }

  override suspend fun pressHome(): Result<Unit> {
    return executeWithReconnect("pressHome") {
      binderClient.pressHome()
    }
  }

  override suspend fun pressRecents(): Result<Unit> {
    return executeWithReconnect("pressRecents") {
      binderClient.pressRecents()
    }
  }

  override suspend fun openNotifications(): Result<Unit> {
    return executeWithReconnect("openNotifications") {
      binderClient.openNotifications()
    }
  }

  override suspend fun openQuickSettings(): Result<Unit> {
    return executeWithReconnect("openQuickSettings") {
      binderClient.openQuickSettings()
    }
  }

  override suspend fun launchApp(packageName: String): Result<Unit> {
    return executeWithReconnect("launchApp") {
      binderClient.launchApp(packageName)
    }
  }

  override suspend fun launchActivity(packageName: String, activityName: String): Result<Unit> {
    return executeWithReconnect("launchActivity") {
      binderClient.launchActivity(packageName, activityName)
    }
  }

  override suspend fun wait(durationMs: Int): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.wait(durationMs)
  }

  override suspend fun saveScreenshot(filePath: String): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.saveScreenshot(filePath)
  }

  override suspend fun requestIntervention(reason: String, actionHint: String): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.requestIntervention(reason, actionHint)
  }

  override suspend fun call(command: String, args: Map<String, Any>): Result<CallResult> {
    val argsJson = buildString {
      append("{")
      args.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) append(",")
        append("\"$key\":")
        when (value) {
          is String -> append("\"$value\"")
          is Number -> append(value)
          is Boolean -> append(value)
          else -> append("\"$value\"")
        }
      }
      append("}")
    }

    return executeWithReconnect("call") {
      binderClient.call(command, argsJson).map { resultJson ->
        parseCallResult(resultJson)
      }
    }
  }

  private fun parseCallResult(json: String): CallResult {
    // Simple JSON parsing
    val success = json.contains("\"success\":true")
    val error = extractJsonString(json, "error")
    val note = extractJsonString(json, "note")

    val data = mutableMapOf<String, String>()
    extractJsonString(json, "output")?.let { data["output"] = it }
    extractJsonString(json, "value")?.let { data["value"] = it }
    extractJsonInt(json, "exitCode")?.let { data["exitCode"] = it.toString() }

    return CallResult(
      success = success,
      error = error,
      data = data,
      note = note,
    )
  }

  private fun extractJsonString(json: String, key: String): String? {
    val keyPattern = "\"$key\":"
    val keyIndex = json.indexOf(keyPattern)
    if (keyIndex == -1) return null

    val valueStart = keyIndex + keyPattern.length
    val trimmed = json.substring(valueStart).trimStart()

    if (trimmed.startsWith("null")) return null
    if (!trimmed.startsWith("\"")) return null

    val endQuote = trimmed.indexOf("\"", 1)
    if (endQuote == -1) return null

    return trimmed.substring(1, endQuote)
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\")
  }

  private fun extractJsonInt(json: String, key: String): Int? {
    val keyPattern = "\"$key\":"
    val keyIndex = json.indexOf(keyPattern)
    if (keyIndex == -1) return null

    val valueStart = keyIndex + keyPattern.length
    val trimmed = json.substring(valueStart).trimStart()

    val numEnd = trimmed.indexOfFirst { !it.isDigit() && it != '-' }
    val numStr = if (numEnd == -1) trimmed else trimmed.take(numEnd)

    return numStr.toIntOrNull()
  }

  override suspend fun launchAppSmart(appNameOrPackage: String): Result<Unit> {
    Log.d(TAG, "launchAppSmart: $appNameOrPackage")

    val isPackageName = appNameOrPackage.contains(".") && !appNameOrPackage.contains(" ")

    if (isPackageName) {
      Log.d(TAG, "launchAppSmart: Trying direct package launch: $appNameOrPackage")
      val result = executeWithReconnect("launchApp") {
        binderClient.launchApp(appNameOrPackage)
      }
      if (result.isSuccess) {
        Log.d(TAG, "launchAppSmart: Direct launch succeeded")
        return result
      }
      val error = result.exceptionOrNull()
      Log.w(TAG, "launchAppSmart: Direct launch failed: ${error?.message}")

      if (error is DaemonBinderException && error.error.code.isBinderTransientError()) {
        Log.w(TAG, "launchAppSmart: Daemon binder issue, trying AppDiscovery fallback")
        val fallbackSuccess = appDiscovery.launchApp(appNameOrPackage)
        if (fallbackSuccess) {
          Log.d(TAG, "launchAppSmart: AppDiscovery fallback succeeded")
          return Result.success(Unit)
        }
        Log.w(TAG, "launchAppSmart: AppDiscovery fallback also failed")
        return result
      }
    }

    Log.d(TAG, "launchAppSmart: Searching for app by name")
    val app = appDiscovery.findAppByName(appNameOrPackage)
      ?: appDiscovery.getAppByPackage(appNameOrPackage)

    if (app != null) {
      Log.d(TAG, "launchAppSmart: Found app: ${app.packageName}")
      val result = executeWithReconnect("launchApp") {
        binderClient.launchApp(app.packageName)
      }
      if (result.isSuccess) return result

      val error = result.exceptionOrNull()
      if (error is DaemonBinderException && error.error.code.isBinderTransientError()) {
        Log.w(TAG, "launchAppSmart: Daemon binder issue, trying AppDiscovery fallback")
        val fallbackSuccess = appDiscovery.launchApp(app.packageName)
        if (fallbackSuccess) {
          Log.d(TAG, "launchAppSmart: AppDiscovery fallback succeeded")
          return Result.success(Unit)
        }
      }
      return result
    }

    val searchResults = appDiscovery.searchApps(appNameOrPackage)
    if (searchResults.isNotEmpty()) {
      val packageName = searchResults.first().packageName
      Log.d(TAG, "launchAppSmart: Found via search: $packageName")
      val result = executeWithReconnect("launchApp") {
        binderClient.launchApp(packageName)
      }
      if (result.isSuccess) return result

      val error = result.exceptionOrNull()
      if (error is DaemonBinderException && error.error.code.isBinderTransientError()) {
        Log.w(TAG, "launchAppSmart: Daemon binder issue, trying AppDiscovery fallback")
        val fallbackSuccess = appDiscovery.launchApp(packageName)
        if (fallbackSuccess) {
          Log.d(TAG, "launchAppSmart: AppDiscovery fallback succeeded")
          return Result.success(Unit)
        }
      }
      return result
    }

    // App not found by local search - let AI pick from all installed apps
    Log.d(TAG, "launchAppSmart: App not found locally, asking AI to pick package")
    val aiPickedPackage = askAIToPickPackage(appNameOrPackage)
    if (aiPickedPackage != null) {
      Log.d(TAG, "launchAppSmart: AI picked package: $aiPickedPackage")
      val result = executeWithReconnect("launchApp") {
        binderClient.launchApp(aiPickedPackage)
      }
      if (result.isSuccess) return result

      // Try AppDiscovery fallback
      val fallbackSuccess = appDiscovery.launchApp(aiPickedPackage)
      if (fallbackSuccess) {
        Log.d(TAG, "launchAppSmart: AppDiscovery fallback succeeded for AI-picked package")
        return Result.success(Unit)
      }
    }

    Log.w(TAG, "launchAppSmart: App not found: $appNameOrPackage")
    return Result.failure(
      DaemonBinderException(
        DaemonError.runtime(
          ErrorCode.UNKNOWN,
          "App not found: $appNameOrPackage",
        ),
      ),
    )
  }

  /**
   * Ask AI to pick the best matching package name from all installed apps.
   */
  private suspend fun askAIToPickPackage(appName: String): String? {
    return try {
      val pm = context.packageManager
      val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
      }

      val allApps = pm.queryIntentActivities(mainIntent, 0).map { resolveInfo ->
        val appInfo = resolveInfo.activityInfo.applicationInfo
        val label = pm.getApplicationLabel(appInfo).toString()
        val pkg = appInfo.packageName
        "$label ($pkg)"
      }.sorted()

      if (allApps.isEmpty()) {
        Log.w(TAG, "askAIToPickPackage: No apps found")
        return null
      }

      val prompt = buildString {
        appendLine("User wants to open app: \"$appName\"")
        appendLine()
        appendLine("Installed apps on device:")
        allApps.forEachIndexed { index, app ->
          appendLine("${index + 1}. $app")
        }
        appendLine()
        appendLine("Find the best matching app and launch it using launch_app action.")
        appendLine("If no match found, use back action.")
      }

      Log.d(TAG, "askAIToPickPackage: Asking AI with ${allApps.size} apps")

      val response = aiClient.analyzeScreenshot(
        screenshot = null,
        taskDescription = prompt,
        uiContext = null,
        conversationHistory = emptyList(),
        appContext = null,
      )

      if (response.action == me.fleey.futon.data.ai.models.ActionType.LAUNCH_APP) {
        response.parameters?.packageName
      } else {
        Log.d(TAG, "askAIToPickPackage: AI returned ${response.action}, not LAUNCH_APP")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "askAIToPickPackage: Failed", e)
      null
    }
  }

  override suspend fun startHotPath(): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.startHotPath()
  }

  override suspend fun stopAutomation(): Result<Unit> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.stopAutomation()
  }

  override suspend fun executeTask(taskJson: String): Result<Long> {
    if (!isConnected()) {
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.executeTask(taskJson)
  }

  override suspend fun reloadModels(): Result<Boolean> {
    if (!isConnected()) {
      Log.w(TAG, "reloadModels called but not connected to daemon")
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.reloadModels().also { result ->
      result.onSuccess { success ->
        Log.i(TAG, "reloadModels completed: success=$success")
      }
      result.onFailure { e ->
        Log.e(TAG, "Failed to reload models", e)
      }
    }
  }

  override suspend fun getModelStatus(): Result<String> {
    if (!isConnected()) {
      Log.w(TAG, "getModelStatus called but not connected to daemon")
      return Result.failure(
        DaemonBinderException(
          DaemonError.connection(
            ErrorCode.SERVICE_NOT_FOUND,
            "Not connected to daemon",
          ),
        ),
      )
    }
    return binderClient.getModelStatus().also { result ->
      result.onFailure { e ->
        Log.e(TAG, "Failed to get model status", e)
      }
    }
  }

  override fun setAutomationCompleteListener(listener: AutomationCompleteListener?) {
    callbackBridge.setAutomationCompleteListener(listener)
  }

  override fun getLastKnownConfig(): FutonConfig? = lastKnownConfig

  override fun getLastKnownHotPathRules(): String? = lastKnownHotPathRules

  override fun close() {
    scope.launch {
      disconnect()
    }
    callbackBridge.close()
    binderClient.close()
    scope.cancel()
  }

  companion object {
    private const val TAG = "DaemonRepository"
    private const val MIN_RECONNECT_INTERVAL_MS = 500L
    private const val SESSION_WAIT_TIMEOUT_MS = 30_000L
  }
}
