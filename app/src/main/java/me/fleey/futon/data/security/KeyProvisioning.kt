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

import me.fleey.futon.data.security.models.KeyProvisioningResult
import me.fleey.futon.data.security.models.ProvisionedKeyInfo

/**
 * User-Provisioned PKI for Daemon Authentication
 */
interface KeyProvisioning {

  /**
   * Check if a key pair has been provisioned
   */
  suspend fun isKeyProvisioned(): Boolean

  /**
   * Get information about the currently provisioned key
   */
  suspend fun getProvisionedKeyInfo(): ProvisionedKeyInfo?

  /**
   * Generate a new key pair and provision it to the daemon
   *
   * @param forceRegenerate If true, regenerate even if key exists
   * @return Result of the provisioning operation
   */
  suspend fun provisionKey(forceRegenerate: Boolean = false): KeyProvisioningResult

  /**
   * Sign data with the provisioned private key
   *
   * Used for challenge-response authentication with the daemon.
   *
   * @param data Data to sign (typically a challenge from daemon)
   * @return Signature bytes, or null if signing failed
   */
  suspend fun signData(data: ByteArray): ByteArray?

  /**
   * Revoke the current key and remove from daemon
   */
  suspend fun revokeKey(): Boolean

  suspend fun getPublicKey(): ByteArray?

  /**
   * Get the attestation certificate chain for the current key
   *
   * This chain proves the key was generated in hardware on this device.
   */
  suspend fun getAttestationChain(): List<ByteArray>?
}
