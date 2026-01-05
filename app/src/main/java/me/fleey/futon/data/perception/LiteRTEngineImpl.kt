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

import android.hardware.HardwareBuffer
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.fleey.futon.data.perception.models.DelegateTransition
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.InferenceError
import me.fleey.futon.data.perception.models.InferenceResult
import me.fleey.futon.data.perception.models.LiteRTConfig
import me.fleey.futon.data.perception.models.LiteRTEngineState
import me.fleey.futon.data.perception.models.LiteRTEngineStats
import me.fleey.futon.data.perception.models.ModelHandle

/**
 * Stub implementation of LiteRTEngine.
 *
 * All inference has been migrated to the daemon process. This stub exists for
 * backward compatibility and returns appropriate error states.
 *
 * Use DaemonPerceptionProxy for actual inference operations.
 */
class LiteRTEngineImpl : LiteRTEngine {

  companion object {
    private const val TAG = "LiteRTEngineImpl"
    private const val STUB_MESSAGE =
      "LiteRT inference has been migrated to daemon. Use DaemonPerceptionProxy."
  }

  override val state: LiteRTEngineState = LiteRTEngineState.UNINITIALIZED
  override val isReady: Boolean = false

  private val _delegateTransitions = MutableSharedFlow<DelegateTransition>(replay = 1)

  override suspend fun loadModel(
    modelData: ByteArray,
    config: LiteRTConfig,
  ): ModelHandle {
    Log.w(TAG, STUB_MESSAGE)
    return ModelHandle.INVALID
  }

  override suspend fun loadModelFromFile(
    modelPath: String,
    config: LiteRTConfig,
  ): ModelHandle {
    Log.w(TAG, STUB_MESSAGE)
    return ModelHandle.INVALID
  }

  override suspend fun unloadModel(handle: ModelHandle) {
    Log.w(TAG, STUB_MESSAGE)
  }

  override suspend fun inferZeroCopy(
    handle: ModelHandle,
    bufferId: Long,
    width: Int,
    height: Int,
  ): InferenceResult {
    Log.w(TAG, STUB_MESSAGE)
    return InferenceResult.Failure(
      InferenceError.NOT_INITIALIZED,
      STUB_MESSAGE,
    )
  }

  override suspend fun inferWithBuffer(
    handle: ModelHandle,
    buffer: HardwareBuffer,
    width: Int,
    height: Int,
  ): InferenceResult {
    Log.w(TAG, STUB_MESSAGE)
    return InferenceResult.Failure(
      InferenceError.NOT_INITIALIZED,
      STUB_MESSAGE,
    )
  }

  override suspend fun inferFloat(
    handle: ModelHandle,
    input: FloatArray,
  ): InferenceResult {
    Log.w(TAG, STUB_MESSAGE)
    return InferenceResult.Failure(
      InferenceError.NOT_INITIALIZED,
      STUB_MESSAGE,
    )
  }

  override fun getActiveDelegate(handle: ModelHandle): DelegateType = DelegateType.NONE

  override fun isDspActive(): Boolean = false

  override fun isGpuActive(): Boolean = false

  override fun getStats(): LiteRTEngineStats {
    return LiteRTEngineStats(
      modelMemoryBytes = 0L,
      activeDelegate = DelegateType.NONE,
      isDspActive = false,
      isGpuActive = false,
      delegateInitHistory = emptyList(),
    )
  }

  override fun observeDelegateTransitions(): Flow<DelegateTransition> {
    return _delegateTransitions.asSharedFlow()
  }

  override fun close() {
    Log.d(TAG, "LiteRTEngine stub closed")
  }
}
