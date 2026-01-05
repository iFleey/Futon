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

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Disables Android's cgroup freezer for the current process to prevent
 * Binder transaction failures when the system attempts to freeze the app.
 *
 * This is particularly important for apps that communicate with privileged
 * daemons via Binder IPC, as frozen processes cannot complete Binder transactions.
 */
object ProcessFreezerDisabler {

  private const val TAG = "ProcessFreezerDisabler"

  /**
   * Attempts to disable the cgroup freezer for the current process.
   * This requires the app to have root access or be running in a privileged context.
   *
   * @return true if at least one method succeeded
   */
  fun disable(): Boolean {
    val pid = Process.myPid()
    val uid = Process.myUid()
    var anySuccess = false

    anySuccess = tryDisableCgroupFreeze(uid, pid) || anySuccess

    anySuccess = tryMoveToRootCgroup(pid) || anySuccess

    anySuccess = trySetOomScoreAdj(pid) || anySuccess

    if (anySuccess) {
      Log.i(TAG, "Process freezer disabled for pid=$pid uid=$uid")
    } else {
      Log.w(TAG, "Failed to disable process freezer (may require root)")
    }

    return anySuccess
  }

  private fun tryDisableCgroupFreeze(uid: Int, pid: Int): Boolean {
    // Try multiple possible cgroup paths
    val paths = listOf(
      "/sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.freeze",
      "/dev/cgroup_info/cgroup.freeze",
      "/sys/fs/cgroup/freezer/cgroup.freeze",
    )

    for (path in paths) {
      try {
        val file = File(path)
        if (file.exists() && file.canWrite()) {
          file.writeText("0")
          Log.d(TAG, "Disabled freezer via $path")
          return true
        }
      } catch (e: Exception) {
        Log.v(TAG, "Cannot write to $path: ${e.message}")
      }
    }

    // Try via su command as fallback
    return tryShellCommand("echo 0 > /sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.freeze")
  }

  private fun tryMoveToRootCgroup(pid: Int): Boolean {
    val paths = listOf(
      "/dev/cgroup_info/cgroup.procs",
      "/sys/fs/cgroup/cgroup.procs",
    )

    for (path in paths) {
      try {
        val file = File(path)
        if (file.exists() && file.canWrite()) {
          file.writeText(pid.toString())
          Log.d(TAG, "Moved process to root cgroup via $path")
          return true
        }
      } catch (e: Exception) {
        Log.v(TAG, "Cannot write to $path: ${e.message}")
      }
    }

    return tryShellCommand("echo $pid > /sys/fs/cgroup/cgroup.procs")
  }

  private fun trySetOomScoreAdj(pid: Int): Boolean {
    val path = "/proc/$pid/oom_score_adj"
    try {
      val file = File(path)
      if (file.exists() && file.canWrite()) {
        // -1000 is the minimum (never kill), but we use -900 to be less aggressive
        file.writeText("-900")
        Log.d(TAG, "Set oom_score_adj to -900")
        return true
      }
    } catch (e: Exception) {
      Log.v(TAG, "Cannot write to $path: ${e.message}")
    }

    return tryShellCommand("echo -900 > $path")
  }

  private fun tryShellCommand(command: String): Boolean {
    return try {
      val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
      val exitCode = process.waitFor()
      if (exitCode == 0) {
        Log.d(TAG, "Shell command succeeded: $command")
        true
      } else {
        Log.v(TAG, "Shell command failed with code $exitCode: $command")
        false
      }
    } catch (e: Exception) {
      Log.v(TAG, "Shell command exception: ${e.message}")
      false
    }
  }
}
