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
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.koin.core.annotation.Single
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecureStorage {
  fun getString(key: String, defaultValue: String?): String?
  fun putString(key: String, value: String?)
  fun getBoolean(key: String, defaultValue: Boolean): Boolean
  fun putBoolean(key: String, value: Boolean)
  fun getInt(key: String, defaultValue: Int): Int
  fun putInt(key: String, value: Int)
  fun getLong(key: String, defaultValue: Long): Long
  fun putLong(key: String, value: Long)
  fun remove(key: String)
  fun contains(key: String): Boolean
  fun clear()
}

@Single(binds = [SecureStorage::class])
class SecureStorageImpl(
  context: Context,
  private val prefsName: String = "futon_secure_prefs",
) : SecureStorage {

  private val prefs: SharedPreferences =
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

  private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private val secretKey: SecretKey
    get() = getOrCreateKey()

  private fun getOrCreateKey(): SecretKey {
    val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existingKey != null) {
      return existingKey.secretKey
    }

    val keyGenerator = KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_AES,
      ANDROID_KEYSTORE,
    )

    val keySpec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .build()

    keyGenerator.init(keySpec)
    return keyGenerator.generateKey()
  }

  private fun encrypt(plainText: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)

    val iv = cipher.iv
    val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

    val combined = ByteArray(iv.size + encryptedBytes.size)
    System.arraycopy(iv, 0, combined, 0, iv.size)
    System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }

  private fun decrypt(encryptedText: String): String {
    val combined = Base64.decode(encryptedText, Base64.NO_WRAP)

    val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
    val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

    val cipher = Cipher.getInstance(TRANSFORMATION)
    val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

    return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
  }

  override fun getString(key: String, defaultValue: String?): String? {
    val encrypted = prefs.getString(key, null) ?: return defaultValue
    return try {
      decrypt(encrypted)
    } catch (e: Exception) {
      defaultValue
    }
  }

  override fun putString(key: String, value: String?) {
    if (value == null) {
      prefs.edit().remove(key).apply()
    } else {
      val encrypted = encrypt(value)
      prefs.edit().putString(key, encrypted).apply()
    }
  }

  override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
    val value = getString(key, null) ?: return defaultValue
    return value.toBooleanStrictOrNull() ?: defaultValue
  }

  override fun putBoolean(key: String, value: Boolean) {
    putString(key, value.toString())
  }

  override fun getInt(key: String, defaultValue: Int): Int {
    val value = getString(key, null) ?: return defaultValue
    return value.toIntOrNull() ?: defaultValue
  }

  override fun putInt(key: String, value: Int) {
    putString(key, value.toString())
  }

  override fun getLong(key: String, defaultValue: Long): Long {
    val value = getString(key, null) ?: return defaultValue
    return value.toLongOrNull() ?: defaultValue
  }

  override fun putLong(key: String, value: Long) {
    putString(key, value.toString())
  }

  override fun remove(key: String) {
    prefs.edit().remove(key).apply()
  }

  override fun contains(key: String): Boolean {
    return prefs.contains(key)
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "futon_secure_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
  }
}
