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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.fleey.futon.domain.automation.AutomationEngine
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.service.gateway.models.BindingState
import me.fleey.futon.service.gateway.models.ServerState
import me.fleey.futon.service.gateway.models.StopReason
import me.fleey.futon.service.gateway.models.UnboundReason
import org.koin.core.annotation.Single
import java.time.Instant

/**
 * LAN HTTP server for external automation triggers.
 * Uses Ktor embedded server with Netty engine.
 */
interface LanHttpServer {

  val serverState: StateFlow<ServerState>


  suspend fun start()


  suspend fun stop(reason: StopReason = StopReason.USER_REQUESTED)


  suspend fun restart()
}

@Single(binds = [LanHttpServer::class])
class LanHttpServerImpl(
  private val automationEngine: AutomationEngine,
  private val smartBindingStrategy: SmartBindingStrategy,
  private val tokenManager: TokenManager,
  private val rateLimiter: RateLimiter,
  private val idleTimeoutManager: IdleTimeoutManager,
  private val serviceDiscovery: ServiceDiscovery,
  private val tlsManager: TlsManager,
  private val gatewayConfig: GatewayConfig,
  private val auditLogger: AuditLogger,
  private val json: Json,
) : LanHttpServer, BindingChangeListener, IdleTimeoutListener {

  companion object {
    private const val TAG = "LanHttpServer"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _serverState = MutableStateFlow<ServerState>(
    ServerState.Stopped(StopReason.NOT_STARTED),
  )
  override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

  private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

  init {
    smartBindingStrategy.addBindingChangeListener(this)
    idleTimeoutManager.addListener(this)
  }

  override suspend fun start() = withContext(Dispatchers.IO) {
    if (_serverState.value is ServerState.Running) {
      Log.d(TAG, "Server already running")
      return@withContext
    }

    _serverState.value = ServerState.Starting

    val config = gatewayConfig.config.first()
    when (val bindingResult = smartBindingStrategy.getBindingAddress(config.serverPort)) {
      is BindingResult.Success -> {
        try {
          startServer(
            host = bindingResult.ipAddress,
            port = bindingResult.port,
            useTls = config.enableTls && tlsManager.tlsState.value is me.fleey.futon.service.gateway.models.TlsState.Enabled,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to start server", e)
          _serverState.value = ServerState.Error(
            reason = e.message ?: "Unknown error",
            exception = e,
          )
          smartBindingStrategy.markUnbound(UnboundReason.BINDING_ERROR)
        }
      }

      is BindingResult.Failure -> {
        Log.w(TAG, "Cannot start server: ${bindingResult.reason}")
        _serverState.value = ServerState.Stopped(
          when (bindingResult.reason) {
            UnboundReason.CELLULAR_ONLY -> StopReason.NETWORK_UNAVAILABLE
            UnboundReason.UNTRUSTED_NETWORK -> StopReason.UNTRUSTED_NETWORK
            else -> StopReason.NETWORK_UNAVAILABLE
          },
        )
      }
    }
  }

  override suspend fun stop(reason: StopReason) = withContext(Dispatchers.IO) {
    Log.i(TAG, "Stopping server: $reason")

    server?.stop(1000, 2000)
    server = null

    serviceDiscovery.unregisterService()
    idleTimeoutManager.stop()

    _serverState.value = ServerState.Stopped(reason)
    smartBindingStrategy.markUnbound(
      when (reason) {
        StopReason.USER_REQUESTED -> UnboundReason.DISABLED
        StopReason.IDLE_TIMEOUT -> UnboundReason.DISABLED
        StopReason.NETWORK_UNAVAILABLE -> UnboundReason.NO_SUITABLE_INTERFACE
        StopReason.UNTRUSTED_NETWORK -> UnboundReason.UNTRUSTED_NETWORK
        StopReason.SERVICE_DESTROYED -> UnboundReason.NOT_STARTED
        StopReason.NOT_STARTED -> UnboundReason.NOT_STARTED
      },
    )
  }

  override suspend fun restart() {
    stop(StopReason.USER_REQUESTED)
    start()
  }

  override fun onBindingChanged(oldState: BindingState, newState: BindingState) {
    scope.launch {
      when (newState) {
        is BindingState.Bound -> {
          if (_serverState.value is ServerState.Running) {
            val currentState = _serverState.value as ServerState.Running
            if (currentState.ipAddress != newState.ipAddress) {
              Log.i(TAG, "IP changed, restarting server")
              tokenManager.onIpChanged()
              restart()
            }
          }
        }

        is BindingState.Unbound -> {
          if (_serverState.value is ServerState.Running) {
            stop(
              when (newState.reason) {
                UnboundReason.CELLULAR_ONLY,
                UnboundReason.NO_SUITABLE_INTERFACE,
                  -> StopReason.NETWORK_UNAVAILABLE

                UnboundReason.UNTRUSTED_NETWORK -> StopReason.UNTRUSTED_NETWORK
                else -> StopReason.NETWORK_UNAVAILABLE
              },
            )
          }
        }

        is BindingState.Rebinding -> {
          // Wait for rebinding to complete
        }
      }
    }
  }

  override fun onIdleTimeoutWarning(remainingTime: kotlin.time.Duration) {
    Log.w(TAG, "Idle timeout warning: $remainingTime remaining")
  }

  override fun onIdleTimeoutExpired() {
    scope.launch {
      Log.i(TAG, "Idle timeout expired, stopping server")
      stop(StopReason.IDLE_TIMEOUT)
    }
  }

  private fun startServer(host: String, port: Int, useTls: Boolean) {
    Log.i(TAG, "Starting server on $host:$port (TLS: $useTls)")

    server = embeddedServer(Netty, port = port, host = host) {
      configureServer()
    }.start(wait = false)

    _serverState.value = ServerState.Running(
      ipAddress = host,
      port = port,
      useTls = useTls,
    )

    // Register mDNS service
    serviceDiscovery.registerService(port)

    // Start idle timeout timer
    idleTimeoutManager.start()

    scope.launch {
      tokenManager.onServerStart()
    }

    Log.i(TAG, "Server started successfully")
  }

  private fun Application.configureServer() {
    install(ContentNegotiation) {
      json(json)
    }

    install(StatusPages) {
      exception<Throwable> { call, cause ->
        Log.e(TAG, "Unhandled exception", cause)
        call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(error = cause.message ?: "Internal server error"),
        )
      }
    }

    install(Authentication) {
      bearer("auth-bearer") {
        authenticate { tokenCredential ->
          if (tokenManager.validateLanToken(tokenCredential.token)) {
            UserIdPrincipal("lan-client")
          } else {
            null
          }
        }
      }
    }

    // Rate limiting interceptor
    intercept(ApplicationCallPipeline.Plugins) {
      val clientIp = call.request.local.remoteAddress

      val result = rateLimiter.checkRequest(clientIp)
      if (result is RateLimitResult.Limited) {
        auditLogger.log(
          AuditEvent.RateLimited(
            sourceIp = clientIp,
            endpoint = call.request.local.uri,
          ),
        )
        call.respond(
          HttpStatusCode.TooManyRequests,
          ErrorResponse(
            error = "Rate limit exceeded",
            retryAfterMs = result.retryAfterMs,
          ),
        )
        finish()
        return@intercept
      }

      // Record request
      rateLimiter.recordRequest(clientIp)

      // Reset idle timeout
      idleTimeoutManager.recordActivity()
    }

    routing {
      authenticate("auth-bearer") {
        post("/api/v1/execute") {
          val clientIp = call.request.local.remoteAddress
          val payload = call.receiveText()

          auditLogger.log(
            AuditEvent.ExecuteRequest(
              sourceIp = clientIp,
              payload = payload.take(100),
            ),
          )

          try {
            val response = when (val result = automationEngine.startTask(payload)) {
              is AutomationResult.Success -> ExecuteResponse(
                success = true,
                message = "Task completed successfully",
              )

              is AutomationResult.Failure -> ExecuteResponse(
                success = false,
                message = result.reason,
              )

              is AutomationResult.Timeout -> ExecuteResponse(
                success = false,
                message = "Task timed out",
              )

              is AutomationResult.Cancelled -> ExecuteResponse(
                success = false,
                message = "Task was cancelled",
              )
            }

            auditLogger.log(
              AuditEvent.ExecuteResult(
                sourceIp = clientIp,
                success = response.success,
                message = response.message,
              ),
            )

            call.respond(response)
          } catch (e: Exception) {
            auditLogger.log(
              AuditEvent.ExecuteResult(
                sourceIp = clientIp,
                success = false,
                message = e.message ?: "Unknown error",
              ),
            )
            call.respond(
              HttpStatusCode.InternalServerError,
              ErrorResponse(error = e.message ?: "Unknown error"),
            )
          }
        }

        get("/api/v1/status") {
          val clientIp = call.request.local.remoteAddress

          auditLogger.log(
            AuditEvent.StatusRequest(sourceIp = clientIp),
          )

          val state = automationEngine.state.value
          call.respond(
            StatusResponse(
              automationState = state.toString(),
              serverUptime = getServerUptime(),
            ),
          )
        }

        post("/api/v1/stop") {
          val clientIp = call.request.local.remoteAddress

          auditLogger.log(
            AuditEvent.StopRequest(sourceIp = clientIp),
          )

          automationEngine.stopTask()
          call.respond(
            ExecuteResponse(
              success = true,
              message = "Task stopped",
            ),
          )
        }
      }
    }
  }

  private fun getServerUptime(): Long {
    val state = _serverState.value
    return if (state is ServerState.Running) {
      System.currentTimeMillis() // Simplified,  would need to track start time
    } else {
      0
    }
  }
}

// Response models
@Serializable
data class ExecuteResponse(
  val success: Boolean,
  val message: String,
)

@Serializable
data class StatusResponse(
  val automationState: String,
  val serverUptime: Long,
)

@Serializable
data class ErrorResponse(
  val error: String,
  val retryAfterMs: Long? = null,
)

data class UserIdPrincipal(val name: String)

// Audit logging
sealed interface AuditEvent {
  val timestamp: Instant get() = Instant.now()

  data class ExecuteRequest(val sourceIp: String, val payload: String) : AuditEvent
  data class ExecuteResult(val sourceIp: String, val success: Boolean, val message: String) :
    AuditEvent

  data class StatusRequest(val sourceIp: String) : AuditEvent
  data class StopRequest(val sourceIp: String) : AuditEvent
  data class RateLimited(val sourceIp: String, val endpoint: String) : AuditEvent
  data class AuthFailed(val sourceIp: String) : AuditEvent
}

interface AuditLogger {
  fun log(event: AuditEvent)
}

@Single(binds = [AuditLogger::class])
class AuditLoggerImpl : AuditLogger {
  companion object {
    private const val TAG = "GatewayAudit"
  }

  override fun log(event: AuditEvent) {
    val message = when (event) {
      is AuditEvent.ExecuteRequest ->
        "[EXECUTE] ${event.sourceIp} - payload: ${event.payload}"

      is AuditEvent.ExecuteResult ->
        "[RESULT] ${event.sourceIp} - success: ${event.success}, message: ${event.message}"

      is AuditEvent.StatusRequest ->
        "[STATUS] ${event.sourceIp}"

      is AuditEvent.StopRequest ->
        "[STOP] ${event.sourceIp}"

      is AuditEvent.RateLimited ->
        "[RATE_LIMITED] ${event.sourceIp} - endpoint: ${event.endpoint}"

      is AuditEvent.AuthFailed ->
        "[AUTH_FAILED] ${event.sourceIp}"
    }
    Log.i(TAG, "[${event.timestamp}] $message")
  }
}
