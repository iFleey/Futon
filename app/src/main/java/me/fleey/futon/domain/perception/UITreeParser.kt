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

import me.fleey.futon.domain.perception.models.UITree

/**
 * Interface for parsing UI hierarchy XML from uiautomator dump.
 *
 * This parser converts the XML output from `uiautomator dump` command
 * into a structured [UITree] that can be used for element matching
 * and automation tasks.
 *
 * Example XML format:
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <hierarchy rotation="0">
 *   <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
 *         package="com.example.app" content-desc="" checkable="false"
 *         checked="false" clickable="false" enabled="true" focusable="false"
 *         focused="false" scrollable="false" long-clickable="false"
 *         password="false" selected="false" bounds="[0,0][1080,2400]">
 *     <node index="0" text="Hello" resource-id="com.example.app:id/text_view"
 *           class="android.widget.TextView" content-desc="Greeting"
 *           clickable="true" enabled="true" bounds="[100,200][500,300]">
 *     </node>
 *   </node>
 * </hierarchy>
 * ```
 */
interface UITreeParser {
  /**
   * Parse uiautomator dump XML output into a structured UI tree.
   *
   * @param xml The XML string from uiautomator dump
   * @param screenWidth Screen width in pixels (used for bounds validation)
   * @param screenHeight Screen height in pixels (used for bounds validation)
   * @return [UITreeParseResult] containing either the parsed tree or an error
   */
  fun parse(xml: String, screenWidth: Int, screenHeight: Int): UITreeParseResult
}

/**
 * Result of parsing a UI hierarchy XML.
 */
sealed interface UITreeParseResult {
  /**
   * Parsing succeeded.
   *
   * @property tree The parsed UI tree
   * @property parseTimeMs Time taken to parse in milliseconds
   * @property nodeCount Total number of nodes parsed
   */
  data class Success(
    val tree: UITree,
    val parseTimeMs: Long = 0L,
    val nodeCount: Int = 0,
  ) : UITreeParseResult

  data class Error(
    val message: String,
    val rawXml: String,
    val exception: Throwable? = null,
    val errorPosition: Int? = null,
  ) : UITreeParseResult
}

fun UITreeParseResult.isSuccess(): Boolean = this is UITreeParseResult.Success

fun UITreeParseResult.getTreeOrNull(): UITree? = (this as? UITreeParseResult.Success)?.tree

fun UITreeParseResult.getErrorMessage(): String? = (this as? UITreeParseResult.Error)?.message
