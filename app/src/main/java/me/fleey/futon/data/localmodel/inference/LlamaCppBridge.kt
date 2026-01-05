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
package me.fleey.futon.data.localmodel.inference

import android.hardware.HardwareBuffer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * JNI Bridge to llama.cpp for local model inference.
 * Provides GGUF model loading, VLM support, and thread-safe model management.
 */
import org.koin.core.annotation.Single

@Single
class LlamaCppBridge {

  companion object {
    private const val TAG = "LlamaCppBridge"

    /**
     * Flag indicating whether the native library is available.
     */
    var isNativeLibraryLoaded: Boolean = false
      private set

    /**
     * Flag indicating whether llama.cpp is compiled and functional.
     */
    var isLlamaAvailable: Boolean = false
      private set

    /**
     * Flag indicating whether CLIP/VLM support is available.
     */
    var isClipAvailable: Boolean = false
      private set

    init {
      try {
        // Load dependencies first in correct order
        // These are required by libllama_android.so
        try {
          System.loadLibrary("c++_shared")
          Log.d(TAG, "Loaded c++_shared")
        } catch (e: UnsatisfiedLinkError) {
          Log.d(TAG, "c++_shared already loaded or not needed: ${e.message}")
        }

        try {
          System.loadLibrary("ggml-base")
          Log.d(TAG, "Loaded ggml-base")
        } catch (e: UnsatisfiedLinkError) {
          Log.d(TAG, "ggml-base load failed: ${e.message}")
        }

        try {
          System.loadLibrary("ggml-cpu")
          Log.d(TAG, "Loaded ggml-cpu")
        } catch (e: UnsatisfiedLinkError) {
          Log.d(TAG, "ggml-cpu load failed: ${e.message}")
        }

        try {
          System.loadLibrary("ggml")
          Log.d(TAG, "Loaded ggml")
        } catch (e: UnsatisfiedLinkError) {
          Log.d(TAG, "ggml load failed: ${e.message}")
        }

        try {
          System.loadLibrary("llama")
          Log.d(TAG, "Loaded llama")
        } catch (e: UnsatisfiedLinkError) {
          Log.d(TAG, "llama load failed: ${e.message}")
        }

        // Now load the main JNI bridge library
        System.loadLibrary("llama_android")
        isNativeLibraryLoaded = true
        Log.i(TAG, "llama_android native library loaded successfully")

        // Check if llama.cpp is actually compiled
        isLlamaAvailable = isAvailable()
        isClipAvailable = isClipAvailableNative()

        Log.i(TAG, "llama.cpp available: $isLlamaAvailable")
        Log.i(TAG, "CLIP/VLM support: $isClipAvailable")

        if (!isLlamaAvailable) {
          Log.w(TAG, "Native library loaded but llama.cpp not compiled")
          Log.w(TAG, "Local model inference will not be available")
        }
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "llama_android native library not available: ${e.message}")
        Log.e(TAG, "Stack trace:", e)
        Log.w(TAG, "Local model inference will not be available")
        isNativeLibraryLoaded = false
        isLlamaAvailable = false
        isClipAvailable = false
      }
    }

    /**
     * Native method to check if llama.cpp is available.
     */
    @JvmStatic
    private external fun isAvailable(): Boolean

    /**
     * Native method to check if CLIP support is available.
     */
    @JvmStatic
    private external fun isClipAvailableNative(): Boolean

