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
package me.fleey.futon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.fleey.futon.MainActivity
import me.fleey.futon.R
import me.fleey.futon.data.daemon.DaemonRepository
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.domain.automation.AutomationEngine
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.service.gateway.GatewayConfig
import me.fleey.futon.service.gateway.LanHttpServer
import me.fleey.futon.service.gateway.NetworkStateMonitor
import me.fleey.futon.service.gateway.TrustedNetworkManager
import me.fleey.futon.service.gateway.models.ServerState
import me.fleey.futon.service.gateway.models.StopReason
import me.fleey.futon.service.gateway.models.isRunning
import org.koin.android.ext.android.inject

/**
 * Foreground service for running automation tasks and managing the LAN HTTP gateway.
 * Prevents Android from killing the process or restricting network access
 * when the app is in background.
 */
class AutomationForegroundService : Service() {

  companion object {
    private const val TAG = "AutomationService"
    private const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "automation_channel"

    private const val ACTION_START_TASK = "me.fleey.futon.START_TASK"
    private const val ACTION_STOP_TASK = "me.fleey.futon.STOP_TASK"
    private const val ACTION_START_SERVER = "me.fleey.futon.START_SERVER"
    private const val ACTION_STOP_SERVER = "me.fleey.futon.STOP_SERVER"
    private const val EXTRA_TASK_DESCRIPTION = "task_description"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun startTask(context: Context, taskDescription: String) {
      val intent = Intent(context, AutomationForegroundService::class.java).apply {
        action = ACTION_START_TASK
        putExtra(EXTRA_TASK_DESCRIPTION, taskDescription)
      }
      context.startForegroundService(intent)
    }

    fun stopTask(context: Context) {
      val intent = Intent(context, AutomationForegroundService::class.java).apply {
        action = ACTION_STOP_TASK
      }
      context.startService(intent)
    }

    fun startServer(context: Context) {
      val intent = Intent(context, AutomationForegroundService::class.java).apply {
        action = ACTION_START_SERVER
      }
      context.startForegroundService(intent)
    }

    fun stopServer(context: Context) {
      val intent = Intent(context, AutomationForegroundService::class.java).apply {
        action = ACTION_STOP_SERVER
      }
      context.startService(intent)
    }
  }

  private val automationEngine: AutomationEngine by inject()
  private val daemonRepository: DaemonRepository by inject()
  private val networkStateMonitor: NetworkStateMonitor by inject()
  private val trustedNetworkManager: TrustedNetworkManager by inject()
  private val lanHttpServer: LanHttpServer by inject()
  private val gatewayConfig: GatewayConfig by inject()

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val binder = LocalBinder()

  private val _lastResult = MutableStateFlow<AutomationResult?>(null)
  val lastResult: StateFlow<AutomationResult?> = _lastResult.asStateFlow()

