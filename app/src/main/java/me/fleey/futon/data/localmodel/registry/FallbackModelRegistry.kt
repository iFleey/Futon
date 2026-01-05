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
package me.fleey.futon.data.localmodel.registry

import me.fleey.futon.data.localmodel.models.ModelInfo
import me.fleey.futon.data.localmodel.models.QuantizationInfo
import me.fleey.futon.data.localmodel.models.QuantizationType

/**
 * Provides hardcoded fallback models when network and cache are unavailable.
 *
 * This registry ensures users always have access to at least some models
 * even when the remote API is unavailable and no cached catalog exists.
 * */
object FallbackModelRegistry {

  private const val GB = 1_000_000_000L
  private const val MB = 1_000_000L

  /**
   * Qwen2.5 3B Instruct - Recommended for UI-tree mode (text-only).
   */
  private val QWEN2_5_3B = ModelInfo(
    id = "qwen2.5-3b",
    name = "Qwen2.5 3B Instruct (Recommended)",
    description = "Alibaba 3B parameter text-only model, optimized for UI-tree mode. " +
      "Excellent instruction following and stable JSON output, great llama.cpp compatibility. " +
      "Recommended for pure UI structure analysis mode (no screenshot required).",
    provider = "Qwen",
    huggingFaceRepo = "bartowski/Qwen2.5-3B-Instruct-GGUF",
    isVisionLanguageModel = false,
    quantizations = listOf(
      QuantizationInfo(
        type = QuantizationType.INT4,
        mainModelFile = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        mainModelSize = (1.93 * GB).toLong(),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 3584,
      ),
      QuantizationInfo(
        type = QuantizationType.INT8,
        mainModelFile = "Qwen2.5-3B-Instruct-Q8_0.gguf",
        mainModelSize = (3.4 * GB).toLong(),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 5120,
      ),
    ),
  )

  /**
   * Qwen2.5 1.5B Instruct
   *
   * A compact text model with fast inference speed.
   */
  private val QWEN2_5_1_5B = ModelInfo(
    id = "qwen2.5-1.5b",
    name = "Qwen2.5 1.5B Instruct (Ultra-fast)",
    description = "Alibaba 1.5B parameter ultra-lightweight text-only model. " +
      "Extremely fast inference, suitable for low-end devices or scenarios requiring quick response. " +
      "Good instruction following, suitable for simple UI automation tasks.",
    provider = "Qwen",
    huggingFaceRepo = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
    isVisionLanguageModel = false,
    quantizations = listOf(
      QuantizationInfo(
        type = QuantizationType.INT4,
        mainModelFile = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        mainModelSize = (990 * MB),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 2048,
      ),
      QuantizationInfo(
        type = QuantizationType.INT8,
        mainModelFile = "Qwen2.5-1.5B-Instruct-Q8_0.gguf",
        mainModelSize = (1.65 * GB).toLong(),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 3072,
      ),
    ),
  )

  /**
   * SmolLM2 1.7B Instruct - HuggingFace's mobile-optimized model.
   */
  private val SMOLLM2_1_7B = ModelInfo(
    id = "smollm2-1.7b",
    name = "SmolLM2 1.7B Instruct",
    description = "HuggingFace's 1.7B parameter compact text-only model. " +
      "Optimized for mobile devices with fast inference speed. " +
      "Significantly improved instruction following, knowledge, and reasoning capabilities.",
    provider = "HuggingFace",
    huggingFaceRepo = "bartowski/SmolLM2-1.7B-Instruct-GGUF",
    isVisionLanguageModel = false,
    quantizations = listOf(
      QuantizationInfo(
        type = QuantizationType.INT4,
        mainModelFile = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        mainModelSize = (1.07 * GB).toLong(),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 2048,
      ),
      QuantizationInfo(
        type = QuantizationType.INT8,
        mainModelFile = "SmolLM2-1.7B-Instruct-Q8_0.gguf",
        mainModelSize = (1.8 * GB).toLong(),
        mmprojFile = null,
        mmprojSize = null,
        minRamMb = 3072,
      ),
    ),
  )

  /**
   * Step-GUI 4B (GELab-Zero-4B-preview) model information.
   *
   * This is a vision-language model designed for GUI automation tasks.
   * WARNING: Requires CLIP support and screenshot mode to work properly.
   */
  private val STEP_GUI_4B = ModelInfo(
    id = "step-gui-4b",
    name = "Step-GUI 4B (Requires Screenshot Mode)",
    description = "4B parameter vision-language model, optimized for GUI automation. " +
      "Note: This model is based on Qwen3-VL and requires screenshot mode to work properly. " +
      "If using pure UI-tree mode, please use text-only models like Qwen2.5-3B instead.",
    provider = "GELab",
    huggingFaceRepo = "prithivMLmods/GELab-Zero-4B-preview-GGUF",
    isVisionLanguageModel = true,
    quantizations = listOf(
      QuantizationInfo(
        type = QuantizationType.INT4,
        mainModelFile = "GELab-Zero-4B-preview.Q4_K_M.gguf",
        mainModelSize = (2.68 * GB).toLong(),
        mmprojFile = "GELab-Zero-4B-preview.mmproj-f16.gguf",
        mmprojSize = (454 * MB),
        minRamMb = 4096,
      ),
      QuantizationInfo(
        type = QuantizationType.INT8,
        mainModelFile = "GELab-Zero-4B-preview.Q8_0.gguf",
        mainModelSize = (4.28 * GB).toLong(),
        mmprojFile = "GELab-Zero-4B-preview.mmproj-f16.gguf",
        mmprojSize = (454 * MB),
        minRamMb = 6144,
      ),
      QuantizationInfo(
        type = QuantizationType.FP16,
        mainModelFile = "GELab-Zero-4B-preview.F16.gguf",
        mainModelSize = (8.05 * GB).toLong(),
        mmprojFile = "GELab-Zero-4B-preview.mmproj-f16.gguf",
        mmprojSize = (454 * MB),
        minRamMb = 10240,
      ),
    ),
  )

  /**
   * List of all fallback models.
   *
   * Text-only models are listed first as they are more reliable
   * without CLIP support.
   */
  private val FALLBACK_MODELS = listOf(
    QWEN2_5_3B,      // Recommended for UI-tree mode
    QWEN2_5_1_5B,    // Ultra-fast option
    SMOLLM2_1_7B,    // Mobile-optimized
    STEP_GUI_4B,     // VLM - requires screenshot mode
  )

  /**
   * Returns the list of hardcoded fallback models.
   *
   * @return List of fallback ModelInfo objects
   */
  fun getFallbackModels(): List<ModelInfo> = FALLBACK_MODELS
}
