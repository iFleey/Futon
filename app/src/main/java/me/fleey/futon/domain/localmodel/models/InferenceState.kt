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
package me.fleey.futon.domain.localmodel.models

import me.fleey.futon.data.localmodel.models.QuantizationType

sealed class InferenceState {

  /**
   * No model is loaded, engine is idle.
   */
  data object Idle : InferenceState()

  data class Loading(
    val message: String,
    val progress: Float? = null,
  ) : InferenceState()

  data class Ready(
    val modelId: String,
    val memoryUsageMb: Int,
  ) : InferenceState()

  data class Inferring(
    val modelId: String,
  ) : InferenceState()

  data class Error(
    val error: InferenceError,
  ) : InferenceState()
}


sealed class InferenceError {

  data object NoActiveModel : InferenceError() {
    override fun toString(): String = "No local model is currently active"
  }

  data class ModelNotFound(
    val modelId: String,
    val message: String,
  ) : InferenceError() {
    override fun toString(): String = "Model not found: $message"
  }

  data class LoadFailed(
    val message: String,
  ) : InferenceError() {
    override fun toString(): String = "Failed to load model: $message"
  }

  /**
   * Out of memory error during model loading or inference.

   * @property suggestedQuantization Recommended quantization to try
   */
  data class OutOfMemory(
    val suggestedQuantization: QuantizationType?,
  ) : InferenceError() {
    override fun toString(): String = buildString {
      append("Out of memory")
      suggestedQuantization?.let {
        append(". Try using ${it.name} quantization for lower memory usage")
      }
    }
  }


  data class InferenceFailed(
    val message: String,
  ) : InferenceError() {
    override fun toString(): String = "Inference failed: $message"
  }

  data class InvalidInput(
    val message: String,
  ) : InferenceError() {
    override fun toString(): String = "Invalid input: $message"
  }

  data class NativeError(
    val code: Int,
    val message: String,
  ) : InferenceError() {
    override fun toString(): String = "Native error ($code): $message"
  }
}
