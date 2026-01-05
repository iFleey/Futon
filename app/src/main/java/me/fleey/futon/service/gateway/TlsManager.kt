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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.fleey.futon.service.gateway.models.CertificateInfo
import me.fleey.futon.service.gateway.models.TlsState
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Manages TLS certificates for the LAN HTTP server.
 */
interface TlsManager {

  val tlsState: StateFlow<TlsState>

  /** Import a PKCS12 certificate from an input stream */
  suspend fun importCertificate(inputStream: InputStream, password: String): Result<CertificateInfo>

  /** Import a PKCS12 certificate from a file */
  suspend fun importCertificate(file: File, password: String): Result<CertificateInfo>

  suspend fun removeCertificate()

  /** Get the SSL context for Ktor (null if TLS disabled or no valid cert) */
  fun getSslContext(): SSLContext?

  /** Get the key store for Ktor (null if TLS disabled or no valid cert) */
  fun getKeyStore(): KeyStore?

  fun getKeyStorePassword(): CharArray

  suspend fun refresh()
}

@Single(binds = [TlsManager::class])
class TlsManagerImpl(
  private val context: Context,
  private val gatewayConfig: GatewayConfig,
) : TlsManager {

  companion object {
    private const val TAG = "TlsManager"
    private const val CERT_FILENAME = "server.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _tlsState = MutableStateFlow<TlsState>(TlsState.Disabled)
  override val tlsState: StateFlow<TlsState> = _tlsState.asStateFlow()

  private var keyStore: KeyStore? = null
  private var keyStorePassword: CharArray = charArrayOf()
  private var sslContext: SSLContext? = null

  private val certFile: File
    get() = File(context.filesDir, CERT_FILENAME)

  init {
    scope.launch {
      gatewayConfig.config.collect { config ->
        if (config.enableTls) {
          loadCertificate()
        } else {
          _tlsState.value = TlsState.Disabled
        }
      }
    }
  }

  override suspend fun importCertificate(
    inputStream: InputStream,
    password: String,
  ): Result<CertificateInfo> = withContext(Dispatchers.IO) {
    try {

      val ks = KeyStore.getInstance(KEYSTORE_TYPE)
      ks.load(inputStream, password.toCharArray())

      val alias = ks.aliases().nextElement()
      val cert = ks.getCertificate(alias) as? X509Certificate
        ?: return@withContext Result.failure(IllegalArgumentException("No X509 certificate found"))

      val certInfo = extractCertificateInfo(cert)
      if (certInfo.isExpired) {
        return@withContext Result.failure(IllegalArgumentException("Certificate is expired"))
      }

      // Save to app private directory
      inputStream.reset()
      certFile.outputStream().use { out ->
        inputStream.copyTo(out)
      }

      // Store password securely (in memory for now)
      keyStorePassword = password.toCharArray()
      keyStore = ks

      createSslContext(ks, password.toCharArray())

      _tlsState.value = TlsState.Enabled(certInfo)
      Log.i(TAG, "Certificate imported successfully: ${certInfo.commonName}")

      Result.success(certInfo)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to import certificate", e)
      _tlsState.value = TlsState.CertInvalid(e.message ?: "Unknown error")
      Result.failure(e)
    }
  }

  override suspend fun importCertificate(
    file: File,
    password: String,
  ): Result<CertificateInfo> = withContext(Dispatchers.IO) {
    FileInputStream(file).use { inputStream ->
      importCertificate(inputStream, password)
    }
  }

  override suspend fun removeCertificate(): Unit = withContext(Dispatchers.IO) {
    try {
      if (certFile.exists()) {
        certFile.delete()
      }
      keyStore = null
      keyStorePassword = charArrayOf()
      sslContext = null
      _tlsState.value = TlsState.Disabled
      Log.i(TAG, "Certificate removed")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to remove certificate", e)
    }
  }

  override fun getSslContext(): SSLContext? {
    return if (_tlsState.value is TlsState.Enabled) sslContext else null
  }

  override fun getKeyStore(): KeyStore? {
    return if (_tlsState.value is TlsState.Enabled) keyStore else null
  }

  override fun getKeyStorePassword(): CharArray {
    return keyStorePassword.copyOf()
  }

  override suspend fun refresh() = withContext(Dispatchers.IO) {
    val config = gatewayConfig.config.first()
    if (config.enableTls) {
      loadCertificate()
    } else {
      _tlsState.value = TlsState.Disabled
    }
  }

  private suspend fun loadCertificate() = withContext(Dispatchers.IO) {
    if (!certFile.exists()) {
      _tlsState.value = TlsState.Disabled
      return@withContext
    }

    try {
      val ks = KeyStore.getInstance(KEYSTORE_TYPE)
      FileInputStream(certFile).use { fis ->
        ks.load(fis, keyStorePassword)
      }

      val alias = ks.aliases().nextElement()
      val cert = ks.getCertificate(alias) as? X509Certificate

      if (cert == null) {
        _tlsState.value = TlsState.CertInvalid("No X509 certificate found")
        return@withContext
      }

      val certInfo = extractCertificateInfo(cert)

      if (certInfo.isExpired) {
        _tlsState.value = TlsState.CertExpired(certInfo)
        return@withContext
      }

      keyStore = ks
      createSslContext(ks, keyStorePassword)
      _tlsState.value = TlsState.Enabled(certInfo)

    } catch (e: Exception) {
      Log.e(TAG, "Failed to load certificate", e)
      _tlsState.value = TlsState.CertInvalid(e.message ?: "Unknown error")
    }
  }

  private fun createSslContext(keyStore: KeyStore, password: CharArray) {
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, password)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, null, null)
    sslContext = ctx
  }

  private fun extractCertificateInfo(cert: X509Certificate): CertificateInfo {
    val cn = cert.subjectX500Principal.name
      .split(",")
      .find { it.trim().startsWith("CN=") }
      ?.substringAfter("CN=")
      ?: "Unknown"

    val issuer = cert.issuerX500Principal.name
      .split(",")
      .find { it.trim().startsWith("CN=") }
      ?.substringAfter("CN=")
      ?: cert.issuerX500Principal.name

    val fingerprint = MessageDigest.getInstance("SHA-256")
      .digest(cert.encoded)
      .joinToString(":") { "%02X".format(it) }

    return CertificateInfo(
      commonName = cn,
      issuer = issuer,
      expiresAt = cert.notAfter.toInstant(),
      serialNumber = cert.serialNumber.toString(16),
      fingerprint = fingerprint,
    )
  }
}
