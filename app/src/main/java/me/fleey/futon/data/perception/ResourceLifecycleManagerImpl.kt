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
package me.fleey.futon.data.perception

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ResourceLifecycleManagerImpl(
  private val zeroCopyCapture: ZeroCopyCapture,
  private val liteRTEngine: LiteRTEngine,
  private val ocrEngine: OCREngine,
  private val uiDetector: UIDetector,
) : ResourceLifecycleManager {

  companion object {
    private const val TAG = "ResourceLifecycleManager"
    private const val RESUME_TIMEOUT_MS = 100L
  }

  private val _lifecycleState = MutableStateFlow(ResourceLifecycleState.UNINITIALIZED)
  override val lifecycleState: StateFlow<ResourceLifecycleState> = _lifecycleState.asStateFlow()

  private data class ManagedResource(
    val resource: Closeable,
    val priority: Int,
  )

  private val managedResources = ConcurrentHashMap<Closeable, ManagedResource>()
  private val lastCleanupTime = AtomicLong(0)

  init {
    registerCoreResources()
    _lifecycleState.value = ResourceLifecycleState.ACTIVE
  }

  private fun registerCoreResources() {
    registerResource(zeroCopyCapture, ResourceLifecycleManager.HIGH_PRIORITY)
    registerResource(liteRTEngine, ResourceLifecycleManager.DEFAULT_PRIORITY)
    registerResource(ocrEngine, ResourceLifecycleManager.DEFAULT_PRIORITY)
    registerResource(uiDetector, ResourceLifecycleManager.LOW_PRIORITY)
  }

  override fun registerResource(resource: Closeable, priority: Int) {
    if (_lifecycleState.value == ResourceLifecycleState.DESTROYED) {
      Log.w(TAG, "Cannot register resource - lifecycle manager is destroyed")
      return
    }
    managedResources[resource] = ManagedResource(resource, priority)
    Log.d(TAG, "Registered resource: ${resource.javaClass.simpleName}, priority=$priority")
  }

  override fun unregisterResource(resource: Closeable) {
    managedResources.remove(resource)
    Log.d(TAG, "Unregistered resource: ${resource.javaClass.simpleName}")
  }

  override fun pause() {
    if (_lifecycleState.value != ResourceLifecycleState.ACTIVE) {
      Log.w(TAG, "Cannot pause - current state: ${_lifecycleState.value}")
      return
    }

    Log.i(TAG, "Pausing resources")
    _lifecycleState.value = ResourceLifecycleState.PAUSED

    zeroCopyCapture.pause()

    Log.i(TAG, "Resources paused")
  }

  override suspend fun resume(): Boolean {
    if (_lifecycleState.value != ResourceLifecycleState.PAUSED) {
      return _lifecycleState.value == ResourceLifecycleState.ACTIVE
    }

    Log.i(TAG, "Resuming resources")

    val resumed = withTimeoutOrNull(RESUME_TIMEOUT_MS) {
      zeroCopyCapture.resume()
    } ?: false

    if (resumed) {
      _lifecycleState.value = ResourceLifecycleState.ACTIVE
      Log.i(TAG, "Resources resumed within ${RESUME_TIMEOUT_MS}ms")
    } else {
      Log.w(TAG, "Resume timed out after ${RESUME_TIMEOUT_MS}ms")
    }

    return resumed
  }

  override fun isHealthy(): Boolean {
    return _lifecycleState.value == ResourceLifecycleState.ACTIVE &&
      zeroCopyCapture.isReady() &&
      liteRTEngine.isReady &&
      ocrEngine.isInitialized()
  }

  override fun getStats(): ResourceStats {
    val captureStats = zeroCopyCapture.getStats()
    val engineStats = liteRTEngine.getStats()

    return ResourceStats(
      registeredResources = managedResources.size,
      activeResources = countActiveResources(),
      totalMemoryBytes = captureStats.bufferMemoryBytes + engineStats.modelMemoryBytes,
      bufferMemoryBytes = captureStats.bufferMemoryBytes,
      modelMemoryBytes = engineStats.modelMemoryBytes,
      lastCleanupTimeMs = lastCleanupTime.get(),
    )
  }

  private fun countActiveResources(): Int {
    var count = 0
    if (zeroCopyCapture.isReady()) count++
    if (liteRTEngine.isReady) count++
    if (ocrEngine.isInitialized()) count++
    if (uiDetector.isReady) count++
    return count
  }

  override fun forceCleanup() {
    Log.i(TAG, "Force cleanup requested")
    _lifecycleState.value = ResourceLifecycleState.CLEANING_UP

    val sortedResources = managedResources.values
      .sortedBy { it.priority }

    for (managed in sortedResources) {
      try {
        managed.resource.close()
        Log.d(TAG, "Closed resource: ${managed.resource.javaClass.simpleName}")
      } catch (e: Exception) {
        Log.e(TAG, "Error closing resource: ${managed.resource.javaClass.simpleName}", e)
      }
    }

    managedResources.clear()
    lastCleanupTime.set(System.currentTimeMillis())
    _lifecycleState.value = ResourceLifecycleState.DESTROYED

    Log.i(TAG, "Force cleanup completed")
  }

  override fun close() {
    if (_lifecycleState.value == ResourceLifecycleState.DESTROYED) {
      Log.d(TAG, "Already destroyed")
      return
    }

    Log.i(TAG, "Closing resource lifecycle manager")
    _lifecycleState.value = ResourceLifecycleState.CLEANING_UP

    val sortedResources = managedResources.values
      .sortedBy { it.priority }

    for (managed in sortedResources) {
      try {
        managed.resource.close()
        Log.d(TAG, "Closed resource: ${managed.resource.javaClass.simpleName}")
      } catch (e: Exception) {
        Log.e(TAG, "Error closing resource: ${managed.resource.javaClass.simpleName}", e)
      }
    }

    managedResources.clear()
    lastCleanupTime.set(System.currentTimeMillis())
    _lifecycleState.value = ResourceLifecycleState.DESTROYED

    Log.i(TAG, "Resource lifecycle manager closed")
  }
}
