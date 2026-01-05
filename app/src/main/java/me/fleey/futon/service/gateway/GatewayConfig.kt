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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Gateway configuration data class.
 */
data class GatewayConfigData(
  val serverPort: Int = DEFAULT_SERVER_PORT,
  val idleTimeoutHours: Int = DEFAULT_IDLE_TIMEOUT_HOURS,
  val rateLimitQps: Int = DEFAULT_RATE_LIMIT_QPS,
  val autoRotateTokenOnIpChange: Boolean = DEFAULT_AUTO_ROTATE_TOKEN,
  val tokenRotationIntervalDays: Int = DEFAULT_TOKEN_ROTATION_INTERVAL_DAYS,
  val enableMdns: Boolean = DEFAULT_ENABLE_MDNS,
  val enableTls: Boolean = DEFAULT_ENABLE_TLS,
  val trustedSsids: Set<String> = emptySet(),
  val serverEnabled: Boolean = DEFAULT_SERVER_ENABLED,
) {
  companion object {
    const val DEFAULT_SERVER_PORT = 8080
    const val DEFAULT_IDLE_TIMEOUT_HOURS = 4
    const val DEFAULT_RATE_LIMIT_QPS = 5
    const val DEFAULT_AUTO_ROTATE_TOKEN = false
    const val DEFAULT_TOKEN_ROTATION_INTERVAL_DAYS = 7
    const val DEFAULT_ENABLE_MDNS = true
    const val DEFAULT_ENABLE_TLS = false
    const val DEFAULT_SERVER_ENABLED = false

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MIN_RATE_LIMIT = 1
    const val MAX_RATE_LIMIT = 100
    const val MAX_IDLE_TIMEOUT_HOURS = 168 // 1 week
    const val MIN_TOKEN_ROTATION_DAYS = 1
    const val MAX_TOKEN_ROTATION_DAYS = 30
  }

  /** Whether idle timeout is enabled (0 = disabled) */
  val isIdleTimeoutEnabled: Boolean get() = idleTimeoutHours > 0

  /** Whether to trust all WiFi networks (empty whitelist) */
  val trustAllWifi: Boolean get() = trustedSsids.isEmpty()
}

interface GatewayConfig {

  val config: Flow<GatewayConfigData>

  /** Update server port */
  suspend fun setServerPort(port: Int)

  /** Update idle timeout hours (0 to disable) */
  suspend fun setIdleTimeoutHours(hours: Int)

  /** Update rate limit QPS */
  suspend fun setRateLimitQps(qps: Int)

  /** Update auto-rotate token on IP change setting */
  suspend fun setAutoRotateTokenOnIpChange(enabled: Boolean)

  suspend fun setEnableMdns(enabled: Boolean)

  suspend fun setEnableTls(enabled: Boolean)

  suspend fun setTrustedSsids(ssids: Set<String>)

  suspend fun addTrustedSsid(ssid: String)

  suspend fun removeTrustedSsid(ssid: String)

  suspend fun setServerEnabled(enabled: Boolean)

  /** Update token rotation interval in days */
  suspend fun setTokenRotationIntervalDays(days: Int)

  suspend fun resetToDefaults()
}

/**
 * DataStore-backed implementation of GatewayConfig.
 */
