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

/**
 * Implementation of LocalInferenceEngine using llama.cpp via JNI.
 * */
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.HardwareBuffer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.fleey.futon.data.ai.AIClientException
import me.fleey.futon.data.ai.AIErrorType
import me.fleey.futon.data.ai.models.AIResponse
import me.fleey.futon.data.ai.models.ActionParameters
import me.fleey.futon.data.ai.models.ActionType
import me.fleey.futon.data.ai.models.Message
import me.fleey.futon.data.localmodel.config.LocalModelConfigRepository
import me.fleey.futon.data.localmodel.inference.LlamaCppBridge
import me.fleey.futon.data.localmodel.inference.LlamaErrorCode
import me.fleey.futon.data.localmodel.inference.LlamaException
import me.fleey.futon.data.localmodel.inference.ModelLoadConfig
import me.fleey.futon.data.localmodel.models.QuantizationType
import me.fleey.futon.data.localmodel.registry.ModelRegistry
import me.fleey.futon.domain.localmodel.models.InferenceError
import me.fleey.futon.domain.localmodel.models.InferenceState
import me.fleey.futon.domain.localmodel.models.MemoryUsage
import org.koin.core.annotation.Single
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

@Single(binds = [LocalInferenceEngine::class])
class LocalInferenceEngineImpl(
  private val context: Context,
  private val llamaCppBridge: LlamaCppBridge,
  private val modelRegistry: ModelRegistry,
  private val configRepository: LocalModelConfigRepository,
) : LocalInferenceEngine {

  companion object {
    private const val TAG = "LocalInferenceEngine"

    private const val DEFAULT_MAX_TOKENS = 512
    private const val DEFAULT_TEMPERATURE = 0.1f
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()

  private val _inferenceState = MutableStateFlow<InferenceState>(InferenceState.Idle)
  override val inferenceState: Flow<InferenceState> = _inferenceState.asStateFlow()

  private val _memoryUsage = MutableStateFlow(MemoryUsage.EMPTY)
  override val memoryUsage: Flow<MemoryUsage> = _memoryUsage.asStateFlow()

  private var backgroundUnloadJob: Job? = null

  private var loadedModelId: String? = null

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val activityManager: ActivityManager by lazy {
    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  }

  override suspend fun analyzeScreenshot(
    screenshot: String?,
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
    appContext: String?,
  ): AIResponse = mutex.withLock {
    if (screenshot.isNullOrBlank() && uiContext.isNullOrBlank()) {
      throw AIClientException(AIErrorType.NoScreenshotOrUiContext)
    }

    Log.i(TAG, "========== Local Model Analysis Started ==========")
    Log.i(TAG, "Task: $taskDescription")
    Log.i(TAG, "Screenshot provided: ${!screenshot.isNullOrBlank()}")
    Log.i(TAG, "UI context provided: ${!uiContext.isNullOrBlank()}")

    ensureModelLoaded()

    val modelId = loadedModelId ?: throw AIClientException("No model loaded")

    try {
      _inferenceState.value = InferenceState.Inferring(modelId)

      val combinedContext = if (!appContext.isNullOrBlank() && !uiContext.isNullOrBlank()) {
        "$appContext\n\n$uiContext"
      } else {
        uiContext ?: appContext
      }
      val prompt = buildGuiAgentPrompt(taskDescription, combinedContext, conversationHistory)

      val config = configRepository.getInferenceConfig()

      val imageBytes = screenshot?.let { base64Image ->
        try {
          val originalBytes = Base64.decode(base64Image, Base64.DEFAULT)
          Log.i(TAG, "Original image size: ${originalBytes.size} bytes")

          val processedBytes = downscaleImageIfNeeded(originalBytes, config.maxImageDimension)
          Log.i(TAG, "Processed image size: ${processedBytes.size} bytes")

          processedBytes
        } catch (e: Exception) {
          Log.e(TAG, "Failed to decode/process screenshot", e)
          null
        }
      }

      Log.i(TAG, "Prompt length: ${prompt.length} chars")
      Log.i(TAG, "Image size: ${imageBytes?.size ?: 0} bytes")
      Log.i(TAG, "Inference timeout: ${config.inferenceTimeoutMs}ms")

      val result = llamaCppBridge.inference(
        prompt = prompt,
        image = imageBytes,
        maxTokens = DEFAULT_MAX_TOKENS,
        temperature = DEFAULT_TEMPERATURE,
        timeoutMs = config.inferenceTimeoutMs,
      )

      updateMemoryUsage()

      result.fold(
        onSuccess = { responseText ->
          Log.i(TAG, "========== Inference Response ==========")
          Log.i(TAG, "Response: $responseText")
          val response = parseInferenceResult(responseText)
          Log.i(TAG, "Parsed action: ${response.action}")
          Log.i(TAG, "Reasoning: ${response.reasoning}")

          _inferenceState.value = InferenceState.Ready(
            modelId = modelId,
            memoryUsageMb = llamaCppBridge.getMemoryUsageMb(),
          )

          response
        },
        onFailure = { error ->
          handleInferenceError(error)
        },
      )
    } catch (e: Exception) {
      handleInferenceError(e)
    }
  }

  /**
   * Downscale image if it exceeds the maximum dimension.
   * This significantly speeds up VLM inference on mobile devices.
   */
  private fun downscaleImageIfNeeded(imageBytes: ByteArray, maxDimension: Int): ByteArray {
    try {
      // Decode bitmap to get dimensions
      val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
      BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

      val originalWidth = options.outWidth
      val originalHeight = options.outHeight

      Log.d(TAG, "Original image dimensions: ${originalWidth}x${originalHeight}")

      val maxOriginalDimension = max(originalWidth, originalHeight)
      if (maxOriginalDimension <= maxDimension) {
        Log.d(TAG, "Image within size limit, no downscaling needed")
        return imageBytes
      }

      val scaleFactor = maxDimension.toFloat() / maxOriginalDimension
      val newWidth = (originalWidth * scaleFactor).toInt()
      val newHeight = (originalHeight * scaleFactor).toInt()

      Log.i(
        TAG,
        "Downscaling image: ${originalWidth}x${originalHeight} -> ${newWidth}x${newHeight}",
      )

      val sampleSize = calculateInSampleSize(originalWidth, originalHeight, newWidth, newHeight)
      val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
      }

      val sampledBitmap =
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
          ?: return imageBytes

      // Scale to exact dimensions
      val scaledBitmap = Bitmap.createScaledBitmap(sampledBitmap, newWidth, newHeight, true)

      // Recycle sampled bitmap if different from scaled
      if (sampledBitmap != scaledBitmap) {
        sampledBitmap.recycle()
      }

      val outputStream = ByteArrayOutputStream()
      scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
      scaledBitmap.recycle()

      val result = outputStream.toByteArray()
      Log.i(
        TAG,
        "Downscaled image size: ${result.size} bytes (${result.size * 100 / imageBytes.size}% of original)",
      )

      return result
    } catch (e: Exception) {
      Log.e(TAG, "Failed to downscale image, using original", e)
      return imageBytes
    }
  }

  /**
   * Calculate optimal inSampleSize for BitmapFactory.
   */
  private fun calculateInSampleSize(
    originalWidth: Int,
    originalHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
  ): Int {
    var inSampleSize = 1

    if (originalHeight > targetHeight || originalWidth > targetWidth) {
      val halfHeight = originalHeight / 2
      val halfWidth = originalWidth / 2

      while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
        inSampleSize *= 2
      }
    }

    return inSampleSize
  }


  override fun isModelLoaded(): Boolean = llamaCppBridge.isLoaded()

  /**
   * Load the currently active model.

   */
  override suspend fun loadModel(): Result<Unit> = mutex.withLock {
    loadModelInternal()
  }

  /**
   * Unload the current model from memory.

   */
  override suspend fun unloadModel(): Result<Unit> = mutex.withLock {
    return try {
      Log.i(TAG, "Unloading model")
      llamaCppBridge.unload()
      loadedModelId = null
      _inferenceState.value = InferenceState.Idle
      _memoryUsage.value = MemoryUsage.EMPTY
      Result.success(Unit)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to unload model", e)
      Result.failure(e)
    }
  }

  /**
   * Handle app moving to background.

   */
  override suspend fun onAppBackground() {
    if (!isModelLoaded()) return

    val config = configRepository.getInferenceConfig()
    val timeoutMs = config.backgroundUnloadTimeoutMs

    Log.i(TAG, "App moved to background, scheduling unload in ${timeoutMs}ms")

    // Cancel any existing job
    backgroundUnloadJob?.cancel()

    // Schedule unload after timeout
    backgroundUnloadJob = scope.launch {
      delay(timeoutMs)
      Log.i(TAG, "Background timeout reached, unloading model")
      unloadModel()
    }
  }

  /**
   * Handle app returning to foreground.

   */
  override suspend fun onAppForeground() {
    // Cancel any pending unload
    backgroundUnloadJob?.cancel()
    backgroundUnloadJob = null

    // Check if we need to reload the model
    val activeModelId = configRepository.getActiveModelId()

    if (activeModelId != null && !isModelLoaded()) {
      Log.i(TAG, "App returned to foreground, reloading model: $activeModelId")
      loadModel()
    }
  }

  override fun getCurrentMemoryUsage(): MemoryUsage? {
    if (!isModelLoaded()) return null
    return _memoryUsage.value.takeIf { it.modelMemoryBytes > 0 }
  }

  /**
   * Analyze a HardwareBuffer directly without copying to byte array.
   * This enables zero-copy transfer from Daemon via Binder IPC.
   */
  override suspend fun analyzeBuffer(
    buffer: HardwareBuffer,
    taskDescription: String,
    uiContext: String?,
  ): AIResponse = mutex.withLock {
    Log.i(TAG, "========== HardwareBuffer Analysis Started ==========")
    Log.i(TAG, "Task: $taskDescription")
    Log.i(TAG, "Buffer: ${buffer.width}x${buffer.height}, format=${buffer.format}")
    Log.i(TAG, "UI context provided: ${!uiContext.isNullOrBlank()}")

    ensureModelLoaded()

    val modelId = loadedModelId ?: throw AIClientException("No model loaded")

    try {
      _inferenceState.value = InferenceState.Inferring(modelId)

      val prompt = buildGuiAgentPrompt(taskDescription, uiContext, emptyList())

      val config = configRepository.getInferenceConfig()

      Log.i(TAG, "Prompt length: ${prompt.length} chars")
      Log.i(TAG, "Inference timeout: ${config.inferenceTimeoutMs}ms")

      val result = llamaCppBridge.analyzeBuffer(
        buffer = buffer,
        prompt = prompt,
        maxTokens = DEFAULT_MAX_TOKENS,
        temperature = DEFAULT_TEMPERATURE,
        timeoutMs = config.inferenceTimeoutMs,
      )

      updateMemoryUsage()

      result.fold(
        onSuccess = { responseText ->
          Log.i(TAG, "========== HardwareBuffer Analysis Response ==========")
          Log.i(TAG, "Response: $responseText")
          val response = parseInferenceResult(responseText)
          Log.i(TAG, "Parsed action: ${response.action}")
          Log.i(TAG, "Reasoning: ${response.reasoning}")

          _inferenceState.value = InferenceState.Ready(
            modelId = modelId,
            memoryUsageMb = llamaCppBridge.getMemoryUsageMb(),
          )

          response
        },
        onFailure = { error ->
          handleInferenceError(error)
        },
      )
    } catch (e: Exception) {
      handleInferenceError(e)
    }
  }

  /**
   * Ensure model is loaded, loading it if necessary.
   */
  private suspend fun ensureModelLoaded() {
    if (isModelLoaded()) return

    loadModelInternal().getOrElse { error ->
      throw AIClientException("Failed to load model: ${error.message}", error)
    }
  }

  private suspend fun loadModelInternal(): Result<Unit> {
    val activeModelId = configRepository.getActiveModelId()
      ?: return Result.failure(
        AIClientException("No active model configured").also {
          _inferenceState.value = InferenceState.Error(InferenceError.NoActiveModel)
        },
      )

    val downloadedModels = modelRegistry.getDownloadedModels().first()
    val downloadedModel = downloadedModels.find { it.modelId == activeModelId }
      ?: return Result.failure(
        AIClientException("Model not found: $activeModelId").also {
          _inferenceState.value = InferenceState.Error(
            InferenceError.ModelNotFound(activeModelId, "Model files not found"),
          )
        },
      )

    val modelFile = File(downloadedModel.mainModelPath)
    if (!modelFile.exists()) {
      return Result.failure(
        AIClientException("Model file not found: ${downloadedModel.mainModelPath}").also {
          _inferenceState.value = InferenceState.Error(
            InferenceError.ModelNotFound(activeModelId, "Model file missing"),
          )
        },
      )
    }

    // Get inference config
    val inferenceConfig = configRepository.getInferenceConfig()

    // Update state to loading
    _inferenceState.value = InferenceState.Loading("Loading model...")

    Log.i(TAG, "Loading model: $activeModelId")
    Log.i(TAG, "  Path: ${downloadedModel.mainModelPath}")
    Log.i(TAG, "  mmproj: ${downloadedModel.mmprojPath ?: "(none)"}")
    Log.i(
      TAG,
      "  Context: ${inferenceConfig.contextLength}, Threads: ${inferenceConfig.numThreads}",
    )

    // Create load config
    val loadConfig = ModelLoadConfig(
      modelPath = downloadedModel.mainModelPath,
      mmprojPath = downloadedModel.mmprojPath,
      contextSize = inferenceConfig.contextLength,
      numThreads = inferenceConfig.numThreads,
      useNnapi = inferenceConfig.useNnapi,
    )

    // Load the model
    return llamaCppBridge.loadModel(loadConfig).fold(
      onSuccess = {
        loadedModelId = activeModelId
        updateMemoryUsage()

        _inferenceState.value = InferenceState.Ready(
          modelId = activeModelId,
          memoryUsageMb = llamaCppBridge.getMemoryUsageMb(),
        )

        Log.i(TAG, "Model loaded successfully")
        Result.success(Unit)
      },
      onFailure = { error ->
        Log.e(TAG, "Failed to load model", error)

        val inferenceError = when {
          error is LlamaException && error.errorCode == LlamaErrorCode.OUT_OF_MEMORY -> {
            // Suggest smaller quantization on OOM
            val suggestedQuant = getSmallerQuantization(downloadedModel.quantization)
            InferenceError.OutOfMemory(suggestedQuant)
          }

          error is LlamaException -> {
            InferenceError.NativeError(error.errorCode.ordinal, error.message)
          }

          else -> {
            InferenceError.LoadFailed(error.message ?: "Unknown error")
          }
        }

        _inferenceState.value = InferenceState.Error(inferenceError)
        Result.failure(AIClientException(inferenceError.toString(), error))
      },
    )
  }

  private fun buildGuiAgentPrompt(
    taskDescription: String,
    uiContext: String?,
    conversationHistory: List<Message>,
  ): String {
    return buildString {
      // System message
      append("<|im_start|>system\n")
      append(GUI_AGENT_SYSTEM_PROMPT.trim())
      append("<|im_end|>\n")

      // Conversation history
      conversationHistory.forEach { message ->
        val role = if (message.role.lowercase() == "assistant") "assistant" else "user"
        append("<|im_start|>$role\n")
        append(
          message.content.joinToString {
            when (it) {
              is me.fleey.futon.data.ai.models.ContentPart.Text -> it.text
              is me.fleey.futon.data.ai.models.ContentPart.ImageUrl -> "[image]"
            }
          },
        )
        append("<|im_end|>\n")
      }

      // Current user message with task and UI context
      append("<|im_start|>user\n")
      append("Task: $taskDescription")
      if (!uiContext.isNullOrBlank()) {
        append("\n\n")
        append(uiContext)
      }
      append("<|im_end|>\n")

      // Start assistant response
      append("<|im_start|>assistant\n")
    }
  }


  /**
   * Parse the inference result into an AIResponse.
   */
  private fun parseInferenceResult(result: String): AIResponse {
    val jsonString = extractJson(result)

    if (jsonString != null) {
      try {
        return json.decodeFromString<AIResponse>(jsonString)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse JSON response, trying manual parsing", e)
      }
    }

    // Fall back to manual parsing
    return parseManually(result)
  }

  /**
   * Extract JSON object from response text.
   */
  private fun extractJson(text: String): String? {
    val startIndex = text.indexOf('{')
    if (startIndex == -1) return null

    var depth = 0
    var endIndex = -1

    for (i in startIndex until text.length) {
      when (text[i]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) {
            endIndex = i
            break
          }
        }
      }
    }

    return if (endIndex > startIndex) {
      text.substring(startIndex, endIndex + 1)
    } else null
  }

  /**
   * Manually parse response when JSON parsing fails.
   */
  private fun parseManually(text: String): AIResponse {
    val lowerText = text.lowercase()

    // Detect action type
    val action = when {
      lowerText.contains("\"action\"") && lowerText.contains("\"tap\"") -> ActionType.TAP
      lowerText.contains("\"action\"") && lowerText.contains("\"swipe\"") -> ActionType.SWIPE
      lowerText.contains("\"action\"") && lowerText.contains("\"input\"") -> ActionType.INPUT
      lowerText.contains("\"action\"") && lowerText.contains("\"wait\"") -> ActionType.WAIT
      lowerText.contains("\"action\"") && lowerText.contains("\"complete\"") -> ActionType.COMPLETE
      lowerText.contains("tap") || lowerText.contains("click") -> ActionType.TAP
      lowerText.contains("swipe") || lowerText.contains("scroll") -> ActionType.SWIPE
      lowerText.contains("type") || lowerText.contains("input") -> ActionType.INPUT
      lowerText.contains("wait") -> ActionType.WAIT
      lowerText.contains("complete") || lowerText.contains("done") || lowerText.contains("finished") -> ActionType.COMPLETE
      else -> ActionType.ERROR
    }

    val coordinates = extractCoordinates(text)

    val inputText = if (action == ActionType.INPUT) {
      extractInputText(text)
    } else null

    val duration = if (action == ActionType.WAIT) {
      extractDuration(text)
    } else null

    val reasoning = extractReasoning(text)

    // Build parameters based on action type
    val parameters = when (action) {
      ActionType.TAP, ActionType.TAP_COORDINATE -> ActionParameters(
        x = coordinates?.first,
        y = coordinates?.second,
      )

      ActionType.LONG_PRESS -> ActionParameters(
        x = coordinates?.first,
        y = coordinates?.second,
        duration = extractDuration(text) ?: 500,
      )

      ActionType.DOUBLE_TAP -> ActionParameters(
        x = coordinates?.first,
        y = coordinates?.second,
      )

      ActionType.SWIPE -> ActionParameters(
        x1 = coordinates?.first,
        y1 = coordinates?.second,
        x2 = extractEndCoordinates(text)?.first,
        y2 = extractEndCoordinates(text)?.second,
      )

      ActionType.SCROLL -> ActionParameters(
        x = coordinates?.first,
        y = coordinates?.second,
        direction = extractDirection(text),
        distance = extractDistance(text),
      )

      ActionType.PINCH -> ActionParameters(
        x = coordinates?.first,
        y = coordinates?.second,
        startDistance = extractStartDistance(text),
        endDistance = extractEndDistance(text),
      )

      ActionType.INPUT -> ActionParameters(
        text = inputText,
        x = coordinates?.first,
        y = coordinates?.second,
      )

      ActionType.WAIT -> ActionParameters(
        duration = duration ?: 1000,
      )

      ActionType.LAUNCH_APP -> ActionParameters(
        text = inputText,
      )

      ActionType.LAUNCH_ACTIVITY -> ActionParameters(
        packageName = extractPackageName(text),
        activity = extractActivityName(text),
      )

      ActionType.BACK,
      ActionType.HOME,
      ActionType.RECENTS,
      ActionType.NOTIFICATIONS,
      ActionType.QUICK_SETTINGS,
        -> ActionParameters()

      ActionType.SCREENSHOT -> ActionParameters(
        path = extractFilePath(text),
      )

      ActionType.INTERVENE -> ActionParameters(
        reason = reasoning ?: "User intervention required",
        hint = extractHint(text),
      )

      ActionType.CALL -> ActionParameters(
        command = extractCommand(text),
        args = extractArgs(text),
      )

      ActionType.COMPLETE -> ActionParameters(
        message = reasoning ?: "Task completed",
      )

      ActionType.ERROR -> ActionParameters(
        message = reasoning ?: "Failed to parse response",
      )
    }

    return AIResponse(
      action = action,
      parameters = parameters,
      reasoning = reasoning,
      taskComplete = action == ActionType.COMPLETE,
    )
  }

  /**
   * Extract coordinates from text using regex patterns.
   */
  private fun extractCoordinates(text: String): Pair<Int, Int>? {
    // Pattern: "x": 123, "y": 456 or x=123, y=456 or (123, 456)
    val patterns = listOf(
      Regex(""""x"\s*:\s*(\d+)\s*,\s*"y"\s*:\s*(\d+)"""),
      Regex("""x\s*[=:]\s*(\d+)\s*,?\s*y\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE),
      Regex("""\(\s*(\d+)\s*,\s*(\d+)\s*\)"""),
      Regex("""coordinates?\s*[=:]\s*\[?\s*(\d+)\s*,\s*(\d+)""", RegexOption.IGNORE_CASE),
    )

    for (pattern in patterns) {
      pattern.find(text)?.let { match ->
        val x = match.groupValues[1].toIntOrNull()
        val y = match.groupValues[2].toIntOrNull()
        if (x != null && y != null) {
          return Pair(x, y)
        }
      }
    }

    return null
  }

  /**
   * Extract end coordinates for swipe action.
   */
  private fun extractEndCoordinates(text: String): Pair<Int, Int>? {
    // Pattern: "x2": 123, "y2": 456 or end_x=123, end_y=456
    val patterns = listOf(
      Regex(""""x2"\s*:\s*(\d+)\s*,\s*"y2"\s*:\s*(\d+)"""),
      Regex("""end_?x\s*[=:]\s*(\d+)\s*,?\s*end_?y\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE),
      Regex("""to\s*[=:]\s*\[?\s*(\d+)\s*,\s*(\d+)""", RegexOption.IGNORE_CASE),
    )

    for (pattern in patterns) {
      pattern.find(text)?.let { match ->
        val x = match.groupValues[1].toIntOrNull()
        val y = match.groupValues[2].toIntOrNull()
        if (x != null && y != null) {
          return Pair(x, y)
        }
      }
    }

    return null
  }

  /**
   * Extract input text from response.
   */
  private fun extractInputText(text: String): String? {
    val patterns = listOf(
      Regex(""""text"\s*:\s*"([^"]+)""""),
      Regex("""input\s*[=:]\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
      Regex("""type\s*[=:]\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    )

    for (pattern in patterns) {
      pattern.find(text)?.let { match ->
        return match.groupValues[1]
      }
    }

    return null
  }

  /**
   * Extract duration from response.
   */
  private fun extractDuration(text: String): Int? {
    val patterns = listOf(
      Regex(""""duration"\s*:\s*(\d+)"""),
      Regex("""wait\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE),
      Regex("""(\d+)\s*(?:ms|milliseconds?)""", RegexOption.IGNORE_CASE),
    )

    for (pattern in patterns) {
      pattern.find(text)?.let { match ->
        return match.groupValues[1].toIntOrNull()
      }
    }

    return null
  }

  /**
   * Extract reasoning from response.
   */
  private fun extractReasoning(text: String): String? {
    val patterns = listOf(
      Regex(""""reasoning"\s*:\s*"([^"]+)""""),
      Regex(""""reason"\s*:\s*"([^"]+)""""),
      Regex(""""message"\s*:\s*"([^"]+)""""),
      Regex(""""explanation"\s*:\s*"([^"]+)""""),
    )

    for (pattern in patterns) {
      pattern.find(text)?.let { match ->
        return match.groupValues[1]
      }
    }

    return null
  }

  private fun extractDirection(text: String): String? {
    val pattern = Regex(""""direction"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractDistance(text: String): Int? {
    val pattern = Regex(""""distance"\s*:\s*(\d+)""")
    return pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun extractStartDistance(text: String): Int? {
    val pattern = Regex(""""start_distance"\s*:\s*(\d+)""")
    return pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun extractEndDistance(text: String): Int? {
    val pattern = Regex(""""end_distance"\s*:\s*(\d+)""")
    return pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }

  private fun extractPackageName(text: String): String? {
    val pattern = Regex(""""package"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractActivityName(text: String): String? {
    val pattern = Regex(""""activity"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractFilePath(text: String): String? {
    val pattern = Regex(""""path"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractHint(text: String): String? {
    val pattern = Regex(""""hint"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractCommand(text: String): String? {
    val pattern = Regex(""""command"\s*:\s*"([^"]+)"""")
    return pattern.find(text)?.groupValues?.get(1)
  }

  private fun extractArgs(text: String): Map<String, String>? {
    val pattern = Regex(""""args"\s*:\s*\{([^}]*)\}""")
    val match = pattern.find(text) ?: return null
    val argsContent = match.groupValues[1]

    val result = mutableMapOf<String, String>()
    val keyValuePattern = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
    keyValuePattern.findAll(argsContent).forEach { kvMatch ->
      result[kvMatch.groupValues[1]] = kvMatch.groupValues[2]
    }
    return result.ifEmpty { null }
  }

  private fun handleInferenceError(error: Throwable): Nothing {
    Log.e(TAG, "Inference error", error)

    val inferenceError = when {
      error is LlamaException && error.errorCode == LlamaErrorCode.OUT_OF_MEMORY -> {
        val currentQuant = modelRegistry.getDownloadedModels()
          .let { flow ->
            // Can't use suspend here, so we'll suggest INT4 as default
            InferenceError.OutOfMemory(QuantizationType.INT4)
          }
        InferenceError.OutOfMemory(QuantizationType.INT4)
      }

      error is LlamaException -> {
        InferenceError.NativeError(error.errorCode.ordinal, error.message)
      }

      error is AIClientException -> {
        InferenceError.InferenceFailed(error.message ?: "Unknown error")
      }

      else -> {
        InferenceError.InferenceFailed(error.message ?: "Unknown error")
      }
    }

    _inferenceState.value = InferenceState.Error(inferenceError)
    throw AIClientException(inferenceError.toString(), error)
  }

  private fun getSmallerQuantization(current: QuantizationType): QuantizationType? {
    return when (current) {
      QuantizationType.FP16 -> QuantizationType.INT8
      QuantizationType.INT8 -> QuantizationType.INT4
      QuantizationType.INT4 -> null
    }
  }

  private fun updateMemoryUsage() {
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)

    _memoryUsage.value = MemoryUsage(
      modelMemoryBytes = llamaCppBridge.getMemoryUsageBytes(),
      totalDeviceRamBytes = memInfo.totalMem,
      availableDeviceRamBytes = memInfo.availMem,
    )
  }
}

// Constants

/**
 * System prompt for local GUI agent models.
 * Optimized for on-device VLM inference with UI structure context.
 */
private const val GUI_AGENT_SYSTEM_PROMPT =
  """You are an Android GUI automation agent. Analyze UI and execute actions.

## Output Format
<think>{brief reasoning}</think><answer>{action_json}</answer>

## Input
- Task: User's goal
- UI Structure: Elements with format: ClassName text="..." [clickable] @[x,y]
- Screenshot: Visual reference

## Actions
| Action | Parameters |
|--------|------------|
| tap | {"x":int,"y":int} |
| long_press | {"x":int,"y":int,"duration":int} |
| double_tap | {"x":int,"y":int} |
| swipe | {"x1":int,"y1":int,"x2":int,"y2":int} |
| scroll | {"x":int,"y":int,"direction":"up/down/left/right","distance":int} |
| input | {"text":"string"} |
| launch_app | {"text":"package_or_name"} - USE FIRST for opening apps! |
| back | {} |
| home | {} |
| wait | {"duration":int} |
| complete | {"message":"result"} |
| error | {"message":"reason"} |

## Rules
1. Use launch_app FIRST for opening any app
2. Use @[x,y] coordinates from UI structure
3. Prefer [clickable] elements
4. Match task with element text
5. Set taskComplete=true only when done"""
