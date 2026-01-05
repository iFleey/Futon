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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.models.AuthState
import me.fleey.futon.data.daemon.models.DaemonError
import me.fleey.futon.data.daemon.models.ErrorCode
import org.koin.core.annotation.Single
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

private val Context.authDataStore by preferencesDataStore(name = "daemon_auth")

/**
 * Key algorithm used for authentication.
 */
enum class KeyAlgorithm {
  ED25519,
  ECDSA_P256
}

/**
 * Security level of the generated key.
 */
enum class KeySecurityLevel {
  /** Ed25519 key in TEE (highest cryptographic security, API 33+) */
  ED25519_TEE,

  /** ECDSA P-256 key in StrongBox (highest hardware isolation, API 28+) */
  STRONGBOX_ECDSA,

  /** ECDSA P-256 key in TEE (standard hardware-backed security) */
  TEE_ECDSA,

  /** Software-only key (fallback, lowest security) */
  SOFTWARE
}

/**
 * Result of key generation with metadata.
 */
data class KeyGenerationResult(
  val algorithm: KeyAlgorithm,
  val securityLevel: KeySecurityLevel,
  val hasAttestation: Boolean,
  val isInsideSecureHardware: Boolean,
)

interface DaemonAuthenticator {
  suspend fun ensureKeyPairExists(): Result<Unit>
  suspend fun getPublicKey(): Result<ByteArray>
  suspend fun getPublicKeyFingerprint(): Result<ByteArray>
  suspend fun signChallenge(challenge: ByteArray): Result<ByteArray>
  suspend fun getInstanceId(): String
  suspend fun regenerateInstanceId(): String
  suspend fun deleteKeyPair(): Result<Unit>

  suspend fun getAttestationChain(): Result<List<ByteArray>>
  suspend fun isAttestationAvailable(): Boolean
  suspend fun getKeySecurityLevel(): KeySecurityLevel?
  suspend fun getKeyAlgorithm(): KeyAlgorithm?
}

