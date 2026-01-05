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

import kotlinx.coroutines.flow.StateFlow

/**
 * Manages allocation limits for perception system resources.
 */
interface AllocationLimitsManager {

  val allocationState: StateFlow<AllocationState>

  val limits: AllocationLimits

  fun updateLimits(limits: AllocationLimits)

  /**
   * Request buffer allocation.
   *
   * @return true if allocation is allowed, false if limit reached
   */
  fun requestBufferAllocation(): Boolean

  fun releaseBufferAllocation()

  fun canAllocateBuffer(): Boolean

  fun getCurrentBufferCount(): Int

  fun reportAllocationFailure(reason: String)

  fun cleanupPartialAllocations()

  fun isUnderMemoryPressure(): Boolean

  fun getMemoryUsage(): MemoryUsage
}

data class AllocationLimits(
  val maxBuffers: Int = DEFAULT_MAX_BUFFERS,
  val maxBufferMemoryBytes: Long = DEFAULT_MAX_BUFFER_MEMORY,
  val maxModelMemoryBytes: Long = DEFAULT_MAX_MODEL_MEMORY,
  val maxTotalMemoryBytes: Long = DEFAULT_MAX_TOTAL_MEMORY,
  val memoryPressureThreshold: Float = DEFAULT_MEMORY_PRESSURE_THRESHOLD,
) {
  init {
    require(maxBuffers in 1..MAX_ALLOWED_BUFFERS) {
      "Max buffers must be between 1 and $MAX_ALLOWED_BUFFERS"
    }
    require(maxBufferMemoryBytes > 0) { "Max buffer memory must be positive" }
    require(maxModelMemoryBytes > 0) { "Max model memory must be positive" }
    require(maxTotalMemoryBytes > 0) { "Max total memory must be positive" }
    require(memoryPressureThreshold in 0f..1f) {
      "Memory pressure threshold must be between 0 and 1"
    }
  }

  companion object {
    const val DEFAULT_MAX_BUFFERS = 3
    const val MAX_ALLOWED_BUFFERS = 5
    const val DEFAULT_MAX_BUFFER_MEMORY = 100L * 1024 * 1024 // 100 MB
    const val DEFAULT_MAX_MODEL_MEMORY = 50L * 1024 * 1024 // 50 MB
    const val DEFAULT_MAX_TOTAL_MEMORY = 200L * 1024 * 1024 // 200 MB
    const val DEFAULT_MEMORY_PRESSURE_THRESHOLD = 0.8f

    val DEFAULT = AllocationLimits()
  }
}

data class AllocationState(
  val currentBufferCount: Int,
  val currentBufferMemoryBytes: Long,
  val currentModelMemoryBytes: Long,
  val isAtBufferLimit: Boolean,
  val isUnderMemoryPressure: Boolean,
  val lastFailureReason: String?,
  val lastFailureTimeMs: Long?,
) {
  val currentTotalMemoryBytes: Long
    get() = currentBufferMemoryBytes + currentModelMemoryBytes

  companion object {
    val INITIAL = AllocationState(
      currentBufferCount = 0,
      currentBufferMemoryBytes = 0,
      currentModelMemoryBytes = 0,
      isAtBufferLimit = false,
      isUnderMemoryPressure = false,
      lastFailureReason = null,
      lastFailureTimeMs = null,
    )
  }
}

data class MemoryUsage(
  val bufferMemoryBytes: Long,
  val modelMemoryBytes: Long,
  val totalMemoryBytes: Long,
  val availableMemoryBytes: Long,
  val usageRatio: Float,
) {
  val bufferMemoryMb: Float get() = bufferMemoryBytes / (1024f * 1024f)
  val modelMemoryMb: Float get() = modelMemoryBytes / (1024f * 1024f)
  val totalMemoryMb: Float get() = totalMemoryBytes / (1024f * 1024f)
  val availableMemoryMb: Float get() = availableMemoryBytes / (1024f * 1024f)
}
