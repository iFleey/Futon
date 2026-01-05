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
package me.fleey.futon.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.security.models.KeyProvisioningResult
import me.fleey.futon.data.security.models.ProvisionedKeyInfo
import me.fleey.futon.data.security.models.SecurityLevel
import org.koin.core.annotation.Single
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Implementation of KeyProvisioning using Android KeyStore
 *
 * Generates EC P-256 keys in hardware (TEE/StrongBox) with attestation,
 * then deploys public key to daemon via Root access.
 */
@Single(binds = [KeyProvisioning::class])
class KeyProvisioningImpl(
  private val context: Context,
) : KeyProvisioning {

  private val keyStore: KeyStore by lazy {
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
  }

  override suspend fun isKeyProvisioned(): Boolean = withContext(Dispatchers.IO) {
    try {
      keyStore.containsAlias(KEY_ALIAS)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to check key existence", e)
      false
    }
  }

  override suspend fun getProvisionedKeyInfo(): ProvisionedKeyInfo? = withContext(Dispatchers.IO) {
    try {
      if (!keyStore.containsAlias(KEY_ALIAS)) return@withContext null

      val privateKey = keyStore.getKey(KEY_ALIAS, null)
        ?: return@withContext null

      val factory = KeyFactory.getInstance(
        privateKey.algorithm,
        ANDROID_KEYSTORE,
      )
      val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)

      val securityLevel = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ->
          SecurityLevel.STRONGBOX

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
          SecurityLevel.TRUSTED_ENVIRONMENT

        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
          @Suppress("DEPRECATION") keyInfo.isInsideSecureHardware ->
          SecurityLevel.TRUSTED_ENVIRONMENT

        else -> SecurityLevel.SOFTWARE
      }

      val publicKey = getPublicKey()
      val keyId = publicKey?.let { generateKeyId(it) } ?: "unknown"

      val isHardwareBacked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        keyInfo.securityLevel >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
      } else {
        @Suppress("DEPRECATION") keyInfo.isInsideSecureHardware
      }

      ProvisionedKeyInfo(
        keyId = keyId,
        algorithm = ALGORITHM_EC_P256,
        createdAt = keyInfo.keyValidityStart?.time ?: System.currentTimeMillis(),
        isHardwareBacked = isHardwareBacked,
        securityLevel = securityLevel,
        attestationAvailable = hasAttestation(),
        deployedToDaemon = isKeyDeployedToDaemon(keyId),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get key info", e)
      null
    }
  }

  override suspend fun provisionKey(forceRegenerate: Boolean): KeyProvisioningResult =
    withContext(Dispatchers.IO) {
      try {
        if (!forceRegenerate && keyStore.containsAlias(KEY_ALIAS)) {
          val info = getProvisionedKeyInfo()
          if (info != null && info.deployedToDaemon) {
            return@withContext KeyProvisioningResult.AlreadyProvisioned(info.keyId)
          }
        }

        // Delete existing key if regenerating
        if (forceRegenerate && keyStore.containsAlias(KEY_ALIAS)) {
          keyStore.deleteEntry(KEY_ALIAS)
        }

        val keyGenResult = generateKeyPair()
        if (keyGenResult is KeyProvisioningResult.Error) {
          return@withContext keyGenResult
        }

        val publicKey = getPublicKey()
          ?: return@withContext KeyProvisioningResult.Error(
            KeyProvisioningResult.ErrorType.KEYSTORE_ERROR,
            "Failed to retrieve generated public key",
          )

        val keyId = generateKeyId(publicKey)
        val attestationChain = getAttestationChain()

        // Deploy to daemon via Root
        val deployResult = deployKeyToDaemon(keyId, publicKey, attestationChain)
        if (!deployResult) {
          return@withContext KeyProvisioningResult.Error(
            KeyProvisioningResult.ErrorType.WRITE_FAILED,
            "Failed to deploy key to daemon",
          )
        }

        signalDaemonReload()

        // Get security level
        val info = getProvisionedKeyInfo()
        KeyProvisioningResult.Success(
          keyId = keyId,
          isHardwareBacked = info?.isHardwareBacked ?: false,
          securityLevel = info?.securityLevel ?: SecurityLevel.SOFTWARE,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Key provisioning failed", e)
        KeyProvisioningResult.Error(
          KeyProvisioningResult.ErrorType.UNKNOWN,
          e.message ?: "Unknown error",
          e,
        )
      }
    }

  private fun generateKeyPair(): KeyProvisioningResult {
    return try {
      val keyPairGenerator = KeyPairGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_EC,
        ANDROID_KEYSTORE,
      )

      val builder = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
      )
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val challenge = context.packageName.toByteArray()
        builder.setAttestationChallenge(challenge)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
          builder.setIsStrongBoxBacked(true)
        } catch (e: Exception) {
          Log.w(TAG, "StrongBox not available, falling back to TEE")
        }
      }

      keyPairGenerator.initialize(builder.build())
      keyPairGenerator.generateKeyPair()

      Log.i(TAG, "Key pair generated successfully")
      KeyProvisioningResult.Success(
        keyId = "",  // Will be filled later
        isHardwareBacked = true,
        securityLevel = SecurityLevel.TRUSTED_ENVIRONMENT,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to generate key pair", e)
      KeyProvisioningResult.Error(
        KeyProvisioningResult.ErrorType.KEYSTORE_ERROR,
        "Failed to generate key pair: ${e.message}",
        e,
      )
    }
  }

  override suspend fun signData(data: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val privateKey = keyStore.getKey(KEY_ALIAS, null)
        ?: return@withContext null

      val signature = Signature.getInstance("SHA256withECDSA")
      signature.initSign(privateKey as java.security.PrivateKey)
      signature.update(data)
      signature.sign()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to sign data", e)
      null
    }
  }

  override suspend fun revokeKey(): Boolean = withContext(Dispatchers.IO) {
    try {
      val publicKey = getPublicKey()
      val keyId = publicKey?.let { generateKeyId(it) }

      if (keyStore.containsAlias(KEY_ALIAS)) {
        keyStore.deleteEntry(KEY_ALIAS)
      }

      if (keyId != null) {
        removeKeyFromDaemon(keyId)
        signalDaemonReload()
      }

      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to revoke key", e)
      false
    }
  }

  override suspend fun getPublicKey(): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val cert = keyStore.getCertificate(KEY_ALIAS)
        ?: return@withContext null
      cert.publicKey.encoded
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get public key", e)
      null
    }
  }

  override suspend fun getAttestationChain(): List<ByteArray>? = withContext(Dispatchers.IO) {
    try {
      val chain = keyStore.getCertificateChain(KEY_ALIAS)
        ?: return@withContext null
      chain.map { it.encoded }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get attestation chain", e)
      null
    }
  }

  private fun hasAttestation(): Boolean {
    return try {
      val chain = keyStore.getCertificateChain(KEY_ALIAS)
      chain != null && chain.size > 1
    } catch (e: Exception) {
      false
    }
  }

  private fun generateKeyId(publicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(publicKey)
    // Use first 16 bytes (32 hex chars) as key ID
    return hash.take(16).joinToString("") { "%02x".format(it) }
  }

  private fun deployKeyToDaemon(
    keyId: String,
    publicKey: ByteArray,
    attestationChain: List<ByteArray>?,
  ): Boolean {
    if (!Shell.getShell().isRoot) {
      Log.e(TAG, "Root access required to deploy key")
      return false
    }

    val keysDir = DaemonConfig.KEYS_DIR
    val keyFile = "$keysDir/$keyId.key"

    val mkdirResult = Shell.cmd("mkdir -p $keysDir").exec()
    if (!mkdirResult.isSuccess) {
      Log.e(TAG, "Failed to create keys directory")
      return false
    }

    val content = buildString {
      appendLine("# Futon Public Key Entry")
      appendLine("# Auto-generated by app - do not edit manually")
      appendLine()
      appendLine("key_id=$keyId")
      appendLine("algorithm=$ALGORITHM_EC_P256")
      appendLine("public_key=${publicKey.toHex()}")
      appendLine("created_at=${System.currentTimeMillis() / 1000}")
      appendLine("last_used_at=0")
      appendLine("attestation_verified=${attestationChain != null}")
      appendLine("attestation_package=${context.packageName}")

      if (attestationChain != null) {
        val chainBytes = attestationChain.fold(byteArrayOf()) { acc, cert -> acc + cert }
        appendLine("attestation_chain=${Base64.getEncoder().encodeToString(chainBytes)}")
      }

      appendLine("is_active=true")
    }

    val writeResult = Shell.cmd(
      "cat > $keyFile << 'EOF'\n$content\nEOF",
    ).exec()

    if (!writeResult.isSuccess) {
      Log.e(TAG, "Failed to write key file: ${writeResult.err}")
      return false
    }

    Shell.cmd("chmod 600 $keyFile").exec()
    Shell.cmd("chown root:root $keyFile").exec()

    Log.i(TAG, "Key deployed to daemon: $keyFile")
    return true
  }

  private fun removeKeyFromDaemon(keyId: String): Boolean {
    if (!Shell.getShell().isRoot) return false

    val keyFile = "${DaemonConfig.KEYS_DIR}/$keyId.key"
    val result = Shell.cmd("rm -f $keyFile").exec()
    return result.isSuccess
  }

  private fun isKeyDeployedToDaemon(keyId: String): Boolean {
    if (!Shell.getShell().isRoot) return false

    val keyFile = "${DaemonConfig.KEYS_DIR}/$keyId.key"
    val result = Shell.cmd("test -f $keyFile && echo 'exists'").exec()
    return result.out.any { it.contains("exists") }
  }

  private fun signalDaemonReload() {
    // Send SIGHUP to daemon to reload keys
    Shell.cmd("pkill -HUP -f futon_daemon").exec()
  }

  private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

  companion object {
    private const val TAG = "KeyProvisioning"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "futon_daemon_auth_key"
    private const val ALGORITHM_EC_P256 = "EC_P256"
  }
}
