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
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Implementation of [AllocationLimitsManager].
 */
class AllocationLimitsManagerImpl(
  private val zeroCopyCapture: ZeroCopyCapture,
  private val liteRTEngine: LiteRTEngine,
  initialLimits: AllocationLimits = AllocationLimits.DEFAULT,
) : AllocationLimitsManager {

  companion object {
    private const val TAG = "AllocationLimitsManager"
  }

  private var _limits = initialLimits
  override val limits: AllocationLimits get() = _limits

  private val _allocationState = MutableStateFlow(AllocationState.INITIAL)
  override val allocationState: StateFlow<AllocationState> = _allocationState.asStateFlow()

  private val bufferAllocationCount = AtomicInteger(0)
  private val failureCount = AtomicLong(0)
  private val cleanupCount = AtomicLong(0)

  override fun updateLimits(limits: AllocationLimits) {
    _limits = limits
    Log.i(
      TAG,
      "Updated limits: maxBuffers=${limits.maxBuffers}, " +
        "maxBufferMemory=${limits.maxBufferMemoryBytes / (1024 * 1024)}MB",
    )
    updateState()
  }

  override fun requestBufferAllocation(): Boolean {
    val currentCount = bufferAllocationCount.get()
    if (currentCount >= _limits.maxBuffers) {
      Log.w(TAG, "Buffer allocation denied: at limit ($currentCount/${_limits.maxBuffers})")
      reportAllocationFailure("Buffer limit reached")
      return false
    }

    val memoryUsage = getMemoryUsage()
    if (memoryUsage.bufferMemoryBytes >= _limits.maxBufferMemoryBytes) {
      Log.w(TAG, "Buffer allocation denied: memory limit reached")
      reportAllocationFailure("Buffer memory limit reached")
      return false
    }

    if (isUnderMemoryPressure()) {
      Log.w(TAG, "Buffer allocation denied: under memory pressure")
      reportAllocationFailure("System under memory pressure")
      return false
    }

    val newCount = bufferAllocationCount.incrementAndGet()
    Log.d(TAG, "Buffer allocation granted: $newCount/${_limits.maxBuffers}")
    updateState()
    return true
  }

  override fun releaseBufferAllocation() {
    val newCount = bufferAllocationCount.decrementAndGet().coerceAtLeast(0)
    Log.d(TAG, "Buffer released: $newCount/${_limits.maxBuffers}")
    updateState()
  }

  override fun canAllocateBuffer(): Boolean {
    val currentCount = bufferAllocationCount.get()
    if (currentCount >= _limits.maxBuffers) {
      return false
    }

    val memoryUsage = getMemoryUsage()
    if (memoryUsage.bufferMemoryBytes >= _limits.maxBufferMemoryBytes) {
      return false
    }

    return !isUnderMemoryPressure()
  }

  override fun getCurrentBufferCount(): Int {
    return bufferAllocationCount.get()
  }

  override fun reportAllocationFailure(reason: String) {
    Log.e(TAG, "Allocation failure: $reason")
    failureCount.incrementAndGet()
    _allocationState.update { state ->
      state.copy(
        lastFailureReason = reason,
        lastFailureTimeMs = System.currentTimeMillis(),
      )
    }
    cleanupPartialAllocations()
  }

  override fun cleanupPartialAllocations() {
    Log.i(TAG, "Cleaning up partial allocations")
    cleanupCount.incrementAndGet()

    val captureStats = zeroCopyCapture.getStats()
    val buffersInUse = captureStats.buffersInUse

    if (buffersInUse > 0) {
      Log.d(TAG, "Found $buffersInUse buffers in use during cleanup")
    }

    bufferAllocationCount.set(buffersInUse.coerceAtLeast(0))
    updateState()

    Log.i(TAG, "Partial allocation cleanup completed (cleanup #${cleanupCount.get()})")
  }

  override fun isUnderMemoryPressure(): Boolean {
    val memoryUsage = getMemoryUsage()
    return memoryUsage.usageRatio >= _limits.memoryPressureThreshold
  }

  override fun getMemoryUsage(): MemoryUsage {
    val captureStats = zeroCopyCapture.getStats()
    val engineStats = liteRTEngine.getStats()

    val bufferMemory = captureStats.bufferMemoryBytes
    val modelMemory = engineStats.modelMemoryBytes
    val totalMemory = bufferMemory + modelMemory
    val availableMemory = (_limits.maxTotalMemoryBytes - totalMemory).coerceAtLeast(0)
    val usageRatio = if (_limits.maxTotalMemoryBytes > 0) {
      totalMemory.toFloat() / _limits.maxTotalMemoryBytes
    } else {
      0f
    }

    return MemoryUsage(
      bufferMemoryBytes = bufferMemory,
      modelMemoryBytes = modelMemory,
      totalMemoryBytes = totalMemory,
      availableMemoryBytes = availableMemory,
      usageRatio = usageRatio,
    )
  }

  fun getFailureCount(): Long = failureCount.get()

  fun getCleanupCount(): Long = cleanupCount.get()

  private fun updateState() {
    val memoryUsage = getMemoryUsage()
    val currentCount = bufferAllocationCount.get()

    _allocationState.update { state ->
      state.copy(
        currentBufferCount = currentCount,
        currentBufferMemoryBytes = memoryUsage.bufferMemoryBytes,
        currentModelMemoryBytes = memoryUsage.modelMemoryBytes,
        isAtBufferLimit = currentCount >= _limits.maxBuffers,
        isUnderMemoryPressure = memoryUsage.usageRatio >= _limits.memoryPressureThreshold,
      )
    }
  }
}
