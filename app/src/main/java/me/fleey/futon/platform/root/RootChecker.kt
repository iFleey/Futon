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
package me.fleey.futon.platform.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.config.DaemonConfig
import me.fleey.futon.data.daemon.deployment.RootState
import org.koin.core.annotation.Single

interface RootChecker {
  val rootState: StateFlow<RootState>

  suspend fun checkRoot(forceRecheck: Boolean = false): RootState
  suspend fun getSELinuxStatus(): SELinuxStatus
  fun invalidateCache()
}

data class SELinuxStatus(
  val enabled: Boolean,
  val enforcing: Boolean,
  val context: String?,
)

@Single(binds = [RootChecker::class])
class RootCheckerImpl(
  private val rootShell: RootShell,
) : RootChecker {

  private val _rootState = MutableStateFlow<RootState>(RootState.NotChecked)
  override val rootState: StateFlow<RootState> = _rootState.asStateFlow()

  private val checkMutex = Mutex()
  private var cachedResult: RootState? = null
  private var lastCheckTime: Long = 0

  override suspend fun checkRoot(forceRecheck: Boolean): RootState =
    withContext(Dispatchers.IO) {
      checkMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRecheck && cachedResult != null && (now - lastCheckTime) < CACHE_DURATION_MS) {
          return@withContext cachedResult!!
        }

        val state = performRootCheck()
        cachedResult = state
        lastCheckTime = now
        _rootState.value = state
        state
      }
    }

  override suspend fun getSELinuxStatus(): SELinuxStatus = withContext(Dispatchers.IO) {
    try {
      val getenforceResult = rootShell.execute("getenforce")
      val enforcing = if (getenforceResult.isSuccess()) {
        val output = (getenforceResult as ShellResult.Success).output.trim().lowercase()
        output == "enforcing"
      } else {
        true
      }

      val contextResult =
        rootShell.execute("cat /proc/self/attr/current 2>/dev/null || echo unknown")
      val context = if (contextResult.isSuccess()) {
        (contextResult as ShellResult.Success).output.trim().takeIf { it != "unknown" }
      } else {
        null
      }

      val enabledResult = rootShell.execute("cat /sys/fs/selinux/enforce 2>/dev/null || echo 1")
      val enabled = if (enabledResult.isSuccess()) {
        (enabledResult as ShellResult.Success).output.trim() != "0"
      } else {
        true
      }

      SELinuxStatus(
        enabled = enabled,
        enforcing = enforcing,
        context = context,
      )
    } catch (e: Exception) {
      SELinuxStatus(enabled = true, enforcing = true, context = null)
    }
  }

  override fun invalidateCache() {
    cachedResult = null
    lastCheckTime = 0
    _rootState.value = RootState.NotChecked
  }

  private suspend fun performRootCheck(): RootState {
    if (!rootShell.isRootAvailable()) {
      return RootState.Unavailable("Root access not available on this device")
    }

    val idResult = rootShell.execute("su -c id")
    when (idResult) {
      is ShellResult.Success -> {
        val output = idResult.output
        if (!output.contains("uid=0")) {
          return RootState.Unavailable("Root shell does not have UID 0")
        }
      }

      is ShellResult.RootDenied -> {
        return RootState.Unavailable(idResult.reason)
      }

      is ShellResult.Timeout -> {
        return RootState.Unavailable("Root check timed out")
      }

      is ShellResult.Error -> {
        return RootState.Unavailable("Root check failed: ${idResult.message}")
      }
    }

    val selinuxStatus = getSELinuxStatus()
    if (selinuxStatus.enabled && selinuxStatus.enforcing) {
      // Ensure base directory exists before testing
      rootShell.execute("su -c 'mkdir -p ${DaemonConfig.BASE_DIR}'")

      val testResult = rootShell.execute(
        "su -c 'touch ${DaemonConfig.BASE_DIR}/.selinux_test && rm ${DaemonConfig.BASE_DIR}/.selinux_test'",
      )
      if (!testResult.isSuccess()) {
        val dmesgResult = rootShell.execute("dmesg | grep -i 'avc.*denied' | tail -5")
        val denials = if (dmesgResult.isSuccess()) {
          (dmesgResult as ShellResult.Success).output
        } else {
          "Unable to read SELinux denials"
        }
        return RootState.SELinuxBlocked(
          "SELinux is blocking daemon operations. Recent denials:\n$denials",
        )
      }
    }

    return RootState.Available
  }

  companion object {
    private const val CACHE_DURATION_MS = 60_000L
  }
}
