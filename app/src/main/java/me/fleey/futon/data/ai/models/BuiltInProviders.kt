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
package me.fleey.futon.data.ai.models

/**
 * Built-in provider templates for quick setup.
 * Users can use these as starting points or create fully custom providers.
 */
object BuiltInProviders {

  val OPENAI = ProviderTemplate(
    id = "builtin_openai",
    name = "OpenAI",
    protocol = ApiProtocol.OPENAI_COMPATIBLE,
    baseUrl = "https://api.openai.com/v1",
    iconKey = "openai",
    defaultModels = listOf(
      ModelTemplate("gpt-5.2", "GPT-5.2", 1.75, 14.0, 128000, true),
      ModelTemplate("gpt-5.1", "GPT-5.1", 1.25, 10.0, 128000, true),
      ModelTemplate("gpt-5", "GPT-5", 1.25, 10.0, 128000, true),
      ModelTemplate("gpt-5-mini", "GPT-5 Mini", 0.25, 2.0, 128000, true),
      ModelTemplate("gpt-5-nano", "GPT-5 Nano", 0.05, 0.4, 128000, true),
      ModelTemplate("gpt-4.1", "GPT-4.1", 2.0, 8.0, 1000000, true),
      ModelTemplate("gpt-4.1-mini", "GPT-4.1 Mini", 0.4, 1.6, 1000000, true),
      ModelTemplate("gpt-4.1-nano", "GPT-4.1 Nano", 0.1, 0.4, 1000000, true),
      ModelTemplate("gpt-4o", "GPT-4o", 2.5, 10.0, 128000, true),
      ModelTemplate("gpt-4o-mini", "GPT-4o Mini", 0.15, 0.6, 128000, true),
      ModelTemplate("o3", "o3", 2.0, 8.0, 200000, false),
      ModelTemplate("o4-mini", "o4 Mini", 1.1, 4.4, 200000, false),
    ),
  )

  val ANTHROPIC = ProviderTemplate(
    id = "builtin_anthropic",
    name = "Anthropic",
    protocol = ApiProtocol.ANTHROPIC,
    baseUrl = "https://api.anthropic.com/v1",
    iconKey = "anthropic",
    defaultModels = listOf(
      ModelTemplate("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", 3.0, 15.0, 200000, true),
      ModelTemplate("claude-haiku-4-5-20251001", "Claude Haiku 4.5", 1.0, 5.0, 200000, true),
      ModelTemplate("claude-opus-4-5-20251101", "Claude Opus 4.5", 5.0, 25.0, 200000, true),
      ModelTemplate("claude-sonnet-4-20250514", "Claude Sonnet 4", 3.0, 15.0, 200000, true),
      ModelTemplate("claude-opus-4-20250514", "Claude Opus 4", 15.0, 75.0, 200000, true),
      ModelTemplate("claude-opus-4-1-20250805", "Claude Opus 4.1", 15.0, 75.0, 200000, true),
    ),
  )

  val GEMINI = ProviderTemplate(
    id = "builtin_gemini",
    name = "Google Gemini",
    protocol = ApiProtocol.GEMINI,
    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    iconKey = "gemini",
    defaultModels = listOf(
      ModelTemplate("gemini-3-pro-preview", "Gemini 3 Pro", 2.0, 12.0, 1000000, true),
      ModelTemplate("gemini-3-flash-preview", "Gemini 3 Flash", 0.5, 3.0, 1000000, true),
      ModelTemplate("gemini-2.5-pro", "Gemini 2.5 Pro", 1.25, 10.0, 1000000, true),
      ModelTemplate("gemini-2.5-flash", "Gemini 2.5 Flash", 0.3, 2.5, 1000000, true),
      ModelTemplate("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", 0.1, 0.4, 1000000, true),
      ModelTemplate("gemini-2.0-flash", "Gemini 2.0 Flash", 0.1, 0.4, 1000000, true),
    ),
  )

