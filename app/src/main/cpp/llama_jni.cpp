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

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <cstring>
#include <algorithm>
#include <chrono>

// Logging macros
#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Check if llama.cpp is available
#if __has_include("llama.cpp/include/llama.h")
#define LLAMA_AVAILABLE 1

#include "llama.cpp/include/llama.h"
#include "llama.cpp/ggml/include/ggml.h"

// Check for clip support (vision-language models)
#if __has_include("llama.cpp/examples/llava/clip.h")
#define CLIP_AVAILABLE 1
#include "llama.cpp/examples/llava/clip.h"
#include "llama.cpp/examples/llava/llava.h"
#else
#define CLIP_AVAILABLE 0
#endif

#else
#define LLAMA_AVAILABLE 0
#define CLIP_AVAILABLE 0
#endif

#if LLAMA_AVAILABLE

static inline void batch_clear(llama_batch &batch) {
    batch.n_tokens = 0;
}

// Add a token to the batch
static inline void batch_add(
        llama_batch &batch,
        llama_token id,
        llama_pos pos,
        const std::vector<llama_seq_id> &seq_ids,
        bool logits
) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits[batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

#endif

struct ModelContext {
    long id{};
    std::string modelPath;
    std::string mmprojPath;
    int contextSize{};
    int numThreads{};
    bool useNnapi{};

#if LLAMA_AVAILABLE
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
#endif

#if CLIP_AVAILABLE
    clip_ctx* clipCtx = nullptr;
#endif

    bool isVLM = false;
    size_t memoryUsage = 0;

    ~ModelContext() {
        unload();
    }

    void unload() {
#if LLAMA_AVAILABLE
        if (sampler) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
#endif
#if CLIP_AVAILABLE
        if (clipCtx) {
            clip_free(clipCtx);
            clipCtx = nullptr;
        }
#endif
        memoryUsage = 0;
    }
};

static std::mutex g_mutex;
static std::unordered_map<long, std::unique_ptr<ModelContext>> g_models;
static long g_nextModelId = 1;

// Helper to get model context safely
static ModelContext *getModelContext(long handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it != g_models.end()) {
        return it->second.get();
    }
    return nullptr;
}

// Helper to convert jstring to std::string
static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) {
        return "";
    }
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Helper to convert byte array to vector
static std::vector<uint8_t> jbyteArrayToVector(JNIEnv *env, jbyteArray arr) {
    if (arr == nullptr) {
        return {};
    }
    jsize len = env->GetArrayLength(arr);
    std::vector<uint8_t> result(len);
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(result.data()));
    return result;
}


// JNI Functions