    /**
     * Native method to get llama.cpp version.
     */
    @JvmStatic
    external fun getVersion(): String
  }

  // Model handle (0 = no model loaded)
  private var modelHandle: Long = 0

  // Mutex for thread-safe operations
  private val mutex = Mutex()

  private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
  val loadingState: Flow<LoadingState> = _loadingState.asStateFlow()

  private var currentConfig: ModelLoadConfig? = null

  // Native Methods

  /**
   * Native method to load a GGUF model.
   *
   * @param modelPath Path to the GGUF model file
   * @param mmprojPath Path to the mmproj file (null for text-only models)
   * @param contextSize Context window size in tokens
   * @param numThreads Number of threads for inference
   * @param useNnapi Whether to use Android NNAPI acceleration
   * @return Model handle (>0 on success, 0 on failure)
   */
  private external fun loadModelNative(
    modelPath: String,
    mmprojPath: String?,
    contextSize: Int,
    numThreads: Int,
    useNnapi: Boolean,
  ): Long

  /**
   * Native method to unload a model from memory.
   *
   * @param handle Model handle returned by loadModel
   */
  private external fun unloadModelNative(handle: Long)

  /**
   * Native method to run inference.
   *
   * @param handle Model handle
   * @param prompt Text prompt for the model
   * @param imageBytes Image data as byte array (null for text-only inference)
   * @param maxTokens Maximum tokens to generate
   * @param temperature Sampling temperature
   * @return Generated text response
   */
  private external fun inferenceNative(
    handle: Long,
    prompt: String,
    imageBytes: ByteArray?,
    maxTokens: Int,
    temperature: Float,
  ): String

  /**
   * Native method to get memory usage.
   *
   * @param handle Model handle
   * @return Memory usage in bytes
   */
  private external fun getMemoryUsageNative(handle: Long): Long

  /**
   * Native method to analyze a HardwareBuffer directly.
   * Enables zero-copy transfer from Daemon via Binder IPC.
   *
   * @param handle Model handle
   * @param hardwareBuffer The HardwareBuffer containing the screenshot
   * @param prompt Text prompt for the model
   * @param maxTokens Maximum tokens to generate
   * @param temperature Sampling temperature
   * @return Generated text response
   */
  private external fun analyzeBufferNative(
    handle: Long,
    hardwareBuffer: HardwareBuffer,
    prompt: String,
    maxTokens: Int,
    temperature: Float,
  ): String

  /**
   * Load a model with the specified configuration.
   *
   * @param config Model loading configuration
   * @return Result indicating success or failure with error message
   */
  suspend fun loadModel(config: ModelLoadConfig): Result<Unit> = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (!isNativeLibraryLoaded) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LIBRARY_NOT_LOADED,
            "Native library not loaded",
          ),
        )
      }

      if (!isLlamaAvailable) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LLAMA_NOT_COMPILED,
            "llama.cpp not compiled. Please add llama.cpp to the project.",
          ),
        )
      }

      // Unload existing model if any
      if (modelHandle != 0L) {
        Log.i(TAG, "Unloading existing model before loading new one")
        unloadModelNative(modelHandle)
        modelHandle = 0
      }

      _loadingState.value = LoadingState.Loading("Loading model...")

      try {
        Log.i(TAG, "Loading model: ${config.modelPath}")
        Log.i(TAG, "  mmproj: ${config.mmprojPath ?: "(none)"}")
        Log.i(TAG, "  contextSize: ${config.contextSize}, threads: ${config.numThreads}")

        val handle = loadModelNative(
          modelPath = config.modelPath,
          mmprojPath = config.mmprojPath,
          contextSize = config.contextSize,
          numThreads = config.numThreads,
          useNnapi = config.useNnapi,
        )

        if (handle > 0) {
          modelHandle = handle
          currentConfig = config
          _loadingState.value = LoadingState.Loaded
          Log.i(TAG, "Model loaded successfully with handle: $handle")
          Result.success(Unit)
        } else {
          _loadingState.value = LoadingState.Error("Failed to load model")
          Result.failure(
            LlamaException(
              LlamaErrorCode.MODEL_LOAD_FAILED,
              "Failed to load model from: ${config.modelPath}",
            ),
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading model", e)
        _loadingState.value = LoadingState.Error(e.message ?: "Unknown error")
        Result.failure(
          LlamaException(
            LlamaErrorCode.NATIVE_ERROR,
            "Native error: ${e.message}",
            e,
          ),
        )
      }
    }
  }

  /**
   * Run inference with the loaded model.
   *
   * @param prompt Text prompt for the model
   * @param image Image data (null for text-only inference)
   * @param maxTokens Maximum tokens to generate
   * @param temperature Sampling temperature (0.0 - 1.0)
   * @param timeoutMs Inference timeout in milliseconds (default: 180 seconds)
   * @return Result containing the generated text or error
   */
  suspend fun inference(
    prompt: String,
    image: ByteArray? = null,
    maxTokens: Int = 512,
    temperature: Float = 0.1f,
    timeoutMs: Long = 180_000L,
  ): Result<String> = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (!isNativeLibraryLoaded) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LIBRARY_NOT_LOADED,
            "Native library not loaded",
          ),
        )
      }

      if (!isLlamaAvailable) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LLAMA_NOT_COMPILED,
            "llama.cpp not compiled",
          ),
        )
      }

      if (modelHandle == 0L) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.MODEL_NOT_LOADED,
            "No model loaded. Call loadModel() first.",
          ),
        )
      }

      // Warn if image provided but CLIP not available
      if (image != null && !isClipAvailable) {
        Log.w(TAG, "Image provided but CLIP support not available - image will be ignored")
      }

      try {
        Log.i(TAG, "========== Starting Inference ==========")
        Log.i(TAG, "  Prompt length: ${prompt.length} chars")
        Log.i(TAG, "  Image size: ${image?.size ?: 0} bytes")
        Log.i(TAG, "  Max tokens: $maxTokens")
        Log.i(TAG, "  Temperature: $temperature")
        Log.i(TAG, "  Timeout: ${timeoutMs}ms (${timeoutMs / 1000}s)")

        val startTime = System.currentTimeMillis()

        val result = try {
          withTimeout(timeoutMs) {
            Log.i(TAG, "Calling native inference...")
            inferenceNative(
              handle = modelHandle,
              prompt = prompt,
              imageBytes = image,
              maxTokens = maxTokens,
              temperature = temperature,
            )
          }
        } catch (e: TimeoutCancellationException) {
          val elapsed = System.currentTimeMillis() - startTime
          Log.e(TAG, "Inference timed out after ${elapsed}ms")
          return@withContext Result.failure(
            LlamaException(
              LlamaErrorCode.INFERENCE_TIMEOUT,
              timeoutSeconds = (timeoutMs / 1000).toInt(),
            ),
          )
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "========== Inference Complete ==========")
        Log.i(TAG, "  Time: ${elapsed}ms (${elapsed / 1000}s)")
        Log.i(TAG, "  Response length: ${result.length} chars")
        Log.i(TAG, "  Response preview: ${result.take(200)}...")

        // Check for error response from native code
        if (result.startsWith("{\"error\":")) {
          Log.e(TAG, "Native error response: $result")
          return@withContext Result.failure(
            LlamaException(
              LlamaErrorCode.INFERENCE_FAILED,
              "Inference failed: $result",
            ),
          )
        }

        Result.success(result)
      } catch (e: Exception) {
        Log.e(TAG, "Error during inference", e)
        Result.failure(
          LlamaException(
            LlamaErrorCode.NATIVE_ERROR,
            "Native error during inference: ${e.message}",
            e,
          ),
        )
      }
    }
  }

  /**
   * Unload the current model from memory.
   */
  suspend fun unload(): Unit = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (modelHandle != 0L && isNativeLibraryLoaded) {
        try {
          unloadModelNative(modelHandle)
          Log.i(TAG, "Model unloaded: $modelHandle")
        } catch (e: Exception) {
          Log.e(TAG, "Error unloading model", e)
        }
        modelHandle = 0
        currentConfig = null
        _loadingState.value = LoadingState.Idle
      }
    }
  }

  /**
   * Check if a model is currently loaded.
   */
  fun isLoaded(): Boolean = modelHandle != 0L

  fun getCurrentConfig(): ModelLoadConfig? = currentConfig

  fun getMemoryUsageBytes(): Long {
    if (!isNativeLibraryLoaded || modelHandle == 0L) {
      return 0
    }

    return try {
      getMemoryUsageNative(modelHandle)
    } catch (e: Exception) {
      Log.e(TAG, "Error getting memory usage", e)
      0
    }
  }

  fun getMemoryUsageMb(): Int {
    return (getMemoryUsageBytes() / (1024 * 1024)).toInt()
  }

  suspend fun analyzeBuffer(
    buffer: HardwareBuffer,
    prompt: String,
    maxTokens: Int = 512,
    temperature: Float = 0.1f,
    timeoutMs: Long = 180_000L,
  ): Result<String> = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (!isNativeLibraryLoaded) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LIBRARY_NOT_LOADED,
            "Native library not loaded",
          ),
        )
      }

      if (!isLlamaAvailable) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.LLAMA_NOT_COMPILED,
            "llama.cpp not compiled",
          ),
        )
      }

      if (modelHandle == 0L) {
        return@withContext Result.failure(
          LlamaException(
            LlamaErrorCode.MODEL_NOT_LOADED,
            "No model loaded. Call loadModel() first.",
          ),
        )
      }

      if (!isClipAvailable) {
        Log.w(TAG, "CLIP support not available - HardwareBuffer image will be ignored")
      }

      try {
        Log.i(TAG, "========== Starting HardwareBuffer Analysis ==========")
        Log.i(TAG, "  Buffer: ${buffer.width}x${buffer.height}, format=${buffer.format}")
        Log.i(TAG, "  Prompt length: ${prompt.length} chars")
        Log.i(TAG, "  Max tokens: $maxTokens")
        Log.i(TAG, "  Temperature: $temperature")
        Log.i(TAG, "  Timeout: ${timeoutMs}ms (${timeoutMs / 1000}s)")

        val startTime = System.currentTimeMillis()

        val result = try {
          withTimeout(timeoutMs) {
            Log.i(TAG, "Calling native analyzeBuffer...")
            analyzeBufferNative(
              handle = modelHandle,
              hardwareBuffer = buffer,
              prompt = prompt,
              maxTokens = maxTokens,
              temperature = temperature,
            )
          }
        } catch (e: TimeoutCancellationException) {
          val elapsed = System.currentTimeMillis() - startTime
          Log.e(TAG, "HardwareBuffer analysis timed out after ${elapsed}ms")
          return@withContext Result.failure(
            LlamaException(
              LlamaErrorCode.INFERENCE_TIMEOUT,
              timeoutSeconds = (timeoutMs / 1000).toInt(),
            ),
          )
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "========== HardwareBuffer Analysis Complete ==========")
        Log.i(TAG, "  Time: ${elapsed}ms (${elapsed / 1000}s)")
        Log.i(TAG, "  Response length: ${result.length} chars")
        Log.i(TAG, "  Response preview: ${result.take(200)}...")

        if (result.startsWith("{\"error\":")) {
          Log.e(TAG, "Native error response: $result")
          return@withContext Result.failure(
            LlamaException(
              LlamaErrorCode.INFERENCE_FAILED,
              "HardwareBuffer analysis failed: $result",
            ),
          )
        }

        Result.success(result)
      } catch (e: Exception) {
        Log.e(TAG, "Error during HardwareBuffer analysis", e)
        Result.failure(
          LlamaException(
            LlamaErrorCode.NATIVE_ERROR,
            "Native error during HardwareBuffer analysis: ${e.message}",
            e,
          ),
        )
      }
    }
  }
}


