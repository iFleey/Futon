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
package me.fleey.futon.data.localmodel.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.data.localmodel.download.DownloadSource

/**
 * Configuration for local model management.
 */
@Serializable
data class LocalModelConfig(
  /** ID of the currently active local model (null if none active) */
  @SerialName("active_model_id")
  val activeModelId: String? = null,

  /** Preferred download source for model files */
  @SerialName("download_source")
  val downloadSource: DownloadSource = DownloadSource.HUGGING_FACE,

  /** Inference configuration settings */
  @SerialName("inference_config")
  val inferenceConfig: InferenceConfig = InferenceConfig(),
)

/**
 * Configuration for model inference.
 */
@Serializable
data class InferenceConfig(
  /** Context window size in tokens (default: 2048) */
  @SerialName("context_length")
  val contextLength: Int = DEFAULT_CONTEXT_LENGTH,

  /** Number of threads for inference (default: 4) */
  @SerialName("num_threads")
  val numThreads: Int = DEFAULT_NUM_THREADS,

  /** Whether to use Android NNAPI acceleration */
  @SerialName("use_nnapi")
  val useNnapi: Boolean = false,

  /** Timeout before unloading model when app is in background (millis) */
  @SerialName("background_unload_timeout_ms")
  val backgroundUnloadTimeoutMs: Long = DEFAULT_BACKGROUND_TIMEOUT_MS,

  /** Inference timeout in milliseconds (default: 180 seconds) */
  @SerialName("inference_timeout_ms")
  val inferenceTimeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS,

  /** Maximum image dimension for VLM inference (images will be downscaled) */
  @SerialName("max_image_dimension")
  val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
) {
  companion object {
    const val DEFAULT_CONTEXT_LENGTH = 2048
    const val DEFAULT_NUM_THREADS = 4
    const val DEFAULT_BACKGROUND_TIMEOUT_MS = 60_000L
    const val DEFAULT_INFERENCE_TIMEOUT_MS = 180_000L  // 3 minutes
    const val DEFAULT_MAX_IMAGE_DIMENSION = 768  // Downscale large images


    /**
     * Fast preset: Fewer threads, shorter context for quicker responses.

     */
    val FAST = InferenceConfig(
      contextLength = 1024,
      numThreads = 2,
      useNnapi = false,
      backgroundUnloadTimeoutMs = 30_000L,
      inferenceTimeoutMs = 120_000L,
      maxImageDimension = 512,
    )

    /**
     * Quality preset: More threads, longer context for better results.
     */
    val QUALITY = InferenceConfig(
      contextLength = 4096,
      numThreads = 6,
      useNnapi = true,
      backgroundUnloadTimeoutMs = 120_000L,
      inferenceTimeoutMs = 300_000L,
      maxImageDimension = 1024,
    )
  }
}