extern "C" {

/**
 * Load a GGUF model into memory.
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param modelPath Path to the GGUF model file
 * @param mmprojPath Path to the mmproj file (nullable for text-only models)
 * @param contextSize Context window size in tokens
 * @param numThreads Number of threads for inference
 * @param useNnapi Whether to use Android NNAPI acceleration
 * @return Model handle (>0 on success, 0 on failure)
 */
JNIEXPORT jlong JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_loadModelNative(
        JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jstring modelPath,
        jstring mmprojPath,
        jint contextSize,
        jint numThreads,
        [[maybe_unused]] jboolean useNnapi
) {
#if !LLAMA_AVAILABLE
    LOGE("llama.cpp not available - library not compiled with llama.cpp support");
    return 0;
#else
    std::string modelPathStr = jstringToString(env, modelPath);
    std::string mmprojPathStr = jstringToString(env, mmprojPath);

    LOGI("Loading model: %s", modelPathStr.c_str());
    LOGI("  mmproj: %s", mmprojPathStr.empty() ? "(none)" : mmprojPathStr.c_str());
    LOGI("  contextSize: %d, threads: %d, nnapi: %d", contextSize, numThreads, useNnapi);

    // Create model context
    auto modelCtx = std::make_unique<ModelContext>();
    modelCtx->modelPath = modelPathStr;
    modelCtx->mmprojPath = mmprojPathStr;
    modelCtx->contextSize = contextSize;
    modelCtx->numThreads = numThreads;
    modelCtx->useNnapi = useNnapi;
    modelCtx->isVLM = !mmprojPathStr.empty();

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // CPU only for Android
    modelParams.use_mmap = true;
    modelParams.use_mlock = false;

    // Load the model using new API
    modelCtx->model = llama_model_load_from_file(modelPathStr.c_str(), modelParams);
    if (!modelCtx->model) {
        LOGE("Failed to load model from: %s", modelPathStr.c_str());
        return 0;
    }

    LOGI("Model loaded successfully");

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = contextSize;
    ctxParams.n_threads = numThreads;
    ctxParams.n_threads_batch = numThreads;
    ctxParams.n_batch = 128; // Reduced from 512 for better mobile performance
    ctxParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    modelCtx->ctx = llama_init_from_model(modelCtx->model, ctxParams);
    if (!modelCtx->ctx) {
        LOGE("Failed to create context");
        llama_model_free(modelCtx->model);
        modelCtx->model = nullptr;
        return 0;
    }

    LOGI("Context created successfully");

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    modelCtx->sampler = llama_sampler_chain_init(samplerParams);

    // Add samplers: temperature -> top-k -> top-p -> greedy
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

#if CLIP_AVAILABLE
    // Load clip model for vision-language models
    if (!mmprojPathStr.empty()) {
        LOGI("Loading CLIP model for VLM support: %s", mmprojPathStr.c_str());
        modelCtx->clipCtx = clip_model_load(mmprojPathStr.c_str(), 1);
        if (!modelCtx->clipCtx) {
            LOGW("Failed to load CLIP model - image processing will not be available");
        } else {
            LOGI("CLIP model loaded successfully");
        }
    }
#else
    if (!mmprojPathStr.empty()) {
        LOGW("CLIP support not compiled - VLM image processing will not be available");
    }
#endif

    modelCtx->memoryUsage = llama_state_get_size(modelCtx->ctx);
    LOGI("Estimated memory usage: %zu bytes", modelCtx->memoryUsage);

    std::lock_guard<std::mutex> lock(g_mutex);
    long handle = g_nextModelId++;
    modelCtx->id = handle;
    g_models[handle] = std::move(modelCtx);

    LOGI("Model loaded with handle: %ld", handle);
    return handle;
#endif
}

/**
 * Unload a model from memory.
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param handle Model handle returned by loadModel
 */
JNIEXPORT void JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_unloadModelNative(
        [[maybe_unused]] JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jlong handle
) {
    LOGI("Unloading model with handle: %ld", (long) handle);

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it != g_models.end()) {
        g_models.erase(it);
        LOGI("Model unloaded successfully");
    } else {
        LOGW("Model handle not found: %ld", (long) handle);
    }
}


/**
 * Run inference on a loaded model.
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param handle Model handle
 * @param prompt Text prompt for the model
 * @param imageBytes Image data as byte array (nullable for text-only inference)
 * @param maxTokens Maximum tokens to generate
 * @param temperature Sampling temperature
 * @return Generated text response (JSON format)
 */
