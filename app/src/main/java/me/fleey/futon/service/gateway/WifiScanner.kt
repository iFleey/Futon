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
package me.fleey.futon.service.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.annotation.Single

interface WifiScanner {
  fun scanWifiNetworks(): Flow<List<String>>
  fun getLastScanResults(): List<String>
}

@Single(binds = [WifiScanner::class])
class WifiScannerImpl(
  private val context: Context,
) : WifiScanner {

  companion object {
    private const val TAG = "WifiScanner"
  }

  private val wifiManager: WifiManager =
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  override fun scanWifiNetworks(): Flow<List<String>> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        Log.d(TAG, "Scan results available, success: $success")
        val ssids = extractSsids()
        trySend(ssids)
      }
    }

    val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
    context.registerReceiver(receiver, intentFilter)

    // Send current cached results immediately
    // NOTE: startScan() is deprecated since Android 9 and will be removed.
    // Apps should rely on system-initiated scans and cached results.
    val currentResults = extractSsids()
    trySend(currentResults)

    awaitClose {
      Log.d(TAG, "Unregistering WiFi scan receiver")
      context.unregisterReceiver(receiver)
    }
  }

  override fun getLastScanResults(): List<String> {
    return extractSsids()
  }

  private fun extractSsids(): List<String> {
    return try {
      val results: List<ScanResult> = wifiManager.scanResults
      Log.d(TAG, "Got ${results.size} scan results")

      results
        .mapNotNull { result -> getSsidFromScanResult(result) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .also { Log.d(TAG, "Extracted SSIDs: $it") }
    } catch (e: SecurityException) {
      Log.e(TAG, "Security exception getting scan results", e)
      emptyList()
    } catch (e: Exception) {
      Log.e(TAG, "Error getting scan results", e)
      emptyList()
    }
  }

  private fun getSsidFromScanResult(result: ScanResult): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      result.wifiSsid?.toString()?.removeSurrounding("\"")
    } else {
      @Suppress("DEPRECATION")
      result.SSID
    }
  }
}
