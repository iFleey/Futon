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
package me.fleey.futon.data.ai.routing

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.fleey.futon.data.ai.repository.ProviderRepository
import me.fleey.futon.domain.localmodel.LocalInferenceEngine
import org.koin.core.annotation.Single

private const val TAG = "SourceAvailabilityTracker"

/**
 * Tracks availability of inference sources.
 */
interface SourceAvailabilityTracker {
  val sourceAvailability: StateFlow<Map<InferenceSource, SourceAvailability>>

  suspend fun checkAvailability(source: InferenceSource): SourceAvailability
  fun markAvailable(source: InferenceSource)
  fun markUnavailable(
    source: InferenceSource,
    reason: String,
    cooldownMs: Long = SourceAvailability.DEFAULT_COOLDOWN_MS,
  )

  fun resetSource(source: InferenceSource)
  fun resetAll()
  fun filterAvailable(sources: List<InferenceSource>): List<InferenceSource>
  suspend fun refreshAllAvailability()
}

@Single(binds = [SourceAvailabilityTracker::class])
class SourceAvailabilityTrackerImpl(
  private val localInferenceEngine: LocalInferenceEngine?,
  private val providerRepository: ProviderRepository,
) : SourceAvailabilityTracker {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _sourceAvailability = MutableStateFlow<Map<InferenceSource, SourceAvailability>>(emptyMap())
  override val sourceAvailability: StateFlow<Map<InferenceSource, SourceAvailability>> =
    _sourceAvailability.asStateFlow()

  init {
    startCooldownMonitor()
  }

  override suspend fun checkAvailability(source: InferenceSource): SourceAvailability {
    val current = _sourceAvailability.value[source]
    if (current is SourceAvailability.Unavailable && !current.isCooldownExpired()) {
      return current
    }

    val newAvailability = when (source) {
      is InferenceSource.LocalModel -> checkLocalModelAvailability()
      is InferenceSource.CloudProvider -> checkCloudProviderAvailability(source)
    }

    updateAvailability(source, newAvailability)
    return newAvailability
  }

  override fun markAvailable(source: InferenceSource) {
    Log.d(TAG, "Marking ${source.id} as available")
    updateAvailability(source, SourceAvailability.Available())
  }

  override fun markUnavailable(source: InferenceSource, reason: String, cooldownMs: Long) {
    val clampedCooldown = cooldownMs.coerceIn(
      SourceAvailability.MIN_COOLDOWN_MS,
      SourceAvailability.MAX_COOLDOWN_MS,
    )
    Log.w(TAG, "Marking ${source.id} as unavailable: $reason")
    updateAvailability(
      source,
      SourceAvailability.Unavailable(
        reason = reason,
        cooldownExpiresMs = System.currentTimeMillis() + clampedCooldown,
      ),
    )
  }

  override fun resetSource(source: InferenceSource) {
    updateAvailability(source, SourceAvailability.Unknown)
  }

  override fun resetAll() {
    _sourceAvailability.update { current -> current.mapValues { SourceAvailability.Unknown } }
  }

  override fun filterAvailable(sources: List<InferenceSource>): List<InferenceSource> {
    val availability = _sourceAvailability.value
    return sources.filter { source ->
      when (val state = availability[source]) {
        is SourceAvailability.Available -> true
        is SourceAvailability.Unknown -> true
        is SourceAvailability.Unavailable -> state.isCooldownExpired()
        null -> true
      }
    }
  }

  override suspend fun refreshAllAvailability() {
    Log.d(TAG, "Refreshing availability for all sources")

    val localAvailability = checkLocalModelAvailability()
    updateAvailability(InferenceSource.LocalModel, localAvailability)

    val providers = providerRepository.getProviders()
    for (provider in providers) {
      if (!provider.enabled) continue
      val models = providerRepository.getModels(provider.id)
      for (model in models) {
        if (!model.enabled) continue
        val source = InferenceSource.CloudProvider(
          providerId = provider.id,
          providerName = provider.name,
          protocol = provider.protocol,
          modelId = model.modelId,
        )
        val availability = checkCloudProviderAvailability(source)
        updateAvailability(source, availability)
      }
    }
  }

  private fun updateAvailability(source: InferenceSource, availability: SourceAvailability) {
    _sourceAvailability.update { current -> current + (source to availability) }
  }

  private fun checkLocalModelAvailability(): SourceAvailability {
    val engine = localInferenceEngine
      ?: return SourceAvailability.Unavailable("Local inference engine not configured")

    return if (engine.isModelLoaded()) {
      SourceAvailability.Available()
    } else {
      SourceAvailability.Unavailable("No model loaded")
    }
  }

  private suspend fun checkCloudProviderAvailability(source: InferenceSource.CloudProvider): SourceAvailability {
    val provider = providerRepository.getProvider(source.providerId)
      ?: return SourceAvailability.Unavailable("Provider not found")

    return when {
      !provider.enabled -> SourceAvailability.Unavailable("Provider disabled")
      !provider.isConfigured() -> SourceAvailability.Unavailable("API key not set")
      else -> SourceAvailability.Available()
    }
  }

  private fun startCooldownMonitor() {
    scope.launch {
      while (true) {
        delay(5_000L)
        _sourceAvailability.update { current ->
          current.mapValues { (source, availability) ->
            if (availability is SourceAvailability.Unavailable && availability.isCooldownExpired()) {
              Log.d(TAG, "Cooldown expired for ${source.id}")
              SourceAvailability.Unknown
            } else {
              availability
            }
          }
        }
      }
    }
  }
}
