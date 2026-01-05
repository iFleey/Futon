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

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.getErrorMessage
import me.fleey.futon.platform.root.getOutputOrNull
import me.fleey.futon.platform.root.isSuccess
import org.koin.core.annotation.Single
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface SecurityEventType {
  val code: String

  data object AuthAttempt : SecurityEventType {
    override val code = "AUTH_ATTEMPT"
  }

  data object AuthSuccess : SecurityEventType {
    override val code = "AUTH_SUCCESS"
  }

  data object AuthFailed : SecurityEventType {
    override val code = "AUTH_FAILED"
  }

  data object SessionCreated : SecurityEventType {
    override val code = "SESSION_CREATED"
  }

  data object SessionExpired : SecurityEventType {
    override val code = "SESSION_EXPIRED"
  }

  data object SessionDestroyed : SecurityEventType {
    override val code = "SESSION_DESTROYED"
  }

  data object CallRejected : SecurityEventType {
    override val code = "CALL_REJECTED"
  }

  data object KeyDeployed : SecurityEventType {
    override val code = "KEY_DEPLOYED"
  }

  data object KeyRotated : SecurityEventType {
    override val code = "KEY_ROTATED"
  }

  data object DaemonStarted : SecurityEventType {
    override val code = "DAEMON_STARTED"
  }

  data object DaemonStopped : SecurityEventType {
    override val code = "DAEMON_STOPPED"
  }

  data class Custom(override val code: String) : SecurityEventType
}

data class SecurityLogEntry(
  val timestamp: Long,
  val eventType: SecurityEventType,
  val uid: Int,
  val pid: Int,
  val details: String,
) {
  fun format(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val formattedTime = dateFormat.format(Date(timestamp))
    return "[$formattedTime] [${eventType.code}] [uid=$uid] [pid=$pid] $details"
  }
}

interface SecurityAuditLogger {
  val recentLogs: StateFlow<List<SecurityLogEntry>>
  val logEvents: SharedFlow<SecurityLogEntry>
  val isEnabled: StateFlow<Boolean>

  fun logAuthAttempt(instanceId: String, success: Boolean, reason: String? = null)
  fun logSessionEvent(eventType: SecurityEventType, instanceId: String, details: String = "")
  fun logCallRejected(method: String, reason: String)
  fun logKeyEvent(eventType: SecurityEventType, keyFingerprint: String)
  fun logDaemonLifecycle(started: Boolean, version: Int? = null)
  fun logCustomEvent(eventCode: String, details: String)

  fun setEnabled(enabled: Boolean)
  suspend fun getLogContent(): Result<String>
  suspend fun clearLogs(): Result<Unit>
}