JNIEXPORT jstring JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_inferenceNative(
        JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jlong handle,
        jstring prompt,
        jbyteArray imageBytes,
        jint maxTokens,
        jfloat temperature
) {
#if !LLAMA_AVAILABLE
    LOGE("llama.cpp not available");
    return env->NewStringUTF("{\"error\": \"llama.cpp not compiled\", \"code\": -1}");
#else
    ModelContext *modelCtx = getModelContext(handle);
    if (!modelCtx) {
        LOGE("Invalid model handle: %ld", (long) handle);
        return env->NewStringUTF(R"({"error": "Invalid model handle", "code": -2})");
    }

    if (!modelCtx->model || !modelCtx->ctx) {
        LOGE("Model not properly loaded");
        return env->NewStringUTF(R"({"error": "Model not loaded", "code": -3})");
    }

    std::string promptStr = jstringToString(env, prompt);
    std::vector<uint8_t> imageData = jbyteArrayToVector(env, imageBytes);

    LOGI("========== Native Inference Started ==========");
    LOGI("  Prompt length: %zu chars", promptStr.length());
    LOGI("  Image size: %zu bytes", imageData.size());
    LOGI("  Max tokens: %d", maxTokens);
    LOGI("  Temperature: %.2f", temperature);

    // Update sampler temperature if different
    // NOTE: For simplicity, we recreate the sampler chain with new temperature
    if (modelCtx->sampler) {
        llama_sampler_free(modelCtx->sampler);
    }
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    modelCtx->sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Sampler chain created");

    llama_memory_t mem = llama_get_memory(modelCtx->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
        LOGI("Context memory cleared");
    }

    const std::string &fullPrompt = promptStr;

#if CLIP_AVAILABLE
    // Process image for VLM if available
    if (!imageData.empty() && modelCtx->clipCtx) {
        LOGI("Processing image for VLM inference...");

        // Load image from memory
        clip_image_u8* img = clip_image_u8_init();
        if (clip_image_load_from_bytes(imageData.data(), imageData.size(), img)) {
            LOGI("Image loaded from bytes successfully");

            // Preprocess image
            clip_image_f32* imgProcessed = clip_image_f32_init();
            if (clip_image_preprocess(modelCtx->clipCtx, img, imgProcessed)) {
                LOGI("Image preprocessed successfully");

                // Get image embeddings
                float* imageEmbed = nullptr;
                int embedSize = 0;

                if (llava_image_embed_make_with_clip_img(
                    modelCtx->clipCtx,
                    modelCtx->numThreads,
                    imgProcessed,
                    &imageEmbed,
                    &embedSize
                )) {
                    LOGI("Image embedding created, size: %d", embedSize);
                    // Image embeddings will be used in the prompt processing
                    // For now, we add a placeholder that the model understands
                    fullPrompt = "<image>\n" + promptStr;
                    free(imageEmbed);
                } else {
                    LOGW("Failed to create image embeddings");
                }
            } else {
                LOGW("Failed to preprocess image");
            }
            clip_image_f32_free(imgProcessed);
        } else {
            LOGW("Failed to load image from bytes");
        }
        clip_image_u8_free(img);
    } else if (!imageData.empty() && !modelCtx->clipCtx) {
        LOGW("Image provided but CLIP model not loaded - ignoring image");
    }
#else
    if (!imageData.empty()) {
        LOGW("Image provided but CLIP support not compiled - ignoring image");
    }
#endif

    const llama_vocab *vocab = llama_model_get_vocab(modelCtx->model);
    if (!vocab) {
        LOGE("Failed to get vocab from model");
        return env->NewStringUTF(R"({"error": "Failed to get vocab", "code": -4})");
    }

    LOGI("Tokenizing prompt...");

    const int n_ctx = llama_n_ctx(modelCtx->ctx);
    std::vector<llama_token> tokens(n_ctx);

    int n_tokens = llama_tokenize(
            vocab,
            fullPrompt.c_str(),
            static_cast<int32_t>(fullPrompt.length()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,    // add_special (BOS)
            true   // parse_special
    );

    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF(R"({"error": "Tokenization failed", "code": -5})");
    }

    tokens.resize(n_tokens);
    LOGI("Tokenized prompt: %d tokens (context size: %d)", n_tokens, n_ctx);

    if (n_tokens > n_ctx - 4) {
        LOGE("Prompt too long: %d tokens (max: %d)", n_tokens, n_ctx - 4);
        return env->NewStringUTF(R"({"error": "Prompt too long", "code": -6})");
    }

    const int n_batch = 128; // Reduced from 512 for better mobile performance
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    // Verify batch was allocated properly
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        LOGE("Failed to allocate batch");
        return env->NewStringUTF(R"({"error": "Batch allocation failed", "code": -8})");
    }

    LOGI("Processing prompt in batches of %d tokens...", n_batch);
    LOGI("Total batches to process: %d", (n_tokens + n_batch - 1) / n_batch);

    // Track timing for performance monitoring
    auto start_time = std::chrono::high_resolution_clock::now();

    int batch_num = 0;
    for (int i = 0; i < n_tokens; i += n_batch) {
        batch_clear(batch);

        int batch_end = std::min(i + n_batch, n_tokens);
        for (int j = i; j < batch_end; j++) {
            // Only request logits for the last token of the last batch
            bool is_last_token = (j == n_tokens - 1);
            batch_add(batch, tokens[j], j, {0}, is_last_token);
        }

        batch_num++;
        LOGI("Decoding batch %d: tokens %d-%d (%d tokens)", batch_num, i, batch_end - 1,
             batch_end - i);

        auto batch_start = std::chrono::high_resolution_clock::now();

        if (llama_decode(modelCtx->ctx, batch) != 0) {
            llama_batch_free(batch);
            LOGE("Failed to decode batch at position %d", i);
            return env->NewStringUTF(R"({"error": "Decode failed", "code": -7})");
        }

        auto batch_end_time = std::chrono::high_resolution_clock::now();
        auto batch_duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                batch_end_time - batch_start).count();
        LOGI("Batch %d completed in %lld ms (%.1f tokens/sec)", batch_num, batch_duration,
             batch_duration > 0 ? (batch_end - i) * 1000.0 / batch_duration : 0);
    }

    auto prompt_end_time = std::chrono::high_resolution_clock::now();
    auto prompt_duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            prompt_end_time - start_time).count();
    LOGI("Prompt processing complete in %lld ms (%.1f tokens/sec)", prompt_duration,
         prompt_duration > 0 ? n_tokens * 1000.0 / prompt_duration : 0);

    LOGI("Prompt processing complete, generating response tokens (max: %d)...", maxTokens);

    std::string response;
    int n_cur = n_tokens;
    int n_generated = 0;

    while (n_generated < maxTokens) {

        llama_token new_token = llama_sampler_sample(modelCtx->sampler, modelCtx->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation token received at token %d", n_generated);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);
        }

        batch_clear(batch);
        batch_add(batch, new_token, n_cur, {0}, true);

        if (llama_decode(modelCtx->ctx, batch) != 0) {
            LOGE("Failed to decode token at position %d", n_cur);
            break;
        }

        n_cur++;
        n_generated++;

        // Log progress every 50 tokens
        if (n_generated % 50 == 0) {
            LOGI("Generated %d tokens...", n_generated);
        }
    }

    llama_batch_free(batch);

    LOGI("========== Native Inference Complete ==========");
    LOGI("  Tokens generated: %d", n_generated);
    LOGI("  Response length: %zu chars", response.length());

    return env->NewStringUTF(response.c_str());
