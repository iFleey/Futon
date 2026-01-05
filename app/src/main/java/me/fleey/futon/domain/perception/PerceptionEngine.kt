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
package me.fleey.futon.domain.perception

import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree

/**
 * Engine for capturing and analyzing UI structur。
 */
interface PerceptionEngine {

  /** Capture current screen's UI hierarchy tree via uiautomator dump. */
  suspend fun captureUITree(): PerceptionResult

  /** Find element matching query, checks cache first then semantic matching. */
  suspend fun findElement(query: MatchQuery): ElementResult

  /** Find deepest element at screen coordinates. */
  suspend fun findElementAt(x: Int, y: Int, mustBeClickable: Boolean = false): ElementResult

  /** Get current perception mode. */
  fun getPerceptionMode(): PerceptionMode

  /** Check if root-based perception is available. */
  suspend fun isHybridPerceptionAvailable(): Boolean

  /** Get screen dimensions (width, height) in pixels. */
  fun getScreenDimensions(): Pair<Int, Int>?

  suspend fun clearCache()

  suspend fun recordSuccessfulMatch(node: UINode, query: MatchQuery)
}

sealed interface PerceptionResult {
  data class Success(
    val tree: UITree,
    val captureTimeMs: Long,
    val parseTimeMs: Long = 0L,
    val nodeCount: Int = 0,
  ) : PerceptionResult

  data class Error(
    val message: String,
    val cause: Throwable? = null,
  ) : PerceptionResult

  data object RootUnavailable : PerceptionResult

  data class Timeout(
    val timeoutMs: Long,
    val partialData: String? = null,
  ) : PerceptionResult
}

sealed interface ElementResult {
  data class Found(
    val node: UINode,
    val score: Float,
    val fromCache: Boolean = false,
  ) : ElementResult

  data class NotFound(
    val query: MatchQuery,
    val searchedNodes: Int = 0,
  ) : ElementResult

  data class Error(
    val message: String,
    val cause: Throwable? = null,
  ) : ElementResult
}

/** Perception mode. Root-Only architecture: only ROOT_SHELL is supported. */
enum class PerceptionMode {
  ROOT_SHELL,
  NONE
}

fun PerceptionResult.isSuccess(): Boolean = this is PerceptionResult.Success

fun PerceptionResult.getTreeOrNull(): UITree? = (this as? PerceptionResult.Success)?.tree

fun PerceptionResult.getErrorMessage(): String? = when (this) {
  is PerceptionResult.Success -> null
  is PerceptionResult.Error -> message
  is PerceptionResult.RootUnavailable -> "Root access is not available"
  is PerceptionResult.Timeout -> "Capture timed out after ${timeoutMs}ms"
}

fun ElementResult.isFound(): Boolean = this is ElementResult.Found

fun ElementResult.getNodeOrNull(): UINode? = (this as? ElementResult.Found)?.node

fun ElementResult.getErrorMessage(): String? = when (this) {
  is ElementResult.Found -> null
  is ElementResult.NotFound -> "No element found matching query"
  is ElementResult.Error -> message
}