@Single(binds = [GatewayConfig::class])
class GatewayConfigImpl(
  @Named("gateway_config") private val dataStore: DataStore<Preferences>,
) : GatewayConfig {

  private object Keys {
    val SERVER_PORT = intPreferencesKey("gateway_server_port")
    val IDLE_TIMEOUT_HOURS = intPreferencesKey("gateway_idle_timeout_hours")
    val RATE_LIMIT_QPS = intPreferencesKey("gateway_rate_limit_qps")
    val AUTO_ROTATE_TOKEN = booleanPreferencesKey("gateway_auto_rotate_token")
    val TOKEN_ROTATION_INTERVAL_DAYS = intPreferencesKey("gateway_token_rotation_interval_days")
    val ENABLE_MDNS = booleanPreferencesKey("gateway_enable_mdns")
    val ENABLE_TLS = booleanPreferencesKey("gateway_enable_tls")
    val TRUSTED_SSIDS = stringSetPreferencesKey("gateway_trusted_ssids")
    val SERVER_ENABLED = booleanPreferencesKey("gateway_server_enabled")
  }

  override val config: Flow<GatewayConfigData> = dataStore.data.map { prefs ->
    GatewayConfigData(
      serverPort = prefs[Keys.SERVER_PORT] ?: GatewayConfigData.DEFAULT_SERVER_PORT,
      idleTimeoutHours = prefs[Keys.IDLE_TIMEOUT_HOURS]
        ?: GatewayConfigData.DEFAULT_IDLE_TIMEOUT_HOURS,
      rateLimitQps = prefs[Keys.RATE_LIMIT_QPS] ?: GatewayConfigData.DEFAULT_RATE_LIMIT_QPS,
      autoRotateTokenOnIpChange = prefs[Keys.AUTO_ROTATE_TOKEN]
        ?: GatewayConfigData.DEFAULT_AUTO_ROTATE_TOKEN,
      tokenRotationIntervalDays = prefs[Keys.TOKEN_ROTATION_INTERVAL_DAYS]
        ?: GatewayConfigData.DEFAULT_TOKEN_ROTATION_INTERVAL_DAYS,
      enableMdns = prefs[Keys.ENABLE_MDNS] ?: GatewayConfigData.DEFAULT_ENABLE_MDNS,
      enableTls = prefs[Keys.ENABLE_TLS] ?: GatewayConfigData.DEFAULT_ENABLE_TLS,
      trustedSsids = prefs[Keys.TRUSTED_SSIDS] ?: emptySet(),
      serverEnabled = prefs[Keys.SERVER_ENABLED] ?: GatewayConfigData.DEFAULT_SERVER_ENABLED,
    )
  }

  override suspend fun setServerPort(port: Int) {
    require(port in GatewayConfigData.MIN_PORT..GatewayConfigData.MAX_PORT) {
      "Port must be between ${GatewayConfigData.MIN_PORT} and ${GatewayConfigData.MAX_PORT}"
    }
    dataStore.edit { prefs ->
      prefs[Keys.SERVER_PORT] = port
    }
  }

  override suspend fun setIdleTimeoutHours(hours: Int) {
    require(hours in 0..GatewayConfigData.MAX_IDLE_TIMEOUT_HOURS) {
      "Idle timeout must be between 0 and ${GatewayConfigData.MAX_IDLE_TIMEOUT_HOURS}"
    }
    dataStore.edit { prefs ->
      prefs[Keys.IDLE_TIMEOUT_HOURS] = hours
    }
  }

  override suspend fun setRateLimitQps(qps: Int) {
    require(qps in GatewayConfigData.MIN_RATE_LIMIT..GatewayConfigData.MAX_RATE_LIMIT) {
      "Rate limit must be between ${GatewayConfigData.MIN_RATE_LIMIT} and ${GatewayConfigData.MAX_RATE_LIMIT}"
    }
    dataStore.edit { prefs ->
      prefs[Keys.RATE_LIMIT_QPS] = qps
    }
  }

  override suspend fun setAutoRotateTokenOnIpChange(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[Keys.AUTO_ROTATE_TOKEN] = enabled
    }
  }

  override suspend fun setEnableMdns(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[Keys.ENABLE_MDNS] = enabled
    }
  }

  override suspend fun setEnableTls(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[Keys.ENABLE_TLS] = enabled
    }
  }

  override suspend fun setTrustedSsids(ssids: Set<String>) {
    dataStore.edit { prefs ->
      prefs[Keys.TRUSTED_SSIDS] = ssids
    }
  }

  override suspend fun addTrustedSsid(ssid: String) {
    dataStore.edit { prefs ->
      val current = prefs[Keys.TRUSTED_SSIDS] ?: emptySet()
      prefs[Keys.TRUSTED_SSIDS] = current + ssid
    }
  }

  override suspend fun removeTrustedSsid(ssid: String) {
    dataStore.edit { prefs ->
      val current = prefs[Keys.TRUSTED_SSIDS] ?: emptySet()
      prefs[Keys.TRUSTED_SSIDS] = current - ssid
    }
  }

  override suspend fun setServerEnabled(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[Keys.SERVER_ENABLED] = enabled
    }
  }

  override suspend fun setTokenRotationIntervalDays(days: Int) {
    require(days in GatewayConfigData.MIN_TOKEN_ROTATION_DAYS..GatewayConfigData.MAX_TOKEN_ROTATION_DAYS) {
      "Token rotation interval must be between ${GatewayConfigData.MIN_TOKEN_ROTATION_DAYS} and ${GatewayConfigData.MAX_TOKEN_ROTATION_DAYS}"
    }
    dataStore.edit { prefs ->
      prefs[Keys.TOKEN_ROTATION_INTERVAL_DAYS] = days
    }
  }

  override suspend fun resetToDefaults() {
    dataStore.edit { prefs ->
      prefs.remove(Keys.SERVER_PORT)
      prefs.remove(Keys.IDLE_TIMEOUT_HOURS)
      prefs.remove(Keys.RATE_LIMIT_QPS)
      prefs.remove(Keys.AUTO_ROTATE_TOKEN)
      prefs.remove(Keys.TOKEN_ROTATION_INTERVAL_DAYS)
      prefs.remove(Keys.ENABLE_MDNS)
      prefs.remove(Keys.ENABLE_TLS)
      prefs.remove(Keys.TRUSTED_SSIDS)
      prefs.remove(Keys.SERVER_ENABLED)
    }
  }
}
