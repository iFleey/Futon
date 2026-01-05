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

sealed interface DaemonState {
  data object Stopped : DaemonState

  data object Starting : DaemonState

  data object Connecting : DaemonState

  data object Authenticating : DaemonState

  data object Reconciling : DaemonState

  data class Ready(
    val version: Int,
    val capabilities: Int,
  ) : DaemonState

  data class Error(
    val message: String,
    val code: ErrorCode,
    val recoverable: Boolean,
  ) : DaemonState
}
