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
package me.fleey.futon.config

/**
 * Centralized configuration for daemon-related constants.
 */
object DaemonConfig {
  // Daemon identity
  const val SERVICE_NAME = "futon_daemon"
  const val PROCESS_NAME = "futon_daemon"

  // Paths
  const val BASE_DIR = "/data/adb/futon"
  const val BINARY_PATH = "$BASE_DIR/futon_daemon"
  const val LIB_DIR = "$BASE_DIR/lib"
  const val PID_FILE = "$BASE_DIR/futon_daemon.pid"
  const val LOG_FILE = "$BASE_DIR/daemon.log"
  const val MODELS_DIR = "$BASE_DIR/models"
  const val CONFIG_DIR = "$BASE_DIR/config"
  const val AUTH_PUBKEY_PATH = "$BASE_DIR/.auth_pubkey"
  const val PUBKEY_PIN_PATH = "$BASE_DIR/.pubkey_pin"
  const val KEYS_DIR = "$BASE_DIR/keys"  // User-Provisioned PKI keys directory

  // Asset paths
  fun getAssetBinaryPath(arch: String): String = "bin/$arch/futon_daemon"
  fun getAssetLibPath(arch: String): String = "lib/$arch"
  const val TEMP_BINARY_NAME = "futon_daemon_temp"

  // Version (format: major.minor.patch.revision encoded as 32-bit int)
  const val PROTOCOL_VERSION = (1 shl 24) or (0 shl 16) or (0 shl 8) or 0x4C

  // Timeouts
  object Timeouts {
    const val BINDER_WAIT_MS = 10_000L
    const val SHELL_COMMAND_MS = 5_000L
    const val INTEGRITY_CHECK_MS = 500L
    const val STARTUP_POLL_INTERVAL_MS = 200L
  }

  // Lifecycle
  object Lifecycle {
    const val DEFAULT_KEEP_ALIVE_MS = 30_000L
    const val MIN_KEEP_ALIVE_MS = 5_000L
    const val RECONNECT_DEBOUNCE_MS = 500L
  }

  // Reconnection
  object Reconnection {
    const val MAX_ATTEMPTS = 3
    const val INITIAL_BACKOFF_MS = 100L
    const val MAX_BACKOFF_MS = 400L
  }

  // Authentication
  object Auth {
    const val KEYSTORE_ALIAS = "futon_daemon_auth"
    const val SIGNATURE_ALGORITHM_ED25519 = "Ed25519"
    const val SIGNATURE_ALGORITHM_ECDSA = "SHA256withECDSA"
    const val ED25519_PUBLIC_KEY_SIZE = 32
    const val CHALLENGE_SIZE = 32
  }

  // Debug
  object Debug {
    const val DEFAULT_STREAM_PORT = 33212
  }

  // IO
  object IO {
    const val BUFFER_SIZE = 8192
  }

  // Security Audit Logging
  object SecurityAudit {
    const val LOG_FILE = "$BASE_DIR/security.log"
    const val MAX_LOG_SIZE_BYTES = 1024 * 1024L
    const val MAX_LOG_FILES = 3
    const val MAX_ENTRIES_PER_MINUTE = 100
    const val RATE_LIMIT_WINDOW_MS = 60_000L
    const val MAX_RECENT_LOGS_IN_MEMORY = 100
  }

  // Shell commands
  object Commands {
    val KILL = "pkill -9 $PROCESS_NAME"
    val KILL_SIGKILL = "pkill -SIGKILL $PROCESS_NAME"
    val GET_PID = "pidof $PROCESS_NAME"

    /**
     * Build daemon start command.
     * In debug builds, adds --skip-sig-check flag.
     * Always kills existing daemon first to ensure single instance.
     * Sets /dev/uinput permissions for touch injection support.
     * Sets LD_LIBRARY_PATH for LiteRT shared libraries.
     */
    fun startDaemon(authorizedPkg: String, isDebug: Boolean): String {
      val skipSigCheck = if (isDebug) " --skip-sig-check" else ""

      return "$KILL 2>/dev/null; sleep 0.1; chmod 666 /dev/uinput 2>/dev/null; " +
        "LD_LIBRARY_PATH=$LIB_DIR:\$LD_LIBRARY_PATH nohup $BINARY_PATH --authorized-pkg=$authorizedPkg$skipSigCheck > /dev/null 2>&1 &"
    }
  }
}
