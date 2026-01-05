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

import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.BuildConfig
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.deployment.DaemonDeployer
import me.fleey.futon.data.daemon.deployment.DeploymentState
import me.fleey.futon.data.daemon.deployment.RootState
import me.fleey.futon.data.daemon.models.DaemonConnectionResult
import me.fleey.futon.data.daemon.models.DaemonLifecycleState
import me.fleey.futon.data.daemon.models.LifecycleDiagnostic
import me.fleey.futon.data.daemon.models.StartupPhase
import me.fleey.futon.platform.root.RootChecker
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.Closeable

interface DaemonLifecycleManager : Closeable {
  val daemonLifecycle: StateFlow<DaemonLifecycleState>

  suspend fun startDaemon(): Result<Unit>
  suspend fun stopDaemon(): Result<Unit>
  suspend fun restartDaemon(): Result<Unit>

  fun isDaemonRunning(): Boolean
  fun scheduleKeepAlive()
  fun cancelKeepAlive()
  fun setKeepAliveMs(ms: Long)

  /**
   * Synchronizes lifecycle state with actual daemon status.
   * Call this to detect externally started daemons or recover from state inconsistencies.
   */
  suspend fun syncLifecycleState()
}


@Single(binds = [DaemonLifecycleManager::class])
class DaemonLifecycleManagerImpl(
  private val rootShell: RootShell,
  private val rootChecker: RootChecker,
  private val daemonDeployer: DaemonDeployer,
  private val binderClient: DaemonBinderClient,
) : DaemonLifecycleManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  private val _daemonLifecycle =
    MutableStateFlow<DaemonLifecycleState>(DaemonLifecycleState.Stopped)
  override val daemonLifecycle: StateFlow<DaemonLifecycleState> = _daemonLifecycle.asStateFlow()

  private var keepAliveJob: Job? = null
  private var keepAliveMs: Long = DaemonConfig.Lifecycle.DEFAULT_KEEP_ALIVE_MS

  private val startupLogs = mutableListOf<String>()
  private var startupStartTime = 0L

  init {
    observeBinderState()
  }

  private fun addStartupLog(message: String) {
    val timestamp = System.currentTimeMillis() - startupStartTime
    startupLogs.add("[${timestamp}ms] $message")
    Log.d(TAG, message)
  }

  private fun updateStartingState(phase: StartupPhase) {
    val elapsed = System.currentTimeMillis() - startupStartTime
    _daemonLifecycle.value = DaemonLifecycleState.Starting(
      phase = phase,
      logs = startupLogs.toList(),
      elapsedMs = elapsed,
    )
  }

  /**
   * Observes binder client connection state and syncs lifecycle state accordingly.
   * This handles cases where daemon is started externally or connection is lost.
   */
  private fun observeBinderState() {
    scope.launch {
      binderClient.connectionState.collect { state ->
        val currentLifecycle = _daemonLifecycle.value
        when (state) {
          is me.fleey.futon.data.daemon.models.DaemonState.Ready -> {
            if (currentLifecycle !is DaemonLifecycleState.Running) {
              val pid = getDaemonPid()
              Log.i(TAG, "Daemon connected externally, syncing lifecycle state (pid=$pid)")
              _daemonLifecycle.value = DaemonLifecycleState.Running(pid = pid)
            }
          }

          is me.fleey.futon.data.daemon.models.DaemonState.Stopped -> {
            if (currentLifecycle is DaemonLifecycleState.Running) {
              Log.i(TAG, "Daemon disconnected, syncing lifecycle state to Stopped")
              _daemonLifecycle.value = DaemonLifecycleState.Stopped
            }
          }

          is me.fleey.futon.data.daemon.models.DaemonState.Error -> {
            if (!state.recoverable && currentLifecycle is DaemonLifecycleState.Running) {
              Log.w(TAG, "Daemon error (non-recoverable), syncing lifecycle state")
              _daemonLifecycle.value = DaemonLifecycleState.Failed(
                reason = state.message,
                diagnostic = null,
              )
            }
          }

          else -> {
            // Connecting, Authenticating, Reconciling, Starting - no lifecycle change needed
          }
        }
      }
    }
  }

  override suspend fun syncLifecycleState() = withContext(Dispatchers.IO) {
    mutex.withLock {
      val binder = getServiceBinder(DaemonConfig.SERVICE_NAME)
      val binderAvailable = binder?.isBinderAlive == true

      if (binderAvailable) {
        val pid = getDaemonPid()
        if (_daemonLifecycle.value !is DaemonLifecycleState.Running) {
          Log.i(TAG, "syncLifecycleState: daemon is running (pid=$pid), updating state")
          _daemonLifecycle.value = DaemonLifecycleState.Running(pid = pid)
        }
      } else {
        if (_daemonLifecycle.value is DaemonLifecycleState.Running) {
          Log.i(TAG, "syncLifecycleState: daemon is not running, updating state")
          _daemonLifecycle.value = DaemonLifecycleState.Stopped
        }
      }
    }
  }

  override suspend fun startDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
    mutex.withLock {
      val currentState = _daemonLifecycle.value
      if (currentState is DaemonLifecycleState.Running) {
        return@withContext Result.success(Unit)
      }
      if (currentState is DaemonLifecycleState.Starting) {
        return@withContext Result.failure(
          IllegalStateException("Daemon is already starting"),
        )
      }

      startupLogs.clear()
      startupStartTime = System.currentTimeMillis()

      updateStartingState(StartupPhase.Initializing)
      addStartupLog("Starting daemon initialization")

      // Check if daemon binder service is already available
      addStartupLog("Checking for existing daemon service...")
      val binder = getServiceBinder(DaemonConfig.SERVICE_NAME)
      val binderAlreadyAvailable = binder?.isBinderAlive == true
      if (binderAlreadyAvailable) {
        addStartupLog("Found existing daemon service, verifying version...")
        updateStartingState(StartupPhase.VerifyingVersion)
        val versionCheckResult = verifyDaemonVersion()
        if (versionCheckResult.isSuccess) {
          val pid = getDaemonPid()
          addStartupLog("Existing daemon verified (pid=$pid)")
          _daemonLifecycle.value = DaemonLifecycleState.Running(pid = pid)
          return@withContext Result.success(Unit)
        }
        addStartupLog("Version check failed: ${versionCheckResult.exceptionOrNull()?.message}")
      }

      // Check root access
      updateStartingState(StartupPhase.CheckingRoot)
      addStartupLog("Checking root access...")
      val rootState = rootChecker.checkRoot()
      if (rootState !is RootState.Available) {
        val diagnostic = when (rootState) {
          is RootState.Unavailable -> LifecycleDiagnostic.RootUnavailable(rootState.reason)
          is RootState.SELinuxBlocked -> LifecycleDiagnostic.SELinuxDenied(rootState.details)
          else -> LifecycleDiagnostic.RootUnavailable("Root not available")
        }
        addStartupLog("Root access check failed: $rootState")
        val failedState = DaemonLifecycleState.Failed(
          reason = "Root access required",
          diagnostic = diagnostic,
          logs = startupLogs.toList(),
        )
        _daemonLifecycle.value = failedState
        return@withContext Result.failure(DaemonLifecycleException(failedState))
      }
      addStartupLog("Root access available")

      // Check if binary exists
      updateStartingState(StartupPhase.CheckingBinary)
      addStartupLog("Checking daemon binary...")
      val checkResult =
        rootShell.execute("test -f ${DaemonConfig.BINARY_PATH} && echo exists", timeoutMs = 2_000)
      val binaryExists = checkResult is ShellResult.Success && checkResult.output.trim() == "exists"
      if (!binaryExists) {
        updateStartingState(StartupPhase.Deploying)
        addStartupLog("Binary not found, deploying...")
        val deployResult = daemonDeployer.deploy()
        if (deployResult !is DeploymentState.Deployed) {
          val reason = when (deployResult) {
            is DeploymentState.Failed -> deployResult.reason
            else -> "Deployment failed"
          }
          addStartupLog("Deployment failed: $reason")
          val failedState = DaemonLifecycleState.Failed(
            reason = reason,
            diagnostic = LifecycleDiagnostic.BinaryMissing,
            logs = startupLogs.toList(),
          )
          _daemonLifecycle.value = failedState
          return@withContext Result.failure(DaemonLifecycleException(failedState))
        }
        addStartupLog("Deployment completed")
      } else {
        addStartupLog("Binary found at ${DaemonConfig.BINARY_PATH}")
      }

      updateStartingState(StartupPhase.ExecutingStart)
      addStartupLog("Executing daemon start command...")
      val startResult = executeDaemonStart()
      if (startResult.isFailure) {
        val exception = startResult.exceptionOrNull()
        addStartupLog("Start command failed: ${exception?.message}")
        val failedState = if (exception is DaemonLifecycleException) {
          exception.state.copy(logs = startupLogs.toList())
        } else {
          DaemonLifecycleState.Failed(
            reason = exception?.message ?: "Unknown error",
            cause = exception,
            logs = startupLogs.toList(),
          )
        }
        _daemonLifecycle.value = failedState
        return@withContext startResult
      }
      addStartupLog("Start command executed successfully")

      updateStartingState(StartupPhase.WaitingForBinder)
      addStartupLog("Waiting for binder service (timeout: ${DaemonConfig.Timeouts.BINDER_WAIT_MS}ms)...")
      val binderAvailable = waitForBinderServiceWithProgress(DaemonConfig.Timeouts.BINDER_WAIT_MS)
      if (!binderAvailable) {
        addStartupLog("Binder service not available after ${DaemonConfig.Timeouts.BINDER_WAIT_MS}ms")
        val failedState = DaemonLifecycleState.Failed(
          reason = "Binder service not available after ${DaemonConfig.Timeouts.BINDER_WAIT_MS}ms",
          diagnostic = LifecycleDiagnostic.BinderUnavailable(DaemonConfig.Timeouts.BINDER_WAIT_MS),
          logs = startupLogs.toList(),
        )
        _daemonLifecycle.value = failedState
        return@withContext Result.failure(DaemonLifecycleException(failedState))
      }
      addStartupLog("Binder service available")

      updateStartingState(StartupPhase.VerifyingVersion)
      addStartupLog("Verifying daemon version...")
      val versionCheckResult = verifyDaemonVersion()
      if (versionCheckResult.isFailure) {
        val exception = versionCheckResult.exceptionOrNull()

        // Handle version mismatch by redeploying and restarting
        if (exception is DaemonLifecycleException) {
          val diagnostic = exception.state.diagnostic
          if (diagnostic is LifecycleDiagnostic.VersionMismatch) {
            addStartupLog("Version mismatch: expected=${diagnostic.expected}, actual=${diagnostic.actual}")
            addStartupLog("Attempting to redeploy and restart...")

            // Stop old daemon, redeploy, and restart (with retry limit)
            val redeployResult = handleVersionMismatch()
            if (redeployResult.isFailure) {
              addStartupLog("Redeploy failed: ${redeployResult.exceptionOrNull()?.message}")
              _daemonLifecycle.value = DaemonLifecycleState.Failed(
                reason = "Failed to update daemon after version mismatch",
                diagnostic = diagnostic,
                cause = redeployResult.exceptionOrNull(),
                logs = startupLogs.toList(),
              )
              return@withContext redeployResult
            }

            addStartupLog("Redeploy successful")
            val pid = getDaemonPid()
            _daemonLifecycle.value = DaemonLifecycleState.Running(pid = pid)
            return@withContext Result.success(Unit)
          }
        }

        addStartupLog("Version check failed: ${exception?.message}")
        _daemonLifecycle.value = exception?.let {
          if (it is DaemonLifecycleException) it.state.copy(logs = startupLogs.toList())
          else DaemonLifecycleState.Failed(
            it.message ?: "Version check failed",
            cause = it,
            logs = startupLogs.toList(),
          )
        } ?: DaemonLifecycleState.Failed("Version check failed", logs = startupLogs.toList())
        return@withContext versionCheckResult
      }

      updateStartingState(StartupPhase.Connecting)
      addStartupLog("Connecting to daemon...")

      val pid = getDaemonPid()
      val elapsed = System.currentTimeMillis() - startupStartTime
      addStartupLog("Daemon started successfully (pid=$pid, elapsed=${elapsed}ms)")
      _daemonLifecycle.value = DaemonLifecycleState.Running(pid = pid)
      Result.success(Unit)
    }
  }


  override suspend fun stopDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
    mutex.withLock {
      stopDaemonInternal()
    }
  }

  private suspend fun stopDaemonInternal(): Result<Unit> {
    val currentState = _daemonLifecycle.value
    if (currentState is DaemonLifecycleState.Stopped) {
      return Result.success(Unit)
    }

    _daemonLifecycle.value = DaemonLifecycleState.Stopping
    cancelKeepAlive()

    rootShell.execute(DaemonConfig.Commands.KILL, timeoutMs = 5_000)

    delay(100)

    val stillRunning = isDaemonProcessRunning()
    if (stillRunning) {
      rootShell.execute(DaemonConfig.Commands.KILL_SIGKILL, timeoutMs = 3_000)
      delay(100)
    }

    _daemonLifecycle.value = DaemonLifecycleState.Stopped
    return Result.success(Unit)
  }

  override suspend fun restartDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
    mutex.withLock {
      stopDaemonInternal()
      delay(200)
    }
    startDaemon()
  }

  override fun isDaemonRunning(): Boolean {
    return _daemonLifecycle.value is DaemonLifecycleState.Running
  }

  override fun scheduleKeepAlive() {
    cancelKeepAlive()
    keepAliveJob = scope.launch {
      delay(keepAliveMs)
      mutex.withLock {
        if (_daemonLifecycle.value is DaemonLifecycleState.Running) {
          stopDaemonInternal()
        }
      }
    }
  }

  override fun cancelKeepAlive() {
    keepAliveJob?.cancel()
    keepAliveJob = null
  }

  override fun setKeepAliveMs(ms: Long) {
    keepAliveMs = ms.coerceAtLeast(DaemonConfig.Lifecycle.MIN_KEEP_ALIVE_MS)
  }

  override fun close() {
    cancelKeepAlive()
    scope.launch {
      stopDaemon()
    }
  }

  private suspend fun executeDaemonStart(): Result<Unit> {
    val command = buildStartCommand()
    val result = rootShell.execute(command, timeoutMs = 5_000)
    Log.d(TAG, "Shell result: $result")

    return when (result) {
      is ShellResult.Success -> {
        if (result.exitCode == 0 || result.output.isEmpty()) {
          Result.success(Unit)
        } else {
          val diagnostic = analyzeDaemonStartFailure(result)
          Result.failure(
            DaemonLifecycleException(
              DaemonLifecycleState.Failed(
                reason = "Daemon start failed with exit code ${result.exitCode}",
                diagnostic = diagnostic,
              ),
            ),
          )
        }
      }

      is ShellResult.Error -> {
        val diagnostic = analyzeShellError(result)
        Result.failure(
          DaemonLifecycleException(
            DaemonLifecycleState.Failed(
              reason = result.message,
              diagnostic = diagnostic,
              cause = result.exception,
            ),
          ),
        )
      }

      is ShellResult.Timeout -> {
        Result.failure(
          DaemonLifecycleException(
            DaemonLifecycleState.Failed(
              reason = "Daemon start timed out",
              diagnostic = LifecycleDiagnostic.StartupTimeout(result.timeoutMs),
            ),
          ),
        )
      }

      is ShellResult.RootDenied -> {
        Result.failure(
          DaemonLifecycleException(
            DaemonLifecycleState.Failed(
              reason = result.reason,
              diagnostic = LifecycleDiagnostic.RootUnavailable(result.reason),
            ),
          ),
        )
      }
    }
  }


  private fun buildStartCommand(): String {
    val packageName = BuildConfig.APPLICATION_ID
    val isDebug = BuildConfig.DEBUG
    val command = DaemonConfig.Commands.startDaemon(packageName, isDebug)
    Log.d(TAG, "Start command: $command")
    return command
  }

  private suspend fun waitForBinderService(timeoutMs: Long): Boolean {
    val startTime = System.currentTimeMillis()
    val pollInterval = DaemonConfig.Timeouts.STARTUP_POLL_INTERVAL_MS

    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val binder = getServiceBinder(DaemonConfig.SERVICE_NAME)
      if (binder != null && binder.isBinderAlive) {
        Log.d(TAG, "Binder service available after ${System.currentTimeMillis() - startTime}ms")
        return true
      }
      delay(pollInterval)
    }
    Log.w(TAG, "Binder service not available after ${timeoutMs}ms")
    return false
  }

  private suspend fun waitForBinderServiceWithProgress(timeoutMs: Long): Boolean {
    val startTime = System.currentTimeMillis()
    val pollInterval = DaemonConfig.Timeouts.STARTUP_POLL_INTERVAL_MS

    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val elapsed = System.currentTimeMillis() - startupStartTime
      val waitElapsed = System.currentTimeMillis() - startTime

      _daemonLifecycle.value = DaemonLifecycleState.Starting(
        phase = StartupPhase.WaitingForBinder,
        logs = startupLogs.toList(),
        elapsedMs = elapsed,
      )

      val binder = getServiceBinder(DaemonConfig.SERVICE_NAME)
      if (binder != null && binder.isBinderAlive) {
        addStartupLog("Binder service found after ${waitElapsed}ms")
        return true
      }
      delay(pollInterval)
    }
    return false
  }

  private suspend fun verifyDaemonVersion(): Result<Unit> {
    val connectResult = binderClient.connect()
    if (connectResult is DaemonConnectionResult.NotRunning) {
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = "Daemon not running after start",
            diagnostic = LifecycleDiagnostic.BinderUnavailable(DaemonConfig.Timeouts.BINDER_WAIT_MS),
          ),
        ),
      )
    }
    if (connectResult is DaemonConnectionResult.Failed) {
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = connectResult.error.message,
            cause = connectResult.error.cause,
          ),
        ),
      )
    }

    val versionResult = binderClient.getVersion()
    if (versionResult.isFailure) {
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = "Failed to get daemon version",
            cause = versionResult.exceptionOrNull(),
          ),
        ),
      )
    }

    val actualVersion = versionResult.getOrThrow()
    if (actualVersion != DaemonConfig.PROTOCOL_VERSION) {
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = "Daemon version mismatch: expected ${DaemonConfig.PROTOCOL_VERSION}, got $actualVersion",
            diagnostic = LifecycleDiagnostic.VersionMismatch(
              expected = DaemonConfig.PROTOCOL_VERSION,
              actual = actualVersion,
            ),
          ),
        ),
      )
    }

    return Result.success(Unit)
  }

  /**
   * Handle version mismatch by stopping old daemon, redeploying, and restarting.
   * Returns success if the new daemon is running with correct version.
   */
  private suspend fun handleVersionMismatch(): Result<Unit> {
    Log.i(TAG, "Handling version mismatch: stopping old daemon and redeploying")

    // Stop the old daemon
    stopDaemonInternal()
    delay(200)

    // Force redeploy
    val deployResult = daemonDeployer.deploy(forceRedeploy = true)
    if (deployResult !is DeploymentState.Deployed) {
      val reason = when (deployResult) {
        is DeploymentState.Failed -> deployResult.reason
        else -> "Redeployment failed"
      }
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = reason,
            diagnostic = LifecycleDiagnostic.BinaryMissing,
          ),
        ),
      )
    }

    // Start the new daemon
    val startResult = executeDaemonStart()
    if (startResult.isFailure) {
      return startResult
    }

    // Wait for binder service
    val binderAvailable = waitForBinderService(DaemonConfig.Timeouts.BINDER_WAIT_MS)
    if (!binderAvailable) {
      return Result.failure(
        DaemonLifecycleException(
          DaemonLifecycleState.Failed(
            reason = "Binder service not available after redeploy",
            diagnostic = LifecycleDiagnostic.BinderUnavailable(DaemonConfig.Timeouts.BINDER_WAIT_MS),
          ),
        ),
      )
    }

    // Verify version again
    val versionCheckResult = verifyDaemonVersion()
    if (versionCheckResult.isFailure) {
      val exception = versionCheckResult.exceptionOrNull()
      if (exception is DaemonLifecycleException &&
        exception.state.diagnostic is LifecycleDiagnostic.VersionMismatch
      ) {
        // Still mismatched after redeploy - this is a serious error
        Log.e(TAG, "Version still mismatched after redeploy")
      }
      return versionCheckResult
    }

    return Result.success(Unit)
  }

  private suspend fun getDaemonPid(): Int? {
    val result = rootShell.execute(DaemonConfig.Commands.GET_PID, timeoutMs = 2_000)
    return if (result.isSuccess()) {
      (result as ShellResult.Success).output.trim().toIntOrNull()
    } else {
      null
    }
  }

  private suspend fun isDaemonProcessRunning(): Boolean {
    val result = rootShell.execute(DaemonConfig.Commands.GET_PID, timeoutMs = 2_000)
    return result.isSuccess() && (result as ShellResult.Success).output.trim().isNotEmpty()
  }

  private fun analyzeDaemonStartFailure(result: ShellResult.Success): LifecycleDiagnostic? {
    val output = result.output.lowercase()
    return when {
      output.contains("permission denied") -> {
        LifecycleDiagnostic.PermissionError(DaemonConfig.BINARY_PATH, "execute")
      }

      output.contains("selinux") || output.contains("avc:") -> {
        LifecycleDiagnostic.SELinuxDenied(result.output)
      }

      output.contains("not found") || output.contains("no such file") -> {
        LifecycleDiagnostic.BinaryMissing
      }

      else -> {
        LifecycleDiagnostic.ProcessCrashed(result.exitCode, result.output)
      }
    }
  }

  private fun analyzeShellError(result: ShellResult.Error): LifecycleDiagnostic? {
    val message = result.message.lowercase()
    val stderr = result.stderr?.lowercase() ?: ""
    return when {
      message.contains("permission") || stderr.contains("permission") -> {
        LifecycleDiagnostic.PermissionError(DaemonConfig.BINARY_PATH, "execute")
      }

      message.contains("selinux") || stderr.contains("avc:") -> {
        LifecycleDiagnostic.SELinuxDenied(result.stderr ?: result.message)
      }

      else -> null
    }
  }

  companion object {
    private const val TAG = "DaemonLifecycleManager"

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

class DaemonLifecycleException(
  val state: DaemonLifecycleState.Failed,
) : Exception(state.reason, state.cause)