@Single(binds = [SecurityAuditLogger::class])
class SecurityAuditLoggerImpl(
  private val rootShell: RootShell,
) : SecurityAuditLogger, Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeMutex = Mutex()

  private val _recentLogs = MutableStateFlow<List<SecurityLogEntry>>(emptyList())
  override val recentLogs: StateFlow<List<SecurityLogEntry>> = _recentLogs.asStateFlow()

  private val _logEvents = MutableSharedFlow<SecurityLogEntry>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  override val logEvents: SharedFlow<SecurityLogEntry> = _logEvents.asSharedFlow()

  private val _isEnabled = MutableStateFlow(true)
  override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

  private val rateLimitCounter = AtomicInteger(0)
  private val rateLimitWindowStart = AtomicLong(System.currentTimeMillis())

  override fun logAuthAttempt(instanceId: String, success: Boolean, reason: String?) {
    val eventType = if (success) SecurityEventType.AuthSuccess else SecurityEventType.AuthFailed
    val details = buildString {
      append("instanceId=$instanceId")
      if (reason != null) {
        append(", reason=$reason")
      }
    }
    log(eventType, details)
  }

  override fun logSessionEvent(eventType: SecurityEventType, instanceId: String, details: String) {
    val fullDetails = buildString {
      append("instanceId=$instanceId")
      if (details.isNotEmpty()) {
        append(", $details")
      }
    }
    log(eventType, fullDetails)
  }

  override fun logCallRejected(method: String, reason: String) {
    log(SecurityEventType.CallRejected, "method=$method, reason=$reason")
  }

  override fun logKeyEvent(eventType: SecurityEventType, keyFingerprint: String) {
    log(eventType, "fingerprint=$keyFingerprint")
  }

  override fun logDaemonLifecycle(started: Boolean, version: Int?) {
    val eventType =
      if (started) SecurityEventType.DaemonStarted else SecurityEventType.DaemonStopped
    val details = if (version != null) "version=$version" else ""
    log(eventType, details)
  }

  override fun logCustomEvent(eventCode: String, details: String) {
    log(SecurityEventType.Custom(eventCode), details)
  }

  override fun setEnabled(enabled: Boolean) {
    _isEnabled.value = enabled
  }

  override suspend fun getLogContent(): Result<String> {
    return try {
      val result =
        rootShell.execute("cat ${DaemonConfig.SecurityAudit.LOG_FILE} 2>/dev/null || echo ''")
      if (result.isSuccess()) {
        Result.success(result.getOutputOrNull() ?: "")
      } else {
        Result.failure(Exception("Failed to read log file: ${result.getErrorMessage()}"))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read security log", e)
      Result.failure(e)
    }
  }

  override suspend fun clearLogs(): Result<Unit> {
    return try {
      writeMutex.withLock {
        val result = rootShell.execute("echo '' > ${DaemonConfig.SecurityAudit.LOG_FILE}")
        if (result.isSuccess()) {
          _recentLogs.value = emptyList()
          Result.success(Unit)
        } else {
          Result.failure(Exception("Failed to clear log file: ${result.getErrorMessage()}"))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear security log", e)
      Result.failure(e)
    }
  }

  private fun log(eventType: SecurityEventType, details: String) {
    if (!_isEnabled.value) return

    if (!checkRateLimit()) {
      Log.w(TAG, "Rate limit exceeded, dropping log entry")
      return
    }

    val entry = SecurityLogEntry(
      timestamp = System.currentTimeMillis(),
      eventType = eventType,
      uid = Process.myUid(),
      pid = Process.myPid(),
      details = details,
    )

    updateRecentLogs(entry)

    scope.launch {
      _logEvents.emit(entry)
      writeToFile(entry)
    }
  }

  private fun checkRateLimit(): Boolean {
    val now = System.currentTimeMillis()
    val windowStart = rateLimitWindowStart.get()

    if (now - windowStart >= DaemonConfig.SecurityAudit.RATE_LIMIT_WINDOW_MS) {
      rateLimitWindowStart.set(now)
      rateLimitCounter.set(1)
      return true
    }

    val count = rateLimitCounter.incrementAndGet()
    return count <= DaemonConfig.SecurityAudit.MAX_ENTRIES_PER_MINUTE
  }

  private fun updateRecentLogs(entry: SecurityLogEntry) {
    val current = _recentLogs.value.toMutableList()
    current.add(0, entry)
    val maxLogs = DaemonConfig.SecurityAudit.MAX_RECENT_LOGS_IN_MEMORY
    if (current.size > maxLogs) {
      _recentLogs.value = current.take(maxLogs)
    } else {
      _recentLogs.value = current
    }
  }

  private suspend fun writeToFile(entry: SecurityLogEntry) {
    writeMutex.withLock {
      try {
        ensureLogDirectory()
        rotateLogsIfNeeded()

        val logLine = entry.format()
        val escapedLine = logLine.replace("'", "'\\''")
        val result =
          rootShell.execute("echo '$escapedLine' >> ${DaemonConfig.SecurityAudit.LOG_FILE}")

        if (!result.isSuccess()) {
          Log.e(TAG, "Failed to write log entry: ${result.getErrorMessage()}")
        } else {
          // Ignore
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error writing to security log", e)
      }
    }
  }

  private suspend fun ensureLogDirectory() {
    rootShell.execute("mkdir -p ${DaemonConfig.BASE_DIR}")
    rootShell.execute("chmod 700 ${DaemonConfig.BASE_DIR}")
  }

  private suspend fun rotateLogsIfNeeded() {
    val logFile = DaemonConfig.SecurityAudit.LOG_FILE
    val sizeResult = rootShell.execute("stat -c%s $logFile 2>/dev/null || echo 0")
    val currentSize = (sizeResult.getOutputOrNull() ?: "0").trim().toLongOrNull() ?: 0

    if (currentSize >= DaemonConfig.SecurityAudit.MAX_LOG_SIZE_BYTES) {
      val maxFiles = DaemonConfig.SecurityAudit.MAX_LOG_FILES
      for (i in (maxFiles - 1) downTo 1) {
        val oldFile = "$logFile.$i"
        val newFile = "$logFile.${i + 1}"
        rootShell.execute("mv $oldFile $newFile 2>/dev/null")
      }

      rootShell.execute("mv $logFile $logFile.1")
      rootShell.execute("touch $logFile")
      rootShell.execute("chmod 600 $logFile")

      rootShell.execute("rm -f $logFile.${maxFiles + 1}")

      Log.d(TAG, "Log rotation completed")
    }
  }

  override fun close() {
    Log.d(TAG, "SecurityAuditLogger closed")
  }

  companion object {
    private const val TAG = "SecurityAuditLogger"
  }
}
