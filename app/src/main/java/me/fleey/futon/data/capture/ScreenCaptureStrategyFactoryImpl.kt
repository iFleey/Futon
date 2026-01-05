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
package me.fleey.futon.data.capture

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.platform.root.RootShell

/**
 * Implementation of [ScreenCaptureStrategyFactory] that manages screenshot
 * capture method selection. Root-Only architecture.
 *
 * This factory:
 * - Detects available capture methods on startup
 * - Uses root-based capture as the primary method
 * - Caches capability detection results
 * - Supports user preference override
 */
import org.koin.core.annotation.Single

@Single(binds = [ScreenCaptureStrategyFactory::class])
class ScreenCaptureStrategyFactoryImpl(
  private val rootShell: RootShell,
) : ScreenCaptureStrategyFactory {

  companion object {
    private const val TAG = "ScreenCaptureFactory"
    private const val CACHE_VALIDITY_MS = 5000L
  }

  private val mutex = Mutex()

  private val rootCapture: RootScreenCapture by lazy {
    RootScreenCapture(rootShell)
  }

  private var cachedCapabilities: CaptureCapabilities? = null
  private var cacheTimestamp: Long = 0L

  private var preferredMethod: CaptureMethod? = null
  private var currentMethod: CaptureMethod? = null

  override suspend fun detectCapabilities(): CaptureCapabilities {
    return mutex.withLock {
      val now = System.currentTimeMillis()

      cachedCapabilities?.let { cached ->
        if (now - cacheTimestamp < CACHE_VALIDITY_MS) {
          return@withLock cached
        }
      }

      val rootAvailable = rootCapture.isAvailable()
      val rootError = if (!rootAvailable) "Root access not available" else null

      val capabilities = CaptureCapabilities(
        rootAvailable = rootAvailable,
        rootError = rootError,
        mediaProjectionAvailable = false,
      )

      cachedCapabilities = capabilities
      cacheTimestamp = now

      Log.d(TAG, "Capabilities detected: root=$rootAvailable")

      if (currentMethod == null && rootAvailable) {
        currentMethod = CaptureMethod.ROOT_SCREENCAP
        Log.d(TAG, "Auto-selected capture method: ROOT_SCREENCAP")
      }

      capabilities
    }
  }

  override suspend fun getAvailableMethods(): List<CaptureMethod> {
    val capabilities = detectCapabilities()
    return buildList {
      if (capabilities.rootAvailable) {
        add(CaptureMethod.ROOT_SCREENCAP)
      }
      if (capabilities.mediaProjectionAvailable) {
        add(CaptureMethod.MEDIA_PROJECTION)
      }
    }
  }

  override suspend fun selectBestMethod(): ScreenCapture {
    val availableMethods = getAvailableMethods()

    if (availableMethods.isEmpty()) {
      throw IllegalStateException(
        "No screenshot capture method available. Root access is required.",
      )
    }

    preferredMethod?.let { preferred ->
      if (preferred in availableMethods) {
        currentMethod = preferred
        Log.d(TAG, "Using preferred method: $preferred")
        return getCaptureForMethod(preferred)
      }
      Log.w(TAG, "Preferred method $preferred not available, using fallback")
    }

    val selectedMethod = when {
      CaptureMethod.ROOT_SCREENCAP in availableMethods -> CaptureMethod.ROOT_SCREENCAP
      CaptureMethod.MEDIA_PROJECTION in availableMethods -> CaptureMethod.MEDIA_PROJECTION
      else -> throw IllegalStateException("No capture method available")
    }

    currentMethod = selectedMethod
    Log.d(TAG, "Selected best method: $selectedMethod")
    return getCaptureForMethod(selectedMethod)
  }

  override suspend fun getCapture(method: CaptureMethod): ScreenCapture? {
    val capabilities = detectCapabilities()

    return when (method) {
      CaptureMethod.ROOT_SCREENCAP -> {
        if (capabilities.rootAvailable) rootCapture else null
      }

      CaptureMethod.MEDIA_PROJECTION -> null
    }
  }

  override fun setPreferredMethod(method: CaptureMethod?) {
    preferredMethod = method
    Log.d(TAG, "Preferred method set to: $method")

    if (method != currentMethod) {
      currentMethod = null
    }
  }

  override fun getCurrentMethod(): CaptureMethod? = currentMethod

  private fun getCaptureForMethod(method: CaptureMethod): ScreenCapture {
    return when (method) {
      CaptureMethod.ROOT_SCREENCAP -> rootCapture
      CaptureMethod.MEDIA_PROJECTION -> {
        throw IllegalStateException("Media projection not implemented")
      }
    }
  }

  fun invalidateCache() {
    cachedCapabilities = null
    cacheTimestamp = 0L
  }
}