#endif
}

/**
 * Get current memory usage of a loaded model.
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param handle Model handle
 * @return Memory usage in bytes
 */
JNIEXPORT jlong JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_getMemoryUsageNative(
        [[maybe_unused]] JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jlong handle
) {
#if !LLAMA_AVAILABLE
    return 0;
#else
    ModelContext *modelCtx = getModelContext(handle);
    if (!modelCtx || !modelCtx->ctx) {
        return 0;
    }

    size_t stateSize = llama_state_get_size(modelCtx->ctx);
    return static_cast<jlong>(stateSize);
#endif
}

/**
 * Get the version of llama.cpp being used.
 *
 * @param env JNI environment
 * @param clazz Java class reference
 * @return Version string or "not available"
 */
JNIEXPORT jstring JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_getVersion(
        JNIEnv *env,
        [[maybe_unused]] jclass clazz
) {
#if LLAMA_AVAILABLE
    // llama.cpp doesn't have a version function, so we return build info
    return env->NewStringUTF("llama.cpp (compiled)");
#else
    return env->NewStringUTF("not available");
#endif
}

/**
 * Check if llama.cpp is available.
 *
 * @param env JNI environment
 * @param clazz Java class reference
 * @return true if llama.cpp is compiled and available
 */
JNIEXPORT jboolean JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_isAvailable(
        [[maybe_unused]] JNIEnv *env,
        [[maybe_unused]] jclass clazz
) {
#if LLAMA_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Check if CLIP/VLM support is available.
 *
 * @param env JNI environment
 * @param clazz Java class reference
 * @return true if CLIP support is compiled
 */
JNIEXPORT jboolean JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_isClipAvailableNative(
        [[maybe_unused]] JNIEnv *env,
        [[maybe_unused]] jclass clazz
) {
#if CLIP_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Analyze a HardwareBuffer directly without copying to byte array.
 * This enables zero-copy transfer from Daemon via Binder IPC.
 *
 * @param env JNI environment
 * @param thiz Java object reference
 * @param handle Model handle
 * @param hardwareBuffer The HardwareBuffer containing the screenshot
 * @param prompt Text prompt for the model
 * @param maxTokens Maximum tokens to generate
 * @param temperature Sampling temperature
 * @return Generated text response (JSON format)
 */
JNIEXPORT jstring JNICALL
Java_me_fleey_futon_data_localmodel_inference_LlamaCppBridge_analyzeBufferNative(
        JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jlong handle,
        jobject hardwareBuffer,
        jstring prompt,
        jint maxTokens,
        jfloat temperature
) {
#if !LLAMA_AVAILABLE
    LOGE("llama.cpp not available");
    return env->NewStringUTF("{\"error\": \"llama.cpp not compiled\", \"code\": -1}");
#else
    ModelContext *modelCtx = getModelContext(handle);
    if (!modelCtx) {
        LOGE("Invalid model handle: %ld", (long) handle);
        return env->NewStringUTF(R"({"error": "Invalid model handle", "code": -2})");
    }

    if (!modelCtx->model || !modelCtx->ctx) {
        LOGE("Model not properly loaded");
        return env->NewStringUTF(R"({"error": "Model not loaded", "code": -3})");
    }

    if (hardwareBuffer == nullptr) {
        LOGE("HardwareBuffer is null");
        return env->NewStringUTF(R"({"error": "HardwareBuffer is null", "code": -10})");
    }

    std::string promptStr = jstringToString(env, prompt);

    LOGI("========== HardwareBuffer Analysis Started ==========");
    LOGI("  Prompt length: %zu chars", promptStr.length());
    LOGI("  Max tokens: %d", maxTokens);
    LOGI("  Temperature: %.2f", temperature);

    // Get native AHardwareBuffer from Java HardwareBuffer
    // NOTE: This does NOT transfer ownership - the buffer is still managed by Java/Binder
    AHardwareBuffer *nativeBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (nativeBuffer == nullptr) {
        LOGE("Failed to get native AHardwareBuffer");
        return env->NewStringUTF(R"({"error": "Failed to get native buffer", "code": -11})");
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(nativeBuffer, &desc);

    LOGI("  Buffer dimensions: %dx%d", desc.width, desc.height);
    LOGI("  Buffer format: %d", desc.format);
    LOGI("  Buffer stride: %d", desc.stride);

    // Lock the buffer for CPU read access
    void *pixelData = nullptr;
    int lockResult = AHardwareBuffer_lock(
            nativeBuffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,  // fence (-1 = no fence)
            nullptr,  // rect (nullptr = entire buffer)
            &pixelData
    );

    if (lockResult != 0 || pixelData == nullptr) {
        LOGE("Failed to lock HardwareBuffer: %d", lockResult);
        return env->NewStringUTF(R"({"error": "Failed to lock buffer", "code": -12})");
    }

    LOGI("  Buffer locked successfully, pixel data at: %p", pixelData);

    std::vector<uint8_t> imageData;
    bool conversionSuccess = false;

    // Calculate image size based on format
    size_t bytesPerPixel = 4;  // RGBA_8888
    if (desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
        desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM) {
        bytesPerPixel = 4;
    } else if (desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM) {
        bytesPerPixel = 3;
    } else {
        LOGW("Unsupported buffer format: %d, assuming RGBA", desc.format);
    }

    // For VLM inference, we need to convert the raw pixel data to a format
    // that clip/llava can process. The simplest approach is to create a
    // raw RGB/RGBA buffer that can be passed to clip_image_load_from_bytes.
    //
    // However, clip expects encoded image formats (JPEG, PNG, etc.), not raw pixels.
    // We need to either:
    // 1. Encode to JPEG/PNG in native code (requires additional libraries)
    // 2. Pass raw pixels and modify clip to accept them
    // 3. Use a simpler approach for now: copy to byte array and use existing path
    //
    // For zero-copy efficiency, we'll create a minimal BMP header and pass as BMP
    // since BMP is essentially raw pixels with a header.

    // Create BMP format (uncompressed, easy to construct)
    const uint32_t width = desc.width;
    const uint32_t height = desc.height;
    const uint32_t rowSize = ((width * 3 + 3) / 4) * 4;  // BMP rows are 4-byte aligned
    const uint32_t imageSize = rowSize * height;
    const uint32_t fileSize = 54 + imageSize;            // 54 = BMP header size

    imageData.resize(fileSize);
    uint8_t *bmpData = imageData.data();

    // BMP File Header (14 bytes)
    bmpData[0] = 'B';
    bmpData[1] = 'M';
    *reinterpret_cast<uint32_t *>(bmpData + 2) = fileSize;
    *reinterpret_cast<uint32_t *>(bmpData + 6) = 0;    // Reserved
    *reinterpret_cast<uint32_t *>(bmpData + 10) = 54;  // Pixel data offset

    // BMP Info Header (40 bytes)
    *reinterpret_cast<uint32_t *>(bmpData + 14) = 40;  // Header size
    *reinterpret_cast<int32_t *>(bmpData + 18) = static_cast<int32_t>(width);
    *reinterpret_cast<int32_t *>(bmpData +
                                 22) = -static_cast<int32_t>(height);  // Negative = top-down
    *reinterpret_cast<uint16_t *>(bmpData + 26) = 1;    // Planes
    *reinterpret_cast<uint16_t *>(bmpData + 28) = 24;   // Bits per pixel (RGB)
    *reinterpret_cast<uint32_t *>(bmpData + 30) = 0;    // Compression (none)
    *reinterpret_cast<uint32_t *>(bmpData + 34) = imageSize;
    *reinterpret_cast<int32_t *>(bmpData + 38) = 2835;  // X pixels per meter
    *reinterpret_cast<int32_t *>(bmpData + 42) = 2835;  // Y pixels per meter
    *reinterpret_cast<uint32_t *>(bmpData + 46) = 0;    // Colors in palette
    *reinterpret_cast<uint32_t *>(bmpData + 50) = 0;    // Important colors

    // Copy pixel data (convert RGBA to BGR for BMP)
    auto *srcRow = static_cast<uint8_t *>(pixelData);
    uint8_t *dstRow = bmpData + 54;

    for (uint32_t y = 0; y < height; y++) {
        uint8_t *src = srcRow;
        uint8_t *dst = dstRow;

        for (uint32_t x = 0; x < width; x++) {
            // RGBA -> BGR (BMP format)
            dst[0] = src[2];  // B
            dst[1] = src[1];  // G
            dst[2] = src[0];  // R
            src += bytesPerPixel;
            dst += 3;
        }

        // Pad row to 4-byte boundary
        for (uint32_t p = width * 3; p < rowSize; p++) {
            dstRow[p] = 0;
        }

        srcRow += desc.stride * bytesPerPixel;
        dstRow += rowSize;
    }

    conversionSuccess = true;
    LOGI("  Converted to BMP: %zu bytes", imageData.size());

    AHardwareBuffer_unlock(nativeBuffer, nullptr);
    LOGI("  Buffer unlocked");

    // NOTE: DO NOT call AHardwareBuffer_release() - ownership is with Java/Binder

    if (!conversionSuccess) {
        LOGE("Failed to convert buffer to image format");
        return env->NewStringUTF(R"({"error": "Buffer conversion failed", "code": -13})");
    }

    // Now run inference using the existing inference path
    if (modelCtx->sampler) {
        llama_sampler_free(modelCtx->sampler);
    }
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    modelCtx->sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(modelCtx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_memory_t mem = llama_get_memory(modelCtx->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    const std::string &fullPrompt = promptStr;

#if CLIP_AVAILABLE
    // Process image for VLM if available
    if (!imageData.empty() && modelCtx->clipCtx) {
        LOGI("Processing HardwareBuffer image for VLM inference...");

        clip_image_u8 *img = clip_image_u8_init();
        if (clip_image_load_from_bytes(imageData.data(), imageData.size(), img)) {
            LOGI("Image loaded from buffer successfully");

            clip_image_f32 *imgProcessed = clip_image_f32_init();
            if (clip_image_preprocess(modelCtx->clipCtx, img, imgProcessed)) {
                LOGI("Image preprocessed successfully");

                float *imageEmbed = nullptr;
                int embedSize = 0;

                if (llava_image_embed_make_with_clip_img(
                        modelCtx->clipCtx,
                        modelCtx->numThreads,
                        imgProcessed,
                        &imageEmbed,
                        &embedSize
                )) {
                    LOGI("Image embedding created, size: %d", embedSize);
                    fullPrompt = "<image>\n" + promptStr;
                    free(imageEmbed);
                } else {
                    LOGW("Failed to create image embeddings");
                }
            } else {
                LOGW("Failed to preprocess image");
            }
            clip_image_f32_free(imgProcessed);
        } else {
            LOGW("Failed to load image from buffer bytes");
        }
        clip_image_u8_free(img);
    } else if (!imageData.empty() && !modelCtx->clipCtx) {
        LOGW("Image provided but CLIP model not loaded - ignoring image");
    }
#else
    LOGW("CLIP support not compiled - image will be ignored");
#endif

    // Get vocab from model
    const llama_vocab *vocab = llama_model_get_vocab(modelCtx->model);
    if (!vocab) {
        LOGE("Failed to get vocab from model");
        return env->NewStringUTF(R"({"error": "Failed to get vocab", "code": -4})");
    }

    const int n_ctx = llama_n_ctx(modelCtx->ctx);
    std::vector<llama_token> tokens(n_ctx);

    int n_tokens = llama_tokenize(
            vocab,
            fullPrompt.c_str(),
            static_cast<int32_t>(fullPrompt.length()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
    );

    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF(R"({"error": "Tokenization failed", "code": -5})");
    }

    tokens.resize(n_tokens);
    LOGI("Tokenized prompt: %d tokens", n_tokens);

    if (n_tokens > n_ctx - 4) {
        LOGE("Prompt too long: %d tokens (max: %d)", n_tokens, n_ctx - 4);
        return env->NewStringUTF(R"({"error": "Prompt too long", "code": -6})");
    }

    const int n_batch = 128;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        LOGE("Failed to allocate batch");
        return env->NewStringUTF(R"({"error": "Batch allocation failed", "code": -8})");
    }

    auto start_time = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < n_tokens; i += n_batch) {
        batch_clear(batch);

        int batch_end = std::min(i + n_batch, n_tokens);
        for (int j = i; j < batch_end; j++) {
            bool is_last_token = (j == n_tokens - 1);
            batch_add(batch, tokens[j], j, {0}, is_last_token);
        }

        if (llama_decode(modelCtx->ctx, batch) != 0) {
            llama_batch_free(batch);
            LOGE("Failed to decode batch at position %d", i);
            return env->NewStringUTF(R"({"error": "Decode failed", "code": -7})");
        }
    }

    auto prompt_end_time = std::chrono::high_resolution_clock::now();
    auto prompt_duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            prompt_end_time - start_time).count();
    LOGI("Prompt processing complete in %lld ms", prompt_duration);

    std::string response;
    int n_cur = n_tokens;
    int n_generated = 0;

    while (n_generated < maxTokens) {
        llama_token new_token = llama_sampler_sample(modelCtx->sampler, modelCtx->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation at token %d", n_generated);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);
        }

        batch_clear(batch);
        batch_add(batch, new_token, n_cur, {0}, true);

        if (llama_decode(modelCtx->ctx, batch) != 0) {
            LOGE("Failed to decode token at position %d", n_cur);
            break;
        }

        n_cur++;
        n_generated++;

        if (n_generated % 50 == 0) {
            LOGI("Generated %d tokens...", n_generated);
        }
    }

    llama_batch_free(batch);

    LOGI("========== HardwareBuffer Analysis Complete ==========");
    LOGI("  Tokens generated: %d", n_generated);
    LOGI("  Response length: %zu chars", response.length());

    return env->NewStringUTF(response.c_str());
#endif
}

} // extern "C"