  val DEEPSEEK = ProviderTemplate(
    id = "builtin_deepseek",
    name = "DeepSeek",
    protocol = ApiProtocol.OPENAI_COMPATIBLE,
    baseUrl = "https://api.deepseek.com/v1",
    iconKey = "deepseek",
    defaultModels = listOf(
      ModelTemplate("deepseek-chat", "DeepSeek V3.2", 0.56, 1.68, 131000, true),
      ModelTemplate("deepseek-reasoner", "DeepSeek R1", 0.56, 1.68, 131000, false),
    ),
  )

  val QWEN = ProviderTemplate(
    id = "builtin_qwen",
    name = "Qwen",
    protocol = ApiProtocol.OPENAI_COMPATIBLE,
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    iconKey = "qwen",
    defaultModels = listOf(
      ModelTemplate("qwen-vl-max", "Qwen VL Max", 3.0, 9.0, 32000, true),
      ModelTemplate("qwen-vl-plus", "Qwen VL Plus", 1.0, 3.0, 32000, true),
      ModelTemplate("qwen-turbo", "Qwen Turbo", 0.3, 0.6, 128000, false),
    ),
  )

  val ZHIPU = ProviderTemplate(
    id = "builtin_zhipu",
    name = "Zhipu AI",
    protocol = ApiProtocol.OPENAI_COMPATIBLE,
    baseUrl = "https://open.bigmodel.cn/api/paas/v4",
    iconKey = "zhipu",
    defaultModels = listOf(
      ModelTemplate("glm-4v-plus", "GLM-4V Plus", 10.0, 10.0, 8000, true),
      ModelTemplate("glm-4-plus", "GLM-4 Plus", 50.0, 50.0, 128000, false),
      ModelTemplate("glm-4-flash", "GLM-4 Flash", 0.0, 0.0, 128000, false),
    ),
  )

  val OLLAMA = ProviderTemplate(
    id = "builtin_ollama",
    name = "Ollama",
    protocol = ApiProtocol.OLLAMA,
    baseUrl = "http://localhost:11434",
    iconKey = "ollama",
    defaultModels = listOf(
      ModelTemplate("llava", "LLaVA", 0.0, 0.0, 4096, true),
      ModelTemplate("llama3.2-vision", "Llama 3.2 Vision", 0.0, 0.0, 128000, true),
    ),
  )

  val NEWAPI = ProviderTemplate(
    id = "builtin_newapi",
    name = "NewAPI",
    protocol = ApiProtocol.OPENAI_COMPATIBLE,
    baseUrl = "",
    iconKey = "newapi",
    defaultModels = emptyList(),
  )

  val all: List<ProviderTemplate> = listOf(
    OPENAI,
    ANTHROPIC,
    GEMINI,
    DEEPSEEK,
    QWEN,
    ZHIPU,
    OLLAMA,
    NEWAPI,
  )

  fun getById(id: String): ProviderTemplate? = all.find { it.id == id }
}

/**
 * Template for creating a provider with default models.
 */
data class ProviderTemplate(
  val id: String,
  val name: String,
  val protocol: ApiProtocol,
  val baseUrl: String,
  val iconKey: String,
  val defaultModels: List<ModelTemplate>,
) {
  fun toProvider(apiKey: String = "", customBaseUrl: String? = null): Provider = Provider(
    id = id,
    name = name,
    protocol = protocol,
    baseUrl = customBaseUrl ?: baseUrl,
    apiKey = apiKey,
    iconKey = iconKey,
  )

  fun toModels(providerId: String): List<ModelConfig> = defaultModels.map { it.toModelConfig(providerId) }
}

/**
 * Template for creating a model configuration.
 */
data class ModelTemplate(
  val modelId: String,
  val displayName: String,
  val inputPrice: Double,
  val outputPrice: Double,
  val contextWindow: Int,
  val supportsVision: Boolean,
) {
  fun toModelConfig(providerId: String): ModelConfig = ModelConfig(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    inputPrice = inputPrice,
    outputPrice = outputPrice,
    contextWindow = contextWindow,
    supportsVision = supportsVision,
  )
}
