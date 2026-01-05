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
package me.fleey.futon.service.gateway.models

import java.time.Instant

/**
 * Represents the current TLS configuration state.
 */
sealed interface TlsState {

  data class Enabled(val certInfo: CertificateInfo) : TlsState

  /**
   * TLS is disabled (using plain HTTP).
   */
  data object Disabled : TlsState

  /**
   * Certificate has expired.
   * @param certInfo Information about the expired certificate
   */
  data class CertExpired(val certInfo: CertificateInfo) : TlsState

  data class CertInvalid(val reason: String) : TlsState
}

/**
 * Information about a loaded certificate.
 */
data class CertificateInfo(
  /** Subject common name (CN) */
  val commonName: String,

  /** Certificate issuer */
  val issuer: String,

  /** Certificate expiration date */
  val expiresAt: Instant,

  /** Certificate serial number */
  val serialNumber: String,

  /** SHA-256 fingerprint of the certificate */
  val fingerprint: String,
) {
  val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)

  /** Days until expiration (negative if expired) */
  val daysUntilExpiration: Long
    get() = java.time.Duration.between(Instant.now(), expiresAt).toDays()
}

val TlsState.isUsable: Boolean
  get() = this is TlsState.Enabled

val TlsState.certificateInfo: CertificateInfo?
  get() = when (this) {
    is TlsState.Enabled -> certInfo
    is TlsState.CertExpired -> certInfo
    else -> null
  }