  inner class LocalBinder : Binder() {
    fun getService(): AutomationForegroundService = this@AutomationForegroundService
  }

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "Service created")
    createNotificationChannel()
    networkStateMonitor.startMonitoring()
    observeStateChanges()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START_TASK -> {
        val taskDescription = intent.getStringExtra(EXTRA_TASK_DESCRIPTION)
        if (taskDescription != null) {
          startForegroundWithNotification()
          executeTask(taskDescription)
        }
      }

      ACTION_STOP_TASK -> {
        stopCurrentTask()
      }

      ACTION_START_SERVER -> {
        startForegroundWithNotification()
        startHttpServer()
      }

      ACTION_STOP_SERVER -> {
        stopHttpServer()
      }
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "Service destroyed")
    networkStateMonitor.stopMonitoring()
    serviceScope.launch {
      lanHttpServer.stop(StopReason.SERVICE_DESTROYED)
    }
    serviceScope.cancel()
    _isRunning.value = false
  }

  private fun observeStateChanges() {
    // Observe daemon state, server state, and automation state for notification updates
    serviceScope.launch {
      combine(
        daemonRepository.daemonState,
        lanHttpServer.serverState,
        automationEngine.state,
        trustedNetworkManager.isCurrentNetworkTrusted,
      ) { daemonState, serverState, automationState, isTrusted ->
        NotificationData(daemonState, serverState, automationState, isTrusted)
      }.collect { data ->
        if (_isRunning.value) {
          updateNotificationFromState(data)
        }
      }
    }

    serviceScope.launch {
      combine(
        trustedNetworkManager.isCurrentNetworkTrusted,
        gatewayConfig.config,
      ) { isTrusted, config ->
        Pair(isTrusted, config.serverEnabled)
      }.collect { (isTrusted, serverEnabled) ->
        if (serverEnabled && _isRunning.value) {
          if (isTrusted && !lanHttpServer.serverState.value.isRunning) {
            Log.i(TAG, "Trusted network detected, starting server")
            lanHttpServer.start()
          } else if (!isTrusted && lanHttpServer.serverState.value.isRunning) {
            Log.i(TAG, "Untrusted network detected, stopping server")
            lanHttpServer.stop(StopReason.UNTRUSTED_NETWORK)
          }
        }
      }
    }
  }

  private fun startForegroundWithNotification() {
    val notification = createNotification(
      title = getString(R.string.automation_running),
      content = getString(R.string.automation_running_description),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    _isRunning.value = true
  }

  private fun executeTask(taskDescription: String) {
    serviceScope.launch {
      Log.i(TAG, "Starting task: $taskDescription")

      updateNotification(
        title = getString(R.string.automation_running),
        content = taskDescription.take(50) + if (taskDescription.length > 50) "..." else "",
      )

      try {
        val result = automationEngine.startTask(taskDescription)
        _lastResult.value = result

        Log.i(TAG, "Task completed: $result")

        updateNotification(
          title = when (result) {
            is AutomationResult.Success -> getString(R.string.task_completed)
            is AutomationResult.Failure -> getString(R.string.task_failed)
            is AutomationResult.Timeout -> getString(R.string.task_timeout)
            is AutomationResult.Cancelled -> getString(R.string.task_cancelled)
          },
          content = when (result) {
            is AutomationResult.Success -> getString(R.string.task_completed_description)
            is AutomationResult.Failure -> result.reason
            is AutomationResult.Timeout -> getString(R.string.task_timeout_description)
            is AutomationResult.Cancelled -> getString(R.string.task_cancelled_by_user)
          },
        )
      } catch (e: Exception) {
        Log.e(TAG, "Task execution error", e)
        _lastResult.value = AutomationResult.Failure(e.message ?: "Unknown error")

        updateNotification(
          title = getString(R.string.task_failed),
          content = e.message ?: "Unknown error",
        )
      } finally {
        if (!lanHttpServer.serverState.value.isRunning) {
          serviceScope.launch {
            kotlinx.coroutines.delay(3000)
            if (!lanHttpServer.serverState.value.isRunning) {
              _isRunning.value = false
              stopSelf()
            }
          }
        }
      }
    }
  }

  private fun stopCurrentTask() {
    Log.i(TAG, "Stopping current task")
    automationEngine.stopTask()

    // Only stop service if server is not running
    if (!lanHttpServer.serverState.value.isRunning) {
      _isRunning.value = false
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun startHttpServer() {
    serviceScope.launch {
      val isTrusted = trustedNetworkManager.isCurrentNetworkTrusted.value
      if (isTrusted) {
        lanHttpServer.start()
      } else {
        Log.w(TAG, "Cannot start server: network not trusted")
        updateNotification(
          title = getString(R.string.server_status),
          content = getString(R.string.server_untrusted_network),
        )
      }
    }
  }

  private fun stopHttpServer() {
    serviceScope.launch {
      lanHttpServer.stop(StopReason.USER_REQUESTED)

      // Stop service if no task is running
      if (automationEngine.state.value == AutomationState.Idle) {
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
  }

  private fun updateNotificationFromState(data: NotificationData) {
    val (daemonState, serverState, automationState, isTrusted) = data

    val title = when {
      automationState != AutomationState.Idle ->
        getString(R.string.automation_running)

      serverState.isRunning ->
        getString(R.string.server_running)

      else ->
        getString(R.string.service_running)
    }

    val content = buildString {
      append(
        when (daemonState) {
          is DaemonState.Ready -> "Daemon: Ready"
          is DaemonState.Connecting -> "Daemon: Connecting..."
          is DaemonState.Authenticating -> "Daemon: Authenticating..."
          is DaemonState.Error -> "Daemon: Error"
          else -> "Daemon: ${daemonState::class.simpleName}"
        },
      )

      append(" | ")
      when (serverState) {
        is ServerState.Running -> {
          append("Server: ${serverState.fullAddress}")
        }

        is ServerState.Stopped -> {
          if (!isTrusted) {
            append("Server: Untrusted network")
          } else {
            append("Server: Stopped")
          }
        }

        is ServerState.Starting -> {
          append("Server: Starting...")
        }

        is ServerState.Error -> {
          append("Server: Error")
        }
      }
    }

    updateNotification(title, content)
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.automation_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = getString(R.string.automation_channel_description)
      setShowBadge(false)
    }

    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)
  }

  private fun createNotification(title: String, content: String): Notification {
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, AutomationForegroundService::class.java).apply {
        action = ACTION_STOP_TASK
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(content)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentIntent(pendingIntent)
      .addAction(
        R.drawable.ic_launcher_foreground,
        getString(R.string.stop),
        stopIntent,
      )
      .setOngoing(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  private fun updateNotification(title: String, content: String) {
    val notification = createNotification(title, content)
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  fun getAutomationState(): StateFlow<AutomationState> = automationEngine.state

  fun getServerState(): StateFlow<ServerState> = lanHttpServer.serverState

  private data class NotificationData(
    val daemonState: DaemonState,
    val serverState: ServerState,
    val automationState: AutomationState,
    val isTrusted: Boolean,
  )
}
