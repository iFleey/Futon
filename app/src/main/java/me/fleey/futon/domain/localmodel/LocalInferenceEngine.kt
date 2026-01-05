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
package me.fleey.futon.domain.localmodel

import android.hardware.HardwareBuffer
import kotlinx.coroutines.flow.Flow
import me.fleey.futon.data.ai.AIClient
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.domain.localmodel.models.InferenceState
import me.fleey.futon.domain.localmodel.models.MemoryUsage

/**
 * Interface for local AI model inference engine.
 * */
interface LocalInferenceEngine : AIClient {

  /**
   * Flow of current inference state (idle, loading, ready, inferring, error).
   */
  val inferenceState: Flow<InferenceState>

  val memoryUsage: Flow<MemoryUsage>

  fun isModelLoaded(): Boolean

  suspend fun loadModel(): Result<Unit>

  suspend fun unloadModel(): Result<Unit>

  suspend fun analyzeBuffer(
    buffer: HardwareBuffer,
    taskDescription: String,
    uiContext: String? = null,
  ): AIResponse

  suspend fun onAppBackground()

  suspend fun onAppForeground()

  fun getCurrentMemoryUsage(): MemoryUsage?
}