/**
 * Configuration for loading a model.
 */
data class ModelLoadConfig(
  val modelPath: String,
  val mmprojPath: String? = null,
  val contextSize: Int = 2048,
  val numThreads: Int = 4,
  val useNnapi: Boolean = false,
)

/**
 * Loading state for the model.
 */
sealed class LoadingState {
  data object Idle : LoadingState()
  data class Loading(val message: String) : LoadingState()
  data object Loaded : LoadingState()
  data class Error(val message: String) : LoadingState()
}

/**
 * Error codes for llama.cpp operations.
 */
enum class LlamaErrorCode {
  LIBRARY_NOT_LOADED,
  LLAMA_NOT_COMPILED,
  MODEL_NOT_LOADED,
  MODEL_LOAD_FAILED,
  INFERENCE_FAILED,
  INFERENCE_TIMEOUT,
  OUT_OF_MEMORY,
  INVALID_INPUT,
  NATIVE_ERROR
}

/**
 * Exception for llama.cpp operations.
 * For INFERENCE_TIMEOUT, use the secondary constructor with timeoutSeconds.
 */
class LlamaException private constructor(
  val errorCode: LlamaErrorCode,
  override val message: String,
  val timeoutSeconds: Int?,
  override val cause: Throwable?,
) : Exception(message, cause) {

  constructor(
    errorCode: LlamaErrorCode,
    message: String,
    cause: Throwable? = null,
  ) : this(errorCode, message, null, cause)

  constructor(
    errorCode: LlamaErrorCode,
    timeoutSeconds: Int,
  ) : this(
    errorCode,
    "Inference timeout: ${timeoutSeconds}s",
    timeoutSeconds,
    null,
  )
}
