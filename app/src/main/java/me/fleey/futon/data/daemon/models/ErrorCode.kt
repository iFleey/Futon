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
package me.fleey.futon.data.daemon.models

enum class ErrorCode(val code: Int, val recoverable: Boolean) {
  // 1xx - Connection errors
  CONNECTION_FAILED(100, true),
  SERVICE_NOT_FOUND(101, true),
  BINDER_DIED(102, true),
  CONNECTION_TIMEOUT(103, true),
  RECONNECTION_EXHAUSTED(104, false),
  BINDER_BUFFER_EXHAUSTED(105, true),

  // 2xx - Authentication errors
  AUTH_FAILED(200, true),
  AUTH_CHALLENGE_FAILED(201, true),
  AUTH_SIGNATURE_INVALID(202, false),
  AUTH_KEY_NOT_FOUND(203, false),
  AUTH_SESSION_EXPIRED(204, true),
  AUTH_SESSION_CONFLICT(205, true),
  AUTH_ATTESTATION_FAILED(206, false),
  AUTH_ATTESTATION_MISMATCH(207, false),
  AUTH_KEY_CORRUPTED(208, true),

  // 3xx - Security errors
  SECURITY_UID_MISMATCH(300, false),
  SECURITY_UNAUTHORIZED(301, false),
  SECURITY_KEY_TAMPERED(302, false),
  SECURITY_PUBKEY_DEPLOY_FAILED(303, false),

  // 35x - Crypto channel errors
  CRYPTO_HANDSHAKE_FAILED(350, true),
  CRYPTO_INIT_FAILED(351, false),
  CRYPTO_ENCRYPT_FAILED(352, true),
  CRYPTO_DECRYPT_FAILED(353, true),
  CRYPTO_KEY_ROTATION_FAILED(354, true),
  CRYPTO_ERROR(355, true),
  CRYPTO_NOT_INITIALIZED(356, true),

  // 4xx - Deployment errors
  DEPLOY_BINARY_MISSING(400, false),
  DEPLOY_BINARY_CORRUPTED(401, false),
  DEPLOY_PERMISSION_DENIED(402, false),
  DEPLOY_SELINUX_BLOCKED(403, false),
  DEPLOY_ROOT_UNAVAILABLE(404, false),
  DEPLOY_MODEL_MISSING(405, true),
  DEPLOY_MODEL_CORRUPTED(406, true),

  // 5xx - Runtime errors
  RUNTIME_DAEMON_CRASHED(500, true),
  RUNTIME_OUT_OF_MEMORY(501, true),
  RUNTIME_BUFFER_EXHAUSTED(502, true),
  RUNTIME_CAPTURE_FAILED(503, true),
  RUNTIME_INFERENCE_FAILED(504, true),
  RUNTIME_INPUT_INJECTION_FAILED(505, true),
  PERCEPTION_FAILED(506, true),

  // 6xx - Configuration errors
  CONFIG_INVALID_FPS(600, false),
  CONFIG_INVALID_CONFIDENCE(601, false),
  CONFIG_INVALID_MODEL_PATH(602, false),
  CONFIG_SYNC_FAILED(603, true),

  // 7xx - Automation errors
  AUTOMATION_LOOP_DETECTED(700, true),
  AUTOMATION_MAX_STEPS_EXCEEDED(701, false),
  AUTOMATION_TASK_PARSE_FAILED(702, false),
  AUTOMATION_HOT_PATH_INVALID(703, false),
  AUTOMATION_AI_FALLBACK_FAILED(704, true),

  // Unknown
  UNKNOWN(999, false);

  fun isBinderTransientError(): Boolean = this == BINDER_DIED || this == BINDER_BUFFER_EXHAUSTED

  companion object {
    fun fromCode(code: Int): ErrorCode =
      entries.find { it.code == code } ?: UNKNOWN

    fun fromServiceSpecific(code: Int): ErrorCode = when (code) {
      // Authentication errors (negative codes from daemon)
      -1 -> RUNTIME_CAPTURE_FAILED        // pipeline/engine not available
      -2 -> RUNTIME_CAPTURE_FAILED        // not initialized
      -3 -> RUNTIME_CAPTURE_FAILED        // frame acquisition failed
      -4 -> RUNTIME_INFERENCE_FAILED      // inference failed
      -100 -> SECURITY_UNAUTHORIZED       // not authenticated

      // Positive codes map directly if in range
      in 100..199 -> fromCode(code)
      in 200..299 -> fromCode(code)
      in 300..399 -> fromCode(code)
      in 400..499 -> fromCode(code)
      in 500..599 -> fromCode(code)
      in 600..699 -> fromCode(code)
      in 700..799 -> fromCode(code)

      else -> UNKNOWN
    }

    fun isConnectionError(code: Int): Boolean = code in 100..199
    fun isAuthError(code: Int): Boolean = code in 200..299
    fun isSecurityError(code: Int): Boolean = code in 300..349
    fun isCryptoError(code: Int): Boolean = code in 350..399
    fun isDeploymentError(code: Int): Boolean = code in 400..499
    fun isRuntimeError(code: Int): Boolean = code in 500..599
    fun isConfigError(code: Int): Boolean = code in 600..699
    fun isAutomationError(code: Int): Boolean = code in 700..799
  }
}
