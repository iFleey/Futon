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

/**
 * Implementation of [PerceptionEngine] using root shell commands.
 *
 * @param context Android context for accessing system services
 * @param rootShell Root shell for executing privileged commands
 * @param uiTreeParser Parser for converting XML to UITree
 * @param semanticMatcher Matcher for finding elements by criteria
 * @param elementCache Cache for storing successful match patterns
 */
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import me.fleey.futon.data.perception.ElementCacheRepository
import me.fleey.futon.data.perception.models.CachedPattern
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import org.koin.core.annotation.Single
import java.util.UUID

@Single(binds = [PerceptionEngine::class])
class PerceptionEngineImpl(
  private val context: Context,
  private val rootShell: RootShell,
  private val uiTreeParser: UITreeParser,
  private val semanticMatcher: SemanticMatcher,
  private val elementCache: ElementCacheRepository,
) : PerceptionEngine {
  private var screenWidth: Int = 0
  private var screenHeight: Int = 0

  @Volatile
  private var currentMode: PerceptionMode = PerceptionMode.NONE

  // Last captured tree for reuse within short time window
  @Volatile
  private var lastCapturedTree: UITree? = null

  @Volatile
  private var lastCaptureTimeMs: Long = 0L

  init {
    initializeScreenDimensions()
  }

  private fun initializeScreenDimensions() {
    try {
      val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION")
      windowManager.defaultDisplay.getRealMetrics(metrics)
      screenWidth = metrics.widthPixels
      screenHeight = metrics.heightPixels
    } catch (e: Exception) {
      // Fallback to common dimensions
      screenWidth = DEFAULT_SCREEN_WIDTH
      screenHeight = DEFAULT_SCREEN_HEIGHT
    }
  }

  override suspend fun captureUITree(): PerceptionResult {
    val now = System.currentTimeMillis()
    val recentTree = lastCapturedTree
    if (recentTree != null && (now - lastCaptureTimeMs) < TREE_CACHE_DURATION_MS) {
      return PerceptionResult.Success(
        tree = recentTree,
        captureTimeMs = 0,
        parseTimeMs = 0,
        nodeCount = recentTree.nodeCount(),
      )
    }

    if (!rootShell.isRootAvailable()) {
      currentMode = PerceptionMode.NONE
      return PerceptionResult.RootUnavailable
    }

    currentMode = PerceptionMode.ROOT_SHELL
    val captureStartTime = System.currentTimeMillis()

    val dumpResult = executeDumpCommand()

    return when (dumpResult) {
      is DumpResult.Success -> {
        val captureTimeMs = System.currentTimeMillis() - captureStartTime
        val parseStartTime = System.currentTimeMillis()

        val parseResult = uiTreeParser.parse(
          xml = dumpResult.xml,
          screenWidth = screenWidth,
          screenHeight = screenHeight,
        )

        when (parseResult) {
          is UITreeParseResult.Success -> {
            val parseTimeMs = System.currentTimeMillis() - parseStartTime

            lastCapturedTree = parseResult.tree
            lastCaptureTimeMs = System.currentTimeMillis()

            val totalTime = captureTimeMs + parseTimeMs
            if (totalTime > SLOW_CAPTURE_THRESHOLD_MS) {
              // NOTE: Could add logging here
            }

            PerceptionResult.Success(
              tree = parseResult.tree,
              captureTimeMs = captureTimeMs,
              parseTimeMs = parseTimeMs,
              nodeCount = parseResult.nodeCount,
            )
          }

          is UITreeParseResult.Error -> {
            PerceptionResult.Error(
              message = "Failed to parse UI tree: ${parseResult.message}",
              cause = parseResult.exception,
            )
          }
        }
      }

      is DumpResult.Error -> {
        PerceptionResult.Error(
          message = dumpResult.message,
          cause = dumpResult.cause,
        )
      }

      is DumpResult.Timeout -> {
        PerceptionResult.Timeout(
          timeoutMs = dumpResult.timeoutMs,
          partialData = dumpResult.partialOutput,
        )
      }

      is DumpResult.RootDenied -> {
        currentMode = PerceptionMode.NONE
        PerceptionResult.RootUnavailable
      }
    }
  }

  override suspend fun findElement(query: MatchQuery): ElementResult {
    val cachedPattern = elementCache.findPattern(query)
    if (cachedPattern != null) {
      // Try to find element using cached pattern
      val cachedResult = findElementFromCache(cachedPattern)
      if (cachedResult is ElementResult.Found) {
        elementCache.updatePattern(
          cachedPattern.copy(
            lastUsedMs = System.currentTimeMillis(),
            successCount = cachedPattern.successCount + 1,
          ),
        )
        return cachedResult.copy(fromCache = true)
      } else {
        // Cached pattern no longer valid, remove it
        elementCache.removePattern(cachedPattern.id)
      }
    }

    val captureResult = captureUITree()

    return when (captureResult) {
      is PerceptionResult.Success -> {
        // Use semantic matcher to find element
        val matchResult = semanticMatcher.findBestMatch(captureResult.tree, query)

        if (matchResult != null && matchResult.exceedsThreshold()) {
          ElementResult.Found(
            node = matchResult.node,
            score = matchResult.score,
            fromCache = false,
          )
        } else {
          ElementResult.NotFound(
            query = query,
            searchedNodes = captureResult.nodeCount,
          )
        }
      }

      is PerceptionResult.Error -> {
        ElementResult.Error(
          message = captureResult.message,
          cause = captureResult.cause,
        )
      }

      is PerceptionResult.RootUnavailable -> {
        ElementResult.Error(
          message = "Root access is not available for UI perception",
        )
      }

      is PerceptionResult.Timeout -> {
        ElementResult.Error(
          message = "UI capture timed out after ${captureResult.timeoutMs}ms",
        )
      }
    }
  }

  override suspend fun findElementAt(x: Int, y: Int, mustBeClickable: Boolean): ElementResult {
    val captureResult = captureUITree()

    return when (captureResult) {
      is PerceptionResult.Success -> {
        val node = if (mustBeClickable) {
          captureResult.tree.findClickableNodeAt(x, y)
        } else {
          captureResult.tree.findNodeAt(x, y)
        }

        if (node != null) {
          ElementResult.Found(
            node = node,
            score = 1.0f,
            fromCache = false,
          )
        } else {
          ElementResult.NotFound(
            query = MatchQuery(),
            searchedNodes = captureResult.nodeCount,
          )
        }
      }

      is PerceptionResult.Error -> {
        ElementResult.Error(
          message = captureResult.message,
          cause = captureResult.cause,
        )
      }

      is PerceptionResult.RootUnavailable -> {
        ElementResult.Error(
          message = "Root access is not available for UI perception",
        )
      }

      is PerceptionResult.Timeout -> {
        ElementResult.Error(
          message = "UI capture timed out after ${captureResult.timeoutMs}ms",
        )
      }
    }
  }

  override fun getPerceptionMode(): PerceptionMode = currentMode

  override suspend fun isHybridPerceptionAvailable(): Boolean {
    return rootShell.isRootAvailable()
  }

  override fun getScreenDimensions(): Pair<Int, Int>? {
    return if (screenWidth > 0 && screenHeight > 0) {
      screenWidth to screenHeight
    } else {
      null
    }
  }

  override suspend fun clearCache() {
    elementCache.clearAll()
    lastCapturedTree = null
    lastCaptureTimeMs = 0L
  }

  override suspend fun recordSuccessfulMatch(node: UINode, query: MatchQuery) {
    // Create a cached pattern from the successful match
    val pattern = CachedPattern(
      id = UUID.randomUUID().toString(),
      resourceId = node.resourceId ?: query.resourceId,
      textPattern = node.text ?: query.text,
      boundsRatio = node.boundsRatio(screenWidth, screenHeight),
      hierarchySignature = node.hierarchySignature(),
      successCount = 1,
      lastUsedMs = System.currentTimeMillis(),
      createdMs = System.currentTimeMillis(),
    )

    elementCache.cachePattern(pattern)
  }

  /**
   * Execute the uiautomator dump command with retry logic.
   */
  private suspend fun executeDumpCommand(): DumpResult {
    var lastError: DumpResult.Error? = null

    // Retry up to MAX_DUMP_RETRIES times
    repeat(MAX_DUMP_RETRIES) { attempt ->
      val result = executeDumpCommandOnce()

      when (result) {
        is DumpResult.Success -> return result
        is DumpResult.RootDenied -> return result // Don't retry root denied
        is DumpResult.Timeout -> return result // Don't retry timeout
        is DumpResult.Error -> {
          lastError = result
          // Wait a bit before retrying (UI might be transitioning)
          if (attempt < MAX_DUMP_RETRIES - 1) {
            delay(DUMP_RETRY_DELAY_MS)
          }
        }
      }
    }

    return lastError ?: DumpResult.Error(
      message = "uiautomator dump failed after $MAX_DUMP_RETRIES attempts",
      cause = null,
    )
  }

  /**
   * Execute a single uiautomator dump attempt.
   */
  private suspend fun executeDumpCommandOnce(): DumpResult {
    val dumpFile = DUMP_FILE_PATH
    rootShell.execute("rm -f $dumpFile", timeoutMs = 1000L)

    // Execute uiautomator dump
    val dumpCommand = "uiautomator dump $dumpFile"
    val shellResult = rootShell.execute(dumpCommand, timeoutMs = DEFAULT_TIMEOUT_MS)

    return when (shellResult) {
      is ShellResult.Success -> {
        val dumpOutput = shellResult.output.trim()

        if (dumpOutput.contains("ERROR", ignoreCase = true) ||
          dumpOutput.contains("exception", ignoreCase = true) ||
          dumpOutput.contains("failed", ignoreCase = true)
        ) {
          return DumpResult.Error(
            message = "uiautomator dump failed: $dumpOutput",
            cause = null,
          )
        }

        delay(150)

        val checkResult = rootShell.execute("test -f $dumpFile && echo EXISTS", timeoutMs = 1000L)
        if (checkResult !is ShellResult.Success || !checkResult.output.contains("EXISTS")) {
          return DumpResult.Error(
            message = "uiautomator dump did not create file. Command output: $dumpOutput",
            cause = null,
          )
        }

        val readResult = rootShell.execute("cat $dumpFile", timeoutMs = 2000L)

        when (readResult) {
          is ShellResult.Success -> {
            val xml = readResult.output.trim()
            if (xml.isEmpty()) {
              DumpResult.Error(
                message = "uiautomator dump produced empty file",
                cause = null,
              )
            } else if (!xml.startsWith("<") && !xml.startsWith("<?xml")) {
              // Got non-XML content (possibly error message)
              DumpResult.Error(
                message = "uiautomator dump file contains non-XML content: ${xml.take(200)}",
                cause = null,
              )
            } else {
              rootShell.execute("rm -f $dumpFile", timeoutMs = 1000L)
              DumpResult.Success(xml)
            }
          }

          is ShellResult.Error -> {
            DumpResult.Error(
              message = "Failed to read dump file: ${readResult.message}",
              cause = readResult.exception,
            )
          }

          is ShellResult.Timeout -> {
            DumpResult.Timeout(
              timeoutMs = 2000L,
              partialOutput = readResult.partialOutput,
            )
          }

          is ShellResult.RootDenied -> {
            DumpResult.RootDenied(readResult.reason)
          }
        }
      }

      is ShellResult.Error -> {
        DumpResult.Error(
          message = "uiautomator dump command failed: ${shellResult.message}",
          cause = shellResult.exception,
        )
      }

      is ShellResult.Timeout -> {
        DumpResult.Timeout(
          timeoutMs = shellResult.timeoutMs,
          partialOutput = shellResult.partialOutput,
        )
      }

      is ShellResult.RootDenied -> {
        DumpResult.RootDenied(shellResult.reason)
      }
    }
  }

  /**
   * Try to find an element using a cached pattern.
   */
  private suspend fun findElementFromCache(pattern: CachedPattern): ElementResult {
    val query = MatchQuery(
      resourceId = pattern.resourceId,
      text = pattern.textPattern,
      boundsRatio = pattern.boundsRatio,
      hierarchyPath = pattern.hierarchySignature,
    )
    val captureResult = captureUITree()

    return when (captureResult) {
      is PerceptionResult.Success -> {
        val matchResult = semanticMatcher.findBestMatch(captureResult.tree, query)

        if (matchResult != null && matchResult.exceedsThreshold()) {
          ElementResult.Found(
            node = matchResult.node,
            score = matchResult.score,
            fromCache = true,
          )
        } else {
          ElementResult.NotFound(
            query = query,
            searchedNodes = captureResult.nodeCount,
          )
        }
      }

      else -> {
        ElementResult.NotFound(
          query = query,
          searchedNodes = 0,
        )
      }
    }
  }

  /**
   * Internal result type for dump command execution.
   */
  private sealed interface DumpResult {
    data class Success(val xml: String) : DumpResult
    data class Error(val message: String, val cause: Throwable?) : DumpResult
    data class Timeout(val timeoutMs: Long, val partialOutput: String?) : DumpResult
    data class RootDenied(val reason: String) : DumpResult
  }

  companion object {
    private const val DUMP_FILE_PATH = "/sdcard/window_dump.xml"

    private const val DEFAULT_TIMEOUT_MS = 5000L

    private const val SLOW_CAPTURE_THRESHOLD_MS = 3000L

    private const val TREE_CACHE_DURATION_MS = 500L

    private const val DEFAULT_SCREEN_WIDTH = 1080

    private const val DEFAULT_SCREEN_HEIGHT = 2400

    private const val MAX_DUMP_RETRIES = 3

    private const val DUMP_RETRY_DELAY_MS = 200L
  }
}
