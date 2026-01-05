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
package me.fleey.futon.data.security.models

import kotlinx.serialization.Serializable

sealed interface KeyProvisioningResult {

  data class Success(
    val keyId: String,
    val isHardwareBacked: Boolean,
    val securityLevel: SecurityLevel,
  ) : KeyProvisioningResult

  data class AlreadyProvisioned(
    val keyId: String,
  ) : KeyProvisioningResult

  data class Error(
    val type: ErrorType,
    val message: String,
    val cause: Throwable? = null,
  ) : KeyProvisioningResult

  enum class ErrorType {
    KEYSTORE_ERROR,
    ROOT_NOT_AVAILABLE,
    DAEMON_NOT_RUNNING,
    WRITE_FAILED,
    ATTESTATION_FAILED,
    UNKNOWN
  }
}

enum class SecurityLevel {
  SOFTWARE,           // Key stored in software (least secure)
  TRUSTED_ENVIRONMENT, // Key stored in TEE
  STRONGBOX           // Key stored in StrongBox (most secure)
}

@Serializable
data class ProvisionedKeyInfo(
  val keyId: String,
  val algorithm: String,
  val createdAt: Long,
  val isHardwareBacked: Boolean,
  val securityLevel: SecurityLevel,
  val attestationAvailable: Boolean,
  val deployedToDaemon: Boolean,
)

/**
 * Key file format written to daemon's keys directory
 */
@Serializable
data class DaemonKeyFile(
  val keyId: String,
  val algorithm: String,
  val publicKey: String,  // Hex encoded
  val createdAt: Long,
  val attestationChain: String? = null,  // Base64 encoded concatenated DER certs
)
