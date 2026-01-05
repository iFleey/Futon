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
package me.fleey.futon.data.perception

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.fleey.futon.data.perception.models.CachedPattern
import me.fleey.futon.domain.perception.MatchQuery

/**
 * ElementCacheRepository implementation using DataStore.
 * LRU eviction at 1000 entries, thread-safe with Mutex.
 */
import org.koin.core.annotation.Single

@Single(binds = [ElementCacheRepository::class])
class ElementCacheRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
) : ElementCacheRepository {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val mutex = Mutex()

  // In-memory cache for faster access
  private var memoryCache: MutableMap<String, CachedPattern>? = null

  override suspend fun cachePattern(pattern: CachedPattern) = mutex.withLock {
    val cache = loadCacheInternal()

    if (cache.size >= ElementCacheRepository.MAX_CACHE_SIZE && !cache.containsKey(pattern.id)) {
      evictLeastRecentlyUsed(cache)
    }

    cache[pattern.id] = pattern
    saveCacheInternal(cache)
  }

  override suspend fun findPattern(query: MatchQuery): CachedPattern? = mutex.withLock {
    val cache = loadCacheInternal()

    val matchingPatterns = cache.values.filter { pattern ->
      matchesQuery(pattern, query)
    }

    // Return the pattern with highest priority score
    matchingPatterns.maxByOrNull { it.priorityScore() }
  }

  override suspend fun findPatternById(patternId: String): CachedPattern? = mutex.withLock {
    val cache = loadCacheInternal()
    cache[patternId]
  }

  override suspend fun updatePattern(pattern: CachedPattern): Boolean = mutex.withLock {
    val cache = loadCacheInternal()

    if (cache.containsKey(pattern.id)) {
      cache[pattern.id] = pattern
      saveCacheInternal(cache)
      true
    } else {
      false
    }
  }

  override suspend fun removePattern(patternId: String): Boolean = mutex.withLock {
    val cache = loadCacheInternal()

    if (cache.containsKey(patternId)) {
      cache.remove(patternId)
      saveCacheInternal(cache)
      true
    } else {
      false
    }
  }

  override suspend fun clearAll() {
    mutex.withLock {
      memoryCache = mutableMapOf()
      dataStore.edit { prefs ->
        prefs.remove(CACHE_KEY)
      }
    }
  }

  override suspend fun size(): Int = mutex.withLock {
    loadCacheInternal().size
  }

  override suspend fun getAllPatterns(): List<CachedPattern> = mutex.withLock {
    loadCacheInternal().values.toList()
  }

  override suspend fun removeStalePatterns(maxIdleTimeMs: Long): Int = mutex.withLock {
    val cache = loadCacheInternal()
    val currentTime = System.currentTimeMillis()

    val staleIds = cache.filter { (_, pattern) ->
      currentTime - pattern.lastUsedMs > maxIdleTimeMs
    }.keys

    staleIds.forEach { cache.remove(it) }

    if (staleIds.isNotEmpty()) {
      saveCacheInternal(cache)
    }

    staleIds.size
  }

  /**
   * Check if a cached pattern matches the given query.
   *
   * A pattern matches if any of its non-null criteria match the query.
   */
  private fun matchesQuery(pattern: CachedPattern, query: MatchQuery): Boolean {
    if (query.resourceId != null && pattern.resourceId != null) {
      if (pattern.resourceId.contains(query.resourceId, ignoreCase = true) ||
        query.resourceId.contains(pattern.resourceId, ignoreCase = true)
      ) {
        return true
      }
    }

    if (query.text != null && pattern.textPattern != null) {
      if (pattern.textPattern.contains(query.text, ignoreCase = true) ||
        query.text.contains(pattern.textPattern, ignoreCase = true)
      ) {
        return true
      }
    }

    if (query.contentDesc != null && pattern.contentDescPattern != null) {
      if (pattern.contentDescPattern.contains(query.contentDesc, ignoreCase = true) ||
        query.contentDesc.contains(pattern.contentDescPattern, ignoreCase = true)
      ) {
        return true
      }
    }

    if (query.className != null && pattern.className != null) {
      if (pattern.className.contains(query.className, ignoreCase = true) ||
        query.className.contains(pattern.className, ignoreCase = true)
      ) {
        return true
      }
    }

    // Hierarchy path match
    if (query.hierarchyPath != null && pattern.hierarchySignature != null) {
      if (pattern.hierarchySignature == query.hierarchyPath) {
        return true
      }
    }

    // Bounds ratio match (with tolerance)
    if (query.boundsRatio != null && pattern.boundsRatio != null) {
      val similarity = pattern.boundsRatio.similarity(query.boundsRatio)
      if (similarity >= BOUNDS_SIMILARITY_THRESHOLD) {
        return true
      }
    }

    return false
  }

  /**
   * Evict the least recently used pattern from the cache.
   */
  private fun evictLeastRecentlyUsed(cache: MutableMap<String, CachedPattern>) {
    val lruPattern = cache.values.minByOrNull { it.priorityScore() }
    lruPattern?.let { cache.remove(it.id) }
  }

  /**
   * Load cache from DataStore or memory.
   */
  private suspend fun loadCacheInternal(): MutableMap<String, CachedPattern> {
    memoryCache?.let { return it }

    val patterns = dataStore.data.map { prefs ->
      val cacheJson = prefs[CACHE_KEY]
      if (cacheJson != null) {
        try {
          json.decodeFromString<List<CachedPattern>>(cacheJson)
        } catch (e: Exception) {
          emptyList()
        }
      } else {
        emptyList()
      }
    }.first()

    val cache = patterns.associateBy { it.id }.toMutableMap()
    memoryCache = cache
    return cache
  }

  private suspend fun saveCacheInternal(cache: MutableMap<String, CachedPattern>) {
    memoryCache = cache
    val patterns = cache.values.toList()
    val cacheJson = json.encodeToString(patterns)

    dataStore.edit { prefs ->
      prefs[CACHE_KEY] = cacheJson
    }
  }

  companion object {
    private val CACHE_KEY = stringPreferencesKey("element_cache")

    private const val BOUNDS_SIMILARITY_THRESHOLD = 0.85f
  }
}
