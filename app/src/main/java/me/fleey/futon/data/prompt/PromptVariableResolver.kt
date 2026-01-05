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
package me.fleey.futon.data.prompt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import me.fleey.futon.data.ai.repository.ProviderRepository
import org.koin.core.annotation.Single
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Resolves prompt template variables with actual runtime values.
 */
interface PromptVariableResolver {
  suspend fun buildContext(
    task: String? = null,
    stepNumber: Int? = null,
    maxSteps: Int? = null,
    appName: String? = null,
    appPackage: String? = null,
    appActivity: String? = null,
    sessionId: String? = null,
    startTime: Long? = null,
  ): Map<String, String>

  fun resolve(content: String, context: Map<String, String>): String
}

@Single(binds = [PromptVariableResolver::class])
class PromptVariableResolverImpl(
  private val context: Context,
  private val providerRepository: ProviderRepository,
) : PromptVariableResolver {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
  private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
  private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  private val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())

  override suspend fun buildContext(
    task: String?,
    stepNumber: Int?,
    maxSteps: Int?,
    appName: String?,
    appPackage: String?,
    appActivity: String?,
    sessionId: String?,
    startTime: Long?,
  ): Map<String, String> {
    val now = Date()
    val activeProviderWithModel = providerRepository.getFirstEnabledProviderWithModel()

    return buildMap {
      task?.let { put("task", it) }
      stepNumber?.let { put("step_number", it.toString()) }
      maxSteps?.let { put("max_steps", it.toString()) }

      appName?.let { put("app_name", it) }
      appPackage?.let { put("app_package", it) }
      appActivity?.let { put("app_activity", it) }

      put("timestamp", dateFormat.format(now))
      put("date", dateOnlyFormat.format(now))
      put("time", timeOnlyFormat.format(now))
      put("day_of_week", dayOfWeekFormat.format(now))
      put("timezone", TimeZone.getDefault().id)

      put("device_model", Build.MODEL)
      put("device_brand", Build.MANUFACTURER)
      put("android_version", Build.VERSION.RELEASE)
      put("sdk_version", Build.VERSION.SDK_INT.toString())

      val (width, height) = getScreenSize()
      put("screen_size", "${width}x${height}")
      put("screen_density", getScreenDensity().toString())
      put("orientation", getOrientation())

      activeProviderWithModel?.let { (provider, model) ->
        put("model_name", model.getDisplayName)
        put("provider_name", provider.name)
      }

      val locale = Locale.getDefault()
      put("locale", "${locale.language}_${locale.country}")
      put("language", locale.displayLanguage)

      put("battery_level", getBatteryLevel().toString())
      put("network_type", getNetworkType())

      sessionId?.let { put("session_id", it) }
      startTime?.let {
        val elapsed = (System.currentTimeMillis() - it) / 1000
        put("elapsed_time", elapsed.toString())
      }
    }
  }

  override fun resolve(content: String, context: Map<String, String>): String {
    var result = content
    context.forEach { (key, value) ->
      result = result.replace("{{$key}}", value)
    }
    return result
  }

  private fun getScreenSize(): Pair<Int, Int> {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = windowManager.currentWindowMetrics.bounds
      bounds.width() to bounds.height()
    } else {
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(metrics)
      metrics.widthPixels to metrics.heightPixels
    }
  }

  private fun getScreenDensity(): Int {
    return context.resources.displayMetrics.densityDpi
  }

  private fun getOrientation(): String {
    return when (context.resources.configuration.orientation) {
      Configuration.ORIENTATION_LANDSCAPE -> "landscape"
      Configuration.ORIENTATION_PORTRAIT -> "portrait"
      else -> "unknown"
    }
  }

  private fun getBatteryLevel(): Int {
    val batteryStatus = context.registerReceiver(
      null,
      IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    )
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) {
      (level * 100 / scale)
    } else {
      -1
    }
  }

  private fun getNetworkType(): String {
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return "None"
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "None"

    return when {
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
      else -> "Other"
    }
  }
}
