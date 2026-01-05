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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a rate limit check.
 */
sealed interface RateLimitResult {

  data object Allowed : RateLimitResult

  data class Limited(
    val retryAfterMs: Long,
    val currentCount: Int,
    val limit: Int,
  ) : RateLimitResult
}

/**
 * IP-based rate limiter using sliding window algorithm.
 */
interface RateLimiter {
  /** Set of currently rate-limited IPs (for monitoring) */
  val rateLimitedIps: StateFlow<Set<String>>

  /** Check if a request from the given IP is allowed */
  fun checkRequest(ip: String): RateLimitResult

  /** Record a request from the given IP */
  fun recordRequest(ip: String)

  fun clearIp(ip: String)

  fun clearAll()

  fun updateLimit(qps: Int)
}

@Single(binds = [RateLimiter::class])
class RateLimiterImpl(
  private val gatewayConfig: GatewayConfig,
) : RateLimiter {

  companion object {
    private const val TAG = "RateLimiter"
    private const val WINDOW_SIZE_MS = 1000L // 1 second window
    private const val CLEANUP_INTERVAL_MS = 60_000L // Cleanup every minute
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  // Map of IP -> list of request timestamps within the window
  private val requestWindows = ConcurrentHashMap<String, MutableList<Long>>()

  private val _rateLimitedIps = MutableStateFlow<Set<String>>(emptySet())
  override val rateLimitedIps: StateFlow<Set<String>> = _rateLimitedIps.asStateFlow()

  @Volatile
  private var currentLimit: Int = GatewayConfigData.DEFAULT_RATE_LIMIT_QPS

  init {
    scope.launch {
      gatewayConfig.config.collect { config ->
        currentLimit = config.rateLimitQps
      }
    }

    // Periodic cleanup of old entries
    scope.launch {
      while (true) {
        delay(CLEANUP_INTERVAL_MS)
        cleanup()
      }
    }
  }

  override fun checkRequest(ip: String): RateLimitResult {
    val now = System.currentTimeMillis()
    val windowStart = now - WINDOW_SIZE_MS

    val timestamps = requestWindows[ip] ?: return RateLimitResult.Allowed

    // Count requests within the window
    val recentRequests = synchronized(timestamps) {
      timestamps.removeAll { it < windowStart }
      timestamps.size
    }

    return if (recentRequests >= currentLimit) {
      // Calculate retry-after based on oldest request in window
      val oldestInWindow = synchronized(timestamps) {
        timestamps.minOrNull() ?: now
      }
      val retryAfter = (oldestInWindow + WINDOW_SIZE_MS) - now

      updateRateLimitedIps(ip, true)
      Log.w(TAG, "Rate limited IP: $ip (${recentRequests}/$currentLimit requests)")

      RateLimitResult.Limited(
        retryAfterMs = maxOf(retryAfter, 0),
        currentCount = recentRequests,
        limit = currentLimit,
      )
    } else {
      updateRateLimitedIps(ip, false)
      RateLimitResult.Allowed
    }
  }

  override fun recordRequest(ip: String) {
    val now = System.currentTimeMillis()
    val timestamps = requestWindows.computeIfAbsent(ip) { mutableListOf() }

    synchronized(timestamps) {
      timestamps.add(now)
      // Keep only recent timestamps to prevent memory growth
      val windowStart = now - WINDOW_SIZE_MS
      timestamps.removeAll { it < windowStart }
    }
  }

  override fun clearIp(ip: String) {
    requestWindows.remove(ip)
    updateRateLimitedIps(ip, false)
  }

  override fun clearAll() {
    requestWindows.clear()
    _rateLimitedIps.value = emptySet()
  }

  override fun updateLimit(qps: Int) {
    currentLimit = qps.coerceIn(
      GatewayConfigData.MIN_RATE_LIMIT,
      GatewayConfigData.MAX_RATE_LIMIT,
    )
  }

  private fun cleanup() {
    val now = System.currentTimeMillis()
    val windowStart = now - WINDOW_SIZE_MS

    val iterator = requestWindows.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      synchronized(entry.value) {
        entry.value.removeAll { it < windowStart }
        if (entry.value.isEmpty()) {
          iterator.remove()
        }
      }
    }

    // Update rate limited IPs set
    val stillLimited = requestWindows.entries
      .filter { (_, timestamps) ->
        synchronized(timestamps) { timestamps.size >= currentLimit }
      }
      .map { it.key }
      .toSet()

    _rateLimitedIps.value = stillLimited
  }

  private fun updateRateLimitedIps(ip: String, isLimited: Boolean) {
    val current = _rateLimitedIps.value
    _rateLimitedIps.value = if (isLimited) {
      current + ip
    } else {
      current - ip
    }
  }
}
