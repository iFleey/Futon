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

import android.content.Context
import android.util.Base64
import android.util.Log
import com.harrytmthy.safebox.SafeBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import java.security.SecureRandom

/**
 * Manages authentication tokens for external triggers (Tasker and LAN HTTP).
 * Tokens are stored securely using SafeBox (Tink + Keystore).
 */
interface TokenManager {

  val lanToken: StateFlow<String>

  val taskerToken: StateFlow<String>

  fun validateLanToken(token: String): Boolean

  fun validateTaskerToken(token: String): Boolean

  /** Regenerate the LAN token */
  suspend fun regenerateLanToken(): String

  suspend fun regenerateTaskerToken(): String

  /** Handle IP change (optionally rotate tokens) */
  suspend fun onIpChanged()

  suspend fun onServerStart()
}

@Single(binds = [TokenManager::class])
class TokenManagerImpl(
  context: Context,
  private val gatewayConfig: GatewayConfig,
) : TokenManager {
  companion object {
    private const val TAG = "TokenManager"
    private const val PREFS_NAME = "futon_gateway_tokens"
    private const val KEY_LAN_TOKEN = "lan_token"
    private const val KEY_TASKER_TOKEN = "tasker_token"
    private const val TOKEN_BYTES = 32
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val secureRandom = SecureRandom()

  private val encryptedPrefs = try {
    SafeBox.create(context, PREFS_NAME)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to create SafeBox", e)
    null
  }

  private val _lanToken = MutableStateFlow("")
  override val lanToken: StateFlow<String> = _lanToken.asStateFlow()

  private val _taskerToken = MutableStateFlow("")
  override val taskerToken: StateFlow<String> = _taskerToken.asStateFlow()

  init {
    scope.launch {
      loadOrGenerateTokens()
    }
  }

  override fun validateLanToken(token: String): Boolean {
    val currentToken = _lanToken.value
    if (currentToken.isEmpty()) return false
    return token == currentToken || token == "Bearer $currentToken"
  }

  override fun validateTaskerToken(token: String): Boolean {
    val currentToken = _taskerToken.value
    if (currentToken.isEmpty()) return false
    return token == currentToken
  }

  override suspend fun regenerateLanToken(): String = mutex.withLock {
    val newToken = generateToken()
    saveToken(KEY_LAN_TOKEN, newToken)
    _lanToken.value = newToken
    Log.i(TAG, "LAN token regenerated")
    newToken
  }

  override suspend fun regenerateTaskerToken(): String = mutex.withLock {
    val newToken = generateToken()
    saveToken(KEY_TASKER_TOKEN, newToken)
    _taskerToken.value = newToken
    Log.i(TAG, "Tasker token regenerated")
    newToken
  }

  override suspend fun onIpChanged() {
    val config = gatewayConfig.config.first()
    if (config.autoRotateTokenOnIpChange) {
      Log.i(TAG, "IP changed, rotating tokens")
      regenerateLanToken()
    }
  }

  override suspend fun onServerStart() {
    // Currently no auto-rotation on server start
    // Could be added as a config option if needed
  }

  private suspend fun loadOrGenerateTokens() = mutex.withLock {
    // Load or generate LAN token
    var lanToken = loadToken(KEY_LAN_TOKEN)
    if (lanToken == null) {
      lanToken = generateToken()
      saveToken(KEY_LAN_TOKEN, lanToken)
      Log.i(TAG, "Generated new LAN token")
    }
    _lanToken.value = lanToken

    // Load or generate Tasker token
    var taskerToken = loadToken(KEY_TASKER_TOKEN)
    if (taskerToken == null) {
      taskerToken = generateToken()
      saveToken(KEY_TASKER_TOKEN, taskerToken)
      Log.i(TAG, "Generated new Tasker token")
    }
    _taskerToken.value = taskerToken
  }

  private fun generateToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  private fun loadToken(key: String): String? {
    return encryptedPrefs?.getString(key, null)
  }

  private fun saveToken(key: String, token: String) {
    encryptedPrefs?.edit()?.putString(key, token)?.apply()
  }
}
