package me.fleey.futon.di

import me.fleey.futon.data.ai.adapters.AnthropicAdapter
import me.fleey.futon.data.ai.adapters.GeminiAdapter
import me.fleey.futon.data.ai.adapters.OllamaAdapter
import me.fleey.futon.data.ai.adapters.OpenAIAdapter
import me.fleey.futon.data.ai.adapters.ProviderAdapter
import me.fleey.futon.data.ai.models.ApiProtocol
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
class ServiceModule {

  @Single
  fun provideProviderAdapters(
    openAIAdapter: OpenAIAdapter,
    geminiAdapter: GeminiAdapter,
    anthropicAdapter: AnthropicAdapter,
    ollamaAdapter: OllamaAdapter,
  ): Map<ApiProtocol, ProviderAdapter> = mapOf(
    ApiProtocol.OPENAI_COMPATIBLE to openAIAdapter,
    ApiProtocol.GEMINI to geminiAdapter,
    ApiProtocol.ANTHROPIC to anthropicAdapter,
    ApiProtocol.OLLAMA to ollamaAdapter,
  )
}
