package me.fleey.futon.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.fleey.futon.data.history.ExecutionLogRepository
import me.fleey.futon.data.history.ExecutionLogRepositoryImpl
import me.fleey.futon.data.history.TaskHistoryRepository
import me.fleey.futon.data.history.TaskHistoryRepositoryImpl
import me.fleey.futon.data.prompt.PromptRepository
import me.fleey.futon.data.prompt.PromptRepositoryImpl
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "futon_settings")
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "futon_history")
private val Context.executionLogDataStore: DataStore<Preferences> by preferencesDataStore(name = "futon_execution_logs")
private val Context.promptDataStore: DataStore<Preferences> by preferencesDataStore(name = "futon_prompts")
private val Context.inferenceMetricsDataStore: DataStore<Preferences> by preferencesDataStore(name = "inference_metrics")
private val Context.gatewayConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "gateway_config")
private val Context.providerConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "provider_config")
private val Context.inferenceStatsDataStore: DataStore<Preferences> by preferencesDataStore(name = "inference_stats")

@Module(includes = [DatabaseModule::class, ServiceModule::class, CircuitModule::class])
@ComponentScan("me.fleey.futon")
class FutonAppModule {

  @Single
  fun provideDataStore(context: Context): DataStore<Preferences> = context.dataStore

  @Single
  @Named("inference_metrics")
  fun provideInferenceMetricsDataStore(context: Context): DataStore<Preferences> = context.inferenceMetricsDataStore

  @Single
  @Named("gateway_config")
  fun provideGatewayConfigDataStore(context: Context): DataStore<Preferences> = context.gatewayConfigDataStore

  @Single
  @Named("provider_config")
  fun provideProviderConfigDataStore(context: Context): DataStore<Preferences> = context.providerConfigDataStore

  @Single
  @Named("inference_stats")
  fun provideInferenceStatsDataStore(context: Context): DataStore<Preferences> = context.inferenceStatsDataStore

  @Single
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Single
  fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
      json(json)
    }
    install(HttpTimeout) {
      connectTimeoutMillis = 30_000
      socketTimeoutMillis = 30_000
    }
  }

  @Single
  fun providePromptRepository(context: Context, json: Json): PromptRepository {
    return PromptRepositoryImpl(context.promptDataStore, json)
  }

  @Single
  fun provideTaskHistoryRepository(context: Context): TaskHistoryRepository {
    return TaskHistoryRepositoryImpl(context.historyDataStore)
  }

  @Single
  fun provideExecutionLogRepository(context: Context): ExecutionLogRepository {
    return ExecutionLogRepositoryImpl(context.executionLogDataStore)
  }
}
