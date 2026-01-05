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
import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.perception.models.DelegateTransition
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.InferenceResult
import me.fleey.futon.data.perception.models.LiteRTConfig
import me.fleey.futon.data.perception.models.LiteRTEngineState
import me.fleey.futon.data.perception.models.LiteRTEngineStats
import me.fleey.futon.data.perception.models.ModelHandle
import java.io.Closeable

/**
 * LiteRT inference engine interface.
 */
interface LiteRTEngine : Closeable {

  /**
   * Current state of the engine.
   */
  val state: LiteRTEngineState

  /**
   * Check if engine is initialized and ready.
   */
  val isReady: Boolean

  /**
   * Load a model from byte array.
   *
   * @param modelData Model data as byte array
   * @param config Inference configuration
   * @return ModelHandle on success, or invalid handle on failure
   */
  suspend fun loadModel(
    modelData: ByteArray,
    config: LiteRTConfig = LiteRTConfig(),
  ): ModelHandle

  /**
   * Load a model from file path.
   *
   * @param modelPath Path to the .tflite model file
   * @param config Inference configuration
   * @return ModelHandle on success, or invalid handle on failure
   */
  suspend fun loadModelFromFile(
    modelPath: String,
    config: LiteRTConfig = LiteRTConfig(),
  ): ModelHandle


  /**
   * Unload a previously loaded model.
   *
   * @param handle Model handle to unload
   */
  suspend fun unloadModel(handle: ModelHandle)

  /**
   * Run inference with AHardwareBuffer input (zero-copy path).
   *
   * @param handle Model handle
   * @param bufferId Buffer ID from ZeroCopyCapture
   * @param width Input width
   * @param height Input height
   * @return InferenceResult with outputs or error
   */
  suspend fun inferZeroCopy(
    handle: ModelHandle,
    bufferId: Long,
    width: Int,
    height: Int,
  ): InferenceResult

  /**
   * Run inference with HardwareBuffer input.
   *
   * @param handle Model handle
   * @param buffer HardwareBuffer containing input data
   * @param width Input width
   * @param height Input height
   * @return InferenceResult with outputs or error
   */
  suspend fun inferWithBuffer(
    handle: ModelHandle,
    buffer: HardwareBuffer,
    width: Int,
    height: Int,
  ): InferenceResult

  /**
   * Run inference with float array input.
   *
   * @param handle Model handle
   * @param input Input data as float array
   * @return InferenceResult with outputs or error
   */
  suspend fun inferFloat(
    handle: ModelHandle,
    input: FloatArray,
  ): InferenceResult

  /**
   * Get active delegate for a model.
   *
   * @param handle Model handle
   * @return Active delegate type
   */
  fun getActiveDelegate(handle: ModelHandle): DelegateType

  /**
   * Check if DSP is active.
   */
  fun isDspActive(): Boolean

  /**
   * Check if GPU is active.
   */
  fun isGpuActive(): Boolean

  /**
   * Get engine statistics.
   */
  fun getStats(): LiteRTEngineStats

  /**
   * Observe delegate transitions.
   */
  fun observeDelegateTransitions(): Flow<DelegateTransition>

  /**
   * Release all resources.
   */
  override fun close()
}