@Single(binds = [DaemonAuthenticator::class])
class DaemonAuthenticatorImpl(
  private val context: Context,
) : DaemonAuthenticator {

  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
  private val mutex = Mutex()

  private object PrefsKeys {
    val INSTANCE_ID = stringPreferencesKey("instance_id")
  }

  @Volatile
  private var currentAlgorithm: KeyAlgorithm? = null

  @Volatile
  private var currentSecurityLevel: KeySecurityLevel? = null

  override suspend fun ensureKeyPairExists(): Result<Unit> = withContext(Dispatchers.IO) {
    mutex.withLock {
      try {
        if (keyStore.containsAlias(DaemonConfig.Auth.KEYSTORE_ALIAS)) {
          try {
            val entry = keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null)
            if (entry is KeyStore.PrivateKeyEntry) {
              currentAlgorithm = detectKeyAlgorithm(entry.privateKey)
              currentSecurityLevel = detectSecurityLevel(entry.privateKey)
              return@withContext Result.success(Unit)
            }
            Log.w(TAG, "Invalid key entry type, regenerating")
            keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
          } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve existing key, regenerating: ${e.message}")
            keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
          }
        }

        val result = generateKeyPairWithFallback()
        currentAlgorithm = result.algorithm
        currentSecurityLevel = result.securityLevel
        Log.i(
          TAG,
          "Generated key: algorithm=${result.algorithm}, security=${result.securityLevel}, attestation=${result.hasAttestation}",
        )
        Result.success(Unit)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to ensure key pair exists", e)
        Result.failure(
          DaemonAuthException(
            DaemonError.authentication(
              ErrorCode.AUTH_KEY_NOT_FOUND,
              "Failed to generate authentication keys: ${e.message}",
            ),
            e,
          ),
        )
      }
    }
  }

  override suspend fun getPublicKey(): Result<ByteArray> = withContext(Dispatchers.IO) {
    mutex.withLock {
      try {
        val entry =
          keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: return@withContext Result.failure(
              DaemonAuthException(
                DaemonError.authentication(ErrorCode.AUTH_KEY_NOT_FOUND, "Public key not found"),
              ),
            )

        val publicKey = entry.certificate.publicKey
        val algorithm = detectKeyAlgorithm(entry.privateKey)
        if (currentAlgorithm != algorithm) {
          currentAlgorithm = algorithm
        }

        val encoded = publicKey.encoded
        val rawKey = when (algorithm) {
          KeyAlgorithm.ED25519 -> extractRawEd25519PublicKey(encoded)
          KeyAlgorithm.ECDSA_P256 -> encoded
        }
        Result.success(rawKey)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to get public key", e)
        Result.failure(
          DaemonAuthException(
            DaemonError.authentication(
              ErrorCode.AUTH_KEY_NOT_FOUND,
              "Failed to get public key: ${e.message}",
            ),
            e,
          ),
        )
      }
    }
  }

  override suspend fun getPublicKeyFingerprint(): Result<ByteArray> = withContext(Dispatchers.IO) {
    getPublicKey().map { publicKey ->
      java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
    }
  }

  override suspend fun signChallenge(challenge: ByteArray): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          if (challenge.size != DaemonConfig.Auth.CHALLENGE_SIZE) {
            return@withContext Result.failure(
              DaemonAuthException(
                DaemonError.authentication(
                  ErrorCode.AUTH_CHALLENGE_FAILED,
                  "Invalid challenge size: expected ${DaemonConfig.Auth.CHALLENGE_SIZE}, got ${challenge.size}",
                ),
              ),
            )
          }

          val entry =
            keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
              ?: return@withContext Result.failure(
                DaemonAuthException(
                  DaemonError.authentication(ErrorCode.AUTH_KEY_NOT_FOUND, "Private key not found"),
                ),
              )

          val privateKey = entry.privateKey
          val algorithm = detectKeyAlgorithm(privateKey)
          if (currentAlgorithm != algorithm) {
            currentAlgorithm = algorithm
          }

          val signatureAlgorithm = when (algorithm) {
            KeyAlgorithm.ED25519 -> DaemonConfig.Auth.SIGNATURE_ALGORITHM_ED25519
            KeyAlgorithm.ECDSA_P256 -> DaemonConfig.Auth.SIGNATURE_ALGORITHM_ECDSA
          }

          val signature = Signature.getInstance(signatureAlgorithm)
          signature.initSign(privateKey)
          signature.update(challenge)
          Result.success(signature.sign())
        } catch (e: Exception) {
          Log.e(TAG, "Failed to sign challenge: ${e.javaClass.simpleName}: ${e.message}")

          val errorMsg = e.message ?: ""
          val isKeyCorrupted = errorMsg.contains("Keystore operation failed") ||
            errorMsg.contains("StrongBox") ||
            errorMsg.contains("INCOMPATIBLE_DIGEST") ||
            e is android.security.keystore.KeyPermanentlyInvalidatedException

          if (isKeyCorrupted) {
            Log.w(TAG, "Key appears corrupted, deleting for regeneration")
            try {
              keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
              currentAlgorithm = null
              currentSecurityLevel = null
            } catch (deleteEx: Exception) {
              Log.e(TAG, "Failed to delete corrupted key: ${deleteEx.message}")
            }
          }

          Result.failure(
            DaemonAuthException(
              DaemonError.authentication(
                if (isKeyCorrupted) ErrorCode.AUTH_KEY_CORRUPTED else ErrorCode.AUTH_SIGNATURE_INVALID,
                "Failed to sign challenge: ${e.message}",
              ),
              e,
            ),
          )
        }
      }
    }

  override suspend fun getInstanceId(): String = withContext(Dispatchers.IO) {
    context.authDataStore.data
      .map { prefs -> prefs[PrefsKeys.INSTANCE_ID] }
      .first()
      ?: run {
        val newId = UUID.randomUUID().toString()
        context.authDataStore.edit { prefs ->
          prefs[PrefsKeys.INSTANCE_ID] = newId
        }
        newId
      }
  }

  override suspend fun regenerateInstanceId(): String = withContext(Dispatchers.IO) {
    val newId = UUID.randomUUID().toString()
    context.authDataStore.edit { prefs ->
      prefs[PrefsKeys.INSTANCE_ID] = newId
    }
    newId
  }

  override suspend fun deleteKeyPair(): Result<Unit> = withContext(Dispatchers.IO) {
    mutex.withLock {
      try {
        if (keyStore.containsAlias(DaemonConfig.Auth.KEYSTORE_ALIAS)) {
          keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
        }
        currentAlgorithm = null
        currentSecurityLevel = null
        Result.success(Unit)
      } catch (e: Exception) {
        Result.failure(
          DaemonAuthException(
            DaemonError.authentication(
              ErrorCode.AUTH_KEY_NOT_FOUND,
              "Failed to delete key pair: ${e.message}",
            ),
            e,
          ),
        )
      }
    }
  }

  override suspend fun getAttestationChain(): Result<List<ByteArray>> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          val chain = keyStore.getCertificateChain(DaemonConfig.Auth.KEYSTORE_ALIAS)
            ?: return@withContext Result.failure(
              DaemonAuthException(
                DaemonError.authentication(
                  ErrorCode.AUTH_KEY_NOT_FOUND,
                  "Certificate chain not found",
                ),
              ),
            )

          val derChain = chain.map { it.encoded }
          if (derChain.size <= 1) {
            return@withContext Result.failure(
              DaemonAuthException(
                DaemonError.authentication(
                  ErrorCode.AUTH_ATTESTATION_FAILED,
                  "Key attestation not available",
                ),
              ),
            )
          }

          Result.success(derChain)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to get attestation chain", e)
          Result.failure(
            DaemonAuthException(
              DaemonError.authentication(
                ErrorCode.AUTH_ATTESTATION_FAILED,
                "Failed to get attestation chain: ${e.message}",
              ),
              e,
            ),
          )
        }
      }
    }

  override suspend fun isAttestationAvailable(): Boolean = withContext(Dispatchers.IO) {
    try {
      val chain = keyStore.getCertificateChain(DaemonConfig.Auth.KEYSTORE_ALIAS)
      chain != null && chain.size > 1
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun getKeySecurityLevel(): KeySecurityLevel? = currentSecurityLevel

  override suspend fun getKeyAlgorithm(): KeyAlgorithm? = currentAlgorithm


  /**
   * Generate key pair with fallback strategy:
   * 1. Ed25519 (API 33+) - highest cryptographic security, but verify signing works
   * 2. ECDSA P-256 with StrongBox (API 28+) - highest hardware isolation
   * 3. ECDSA P-256 with TEE - standard hardware-backed security
   *
   * NOTE: Ed25519 in Android KeyStore has known issues on some devices where
   * key generation succeeds but signing fails with "Keystore operation failed".
   * See: https://issuetracker.google.com/issues/399856239
   * We verify signing works before accepting the key.
   */
  private fun generateKeyPairWithFallback(): KeyGenerationResult {
    // Try Ed25519 first (API 33+), but verify it actually works
    // Known issue: Ed25519 key generation may succeed but signing fails
    // (Google Issue Tracker #399856239)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        val result = generateEd25519KeyPair()
        if (verifySigningWorks()) {
          return result
        }
        Log.w(TAG, "Ed25519 key generated but signing failed, falling back to ECDSA")
        keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
      } catch (e: Exception) {
        Log.w(TAG, "Ed25519 generation failed, falling back to ECDSA: ${e.message}")
        try {
          keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
        } catch (_: Exception) {
        }
      }
    }

    // Try ECDSA with StrongBox
    if (hasStrongBox()) {
      try {
        val result = generateEcdsaKeyPair(useStrongBox = true, useAttestation = true)
        if (verifySigningWorks()) {
          return result
        }
        Log.w(TAG, "StrongBox key generated but signing failed, falling back")
        keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
      } catch (e: StrongBoxUnavailableException) {
        Log.w(TAG, "StrongBox unavailable, falling back to TEE")
      } catch (e: Exception) {
        Log.w(TAG, "StrongBox ECDSA generation failed: ${e.message}")
        // Try StrongBox without attestation
        try {
          val result = generateEcdsaKeyPair(useStrongBox = true, useAttestation = false)
          if (verifySigningWorks()) {
            return result
          }
          Log.w(TAG, "StrongBox without attestation signing failed")
          keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
        } catch (e2: Exception) {
          Log.w(TAG, "StrongBox without attestation also failed: ${e2.message}")
        }
      }
    }

    // Try ECDSA with TEE and attestation
    try {
      val result = generateEcdsaKeyPair(useStrongBox = false, useAttestation = true)
      if (verifySigningWorks()) {
        return result
      }
      Log.w(TAG, "TEE ECDSA with attestation signing failed")
      keyStore.deleteEntry(DaemonConfig.Auth.KEYSTORE_ALIAS)
    } catch (e: Exception) {
      Log.w(TAG, "TEE ECDSA with attestation failed: ${e.message}")
    }

    // Final fallback: ECDSA with TEE, no attestation
    val result = generateEcdsaKeyPair(useStrongBox = false, useAttestation = false)
    if (!verifySigningWorks()) {
      throw IllegalStateException("All key generation strategies failed - signing does not work")
    }
    return result
  }

  /**
   * Verify that signing actually works with the current key.
   * This catches cases where key generation succeeds but signing fails
   * (known issue with Ed25519 on some devices).
   */
  private fun verifySigningWorks(): Boolean {
    return try {
      val entry =
        keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
          ?: return false

      val privateKey = entry.privateKey
      val algorithm = detectKeyAlgorithm(privateKey)
      val signatureAlgorithm = when (algorithm) {
        KeyAlgorithm.ED25519 -> DaemonConfig.Auth.SIGNATURE_ALGORITHM_ED25519
        KeyAlgorithm.ECDSA_P256 -> DaemonConfig.Auth.SIGNATURE_ALGORITHM_ECDSA
      }

      val testData = ByteArray(32) { it.toByte() }
      val signature = Signature.getInstance(signatureAlgorithm)
      signature.initSign(privateKey)
      signature.update(testData)
      signature.sign()

      true
    } catch (e: Exception) {
      Log.w(TAG, "Signing verification failed: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  private fun generateEd25519KeyPair(): KeyGenerationResult {
    val keyPairGenerator = KeyPairGenerator.getInstance(
      ALGORITHM_ED25519,
      ANDROID_KEYSTORE,
    )

    val builder = KeyGenParameterSpec.Builder(
      DaemonConfig.Auth.KEYSTORE_ALIAS,
      KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    )
      .setDigests(KeyProperties.DIGEST_NONE)
      .setUserAuthenticationRequired(false)

    var hasAttestation = false
    try {
      val challenge = generateAttestationChallenge()
      builder.setAttestationChallenge(challenge)
      keyPairGenerator.initialize(builder.build())
      keyPairGenerator.generateKeyPair()
      hasAttestation = true
    } catch (e: Exception) {
      Log.w(TAG, "Ed25519 with attestation failed, trying without: ${e.message}")
      val builderNoAttest = KeyGenParameterSpec.Builder(
        DaemonConfig.Auth.KEYSTORE_ALIAS,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
      )
        .setDigests(KeyProperties.DIGEST_NONE)
        .setUserAuthenticationRequired(false)

      keyPairGenerator.initialize(builderNoAttest.build())
      keyPairGenerator.generateKeyPair()
    }

    val isSecureHardware = checkIsInsideSecureHardware()
    return KeyGenerationResult(
      algorithm = KeyAlgorithm.ED25519,
      securityLevel = KeySecurityLevel.ED25519_TEE,
      hasAttestation = hasAttestation,
      isInsideSecureHardware = isSecureHardware,
    )
  }

  private fun generateEcdsaKeyPair(
    useStrongBox: Boolean,
    useAttestation: Boolean,
  ): KeyGenerationResult {
    val keyPairGenerator = KeyPairGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_EC,
      ANDROID_KEYSTORE,
    )

    val builder = KeyGenParameterSpec.Builder(
      DaemonConfig.Auth.KEYSTORE_ALIAS,
      KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    )
      .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_P256))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(false)

    if (useStrongBox) {
      builder.setIsStrongBoxBacked(true)
    }

    var hasAttestation = false
    if (useAttestation) {
      try {
        val challenge = generateAttestationChallenge()
        builder.setAttestationChallenge(challenge)
        hasAttestation = true
      } catch (e: Exception) {
        Log.w(TAG, "Failed to set attestation challenge: ${e.message}")
      }
    }

    keyPairGenerator.initialize(builder.build())
    keyPairGenerator.generateKeyPair()

    val isSecureHardware = checkIsInsideSecureHardware()
    val securityLevel = when {
      useStrongBox && isSecureHardware -> KeySecurityLevel.STRONGBOX_ECDSA
      isSecureHardware -> KeySecurityLevel.TEE_ECDSA
      else -> KeySecurityLevel.SOFTWARE
    }

    return KeyGenerationResult(
      algorithm = KeyAlgorithm.ECDSA_P256,
      securityLevel = securityLevel,
      hasAttestation = hasAttestation,
      isInsideSecureHardware = isSecureHardware,
    )
  }

  private fun hasStrongBox(): Boolean {
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
  }

  private fun generateAttestationChallenge(): ByteArray {
    val challenge = ByteArray(32)
    java.security.SecureRandom().nextBytes(challenge)
    return challenge
  }

  @Suppress("DEPRECATION")
  private fun checkIsInsideSecureHardware(): Boolean {
    return try {
      val entry =
        keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
          ?: return false
      val factory = KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEYSTORE)
      val keyInfo = factory.getKeySpec(entry.privateKey, KeyInfo::class.java)

      // Use securityLevel on API 31+, fall back to deprecated isInsideSecureHardware
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        keyInfo.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
      } else {
        keyInfo.isInsideSecureHardware
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to check secure hardware: ${e.message}")
      false
    }
  }

  @Suppress("DEPRECATION")
  private fun detectSecurityLevel(privateKey: PrivateKey): KeySecurityLevel {
    return try {
      val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
      val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)

      val algorithm = detectKeyAlgorithm(privateKey)

      // Use securityLevel on API 31+, fall back to deprecated isInsideSecureHardware
      val isSecureHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        keyInfo.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
      } else {
        keyInfo.isInsideSecureHardware
      }

      val isStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
      } else {
        false
      }

      when {
        algorithm == KeyAlgorithm.ED25519 && isSecureHardware -> KeySecurityLevel.ED25519_TEE
        isStrongBox -> KeySecurityLevel.STRONGBOX_ECDSA
        isSecureHardware -> KeySecurityLevel.TEE_ECDSA
        else -> KeySecurityLevel.SOFTWARE
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to detect security level: ${e.message}")
      KeySecurityLevel.SOFTWARE
    }
  }

  private fun detectKeyAlgorithm(privateKey: PrivateKey): KeyAlgorithm {
    val algorithm = privateKey.algorithm

    // Check by algorithm name
    if (algorithm.equals("Ed25519", ignoreCase = true) ||
      algorithm.equals("EdDSA", ignoreCase = true)
    ) {
      return KeyAlgorithm.ED25519
    }

    // Check by public key type (API 33+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        val entry =
          keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
        val publicKey = entry?.certificate?.publicKey
        if (publicKey != null) {
          val className = publicKey.javaClass.name
          if (className.contains("EdEC") || className.contains("Ed25519")) {
            return KeyAlgorithm.ED25519
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check public key type: ${e.message}")
      }
    }

    // Check by public key size
    if (algorithm.equals("EC", ignoreCase = true)) {
      try {
        val entry =
          keyStore.getEntry(DaemonConfig.Auth.KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
        val publicKey = entry?.certificate?.publicKey
        if (publicKey != null) {
          val encoded = publicKey.encoded
          // Ed25519 X.509 encoded: ~44 bytes, ECDSA P-256: ~91 bytes
          if (encoded.size <= 50) {
            return KeyAlgorithm.ED25519
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check public key size: ${e.message}")
      }
    }

    return KeyAlgorithm.ECDSA_P256
  }

  private fun extractRawEd25519PublicKey(encoded: ByteArray): ByteArray {
    return if (encoded.size == DaemonConfig.Auth.ED25519_PUBLIC_KEY_SIZE) {
      encoded
    } else if (encoded.size > DaemonConfig.Auth.ED25519_PUBLIC_KEY_SIZE) {
      encoded.copyOfRange(encoded.size - DaemonConfig.Auth.ED25519_PUBLIC_KEY_SIZE, encoded.size)
    } else {
      throw IllegalStateException("Invalid public key encoding: size=${encoded.size}")
    }
  }

  companion object {
    private const val TAG = "DaemonAuthenticator"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALGORITHM_ED25519 = "Ed25519"
    private const val CURVE_P256 = "secp256r1"
  }
}

class DaemonAuthException(
  val error: DaemonError,
  cause: Throwable? = null,
) : Exception(error.message, cause) {
  val authState: AuthState
    get() = AuthState.Failed(error)
}
