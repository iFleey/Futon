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
 * UITreeParser implementation using Java's DOM parser.
 */
import me.fleey.futon.domain.perception.models.UIBounds
import me.fleey.futon.domain.perception.models.UINode
import me.fleey.futon.domain.perception.models.UITree
import org.koin.core.annotation.Single
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

@Single(binds = [UITreeParser::class])
class UITreeParserImpl : UITreeParser {

  override fun parse(xml: String, screenWidth: Int, screenHeight: Int): UITreeParseResult {
    val startTime = System.currentTimeMillis()

    if (xml.isBlank()) {
      return UITreeParseResult.Error(
        message = "XML input is empty or blank",
        rawXml = xml,
      )
    }

    val trimmedXml = xml.trim()

    val lowerXml = trimmedXml.lowercase()
    if (lowerXml.startsWith("error") ||
      lowerXml.contains("exception") ||
      lowerXml.contains("could not") ||
      lowerXml.contains("failed to") ||
      lowerXml.contains("unable to")
    ) {
      return UITreeParseResult.Error(
        message = "uiautomator returned error: ${trimmedXml.take(200)}",
        rawXml = xml,
      )
    }

    // Check if it's just a status message (not actual XML)
    if (!trimmedXml.startsWith("<") && !trimmedXml.startsWith("<?xml")) {
      // Might be a status message like "UI hierchary dumped to: ..."
      return UITreeParseResult.Error(
        message = "Expected XML but got status message: ${trimmedXml.take(200)}",
        rawXml = xml,
      )
    }

    if (!trimmedXml.contains("<hierarchy") && !trimmedXml.contains("<node")) {
      return UITreeParseResult.Error(
        message = "XML does not contain valid UI hierarchy structure (missing <hierarchy> or <node> tags)",
        rawXml = xml,
      )
    }

    return try {
      val factory = DocumentBuilderFactory.newInstance()
      factory.isNamespaceAware = false

      // Note: Android's XML parser doesn't support all Apache Xerces features
      try {
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      } catch (_: Exception) {
        // Feature not supported on this platform, ignore
      }
      try {
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      } catch (_: Exception) {
        // Feature not supported on this platform, ignore
      }
      try {
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      } catch (_: Exception) {
        // Feature not supported on this platform, ignore
      }

      val builder = factory.newDocumentBuilder()
      val inputSource = InputSource(StringReader(trimmedXml))
      val document = builder.parse(inputSource)

      val hierarchyElements = document.getElementsByTagName(TAG_HIERARCHY)
      if (hierarchyElements.length == 0) {
        return UITreeParseResult.Error(
          message = "No hierarchy element found in XML",
          rawXml = xml,
        )
      }

      val hierarchyElement = hierarchyElements.item(0) as Element
      val rotation = hierarchyElement.getAttribute(ATTR_ROTATION)?.toIntOrNull() ?: 0

      // Find root node element
      val nodeElements = hierarchyElement.getElementsByTagName(TAG_NODE)
      if (nodeElements.length == 0) {
        return UITreeParseResult.Error(
          message = "No root node found in XML hierarchy",
          rawXml = xml,
        )
      }

      var rootNodeElement: Element? = null
      val childNodes = hierarchyElement.childNodes
      for (i in 0 until childNodes.length) {
        val child = childNodes.item(i)
        if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == TAG_NODE) {
          rootNodeElement = child as Element
          break
        }
      }

      if (rootNodeElement == null) {
        return UITreeParseResult.Error(
          message = "No root node found in XML hierarchy",
          rawXml = xml,
        )
      }

      var nodeCount = 0
      var packageName: String? = null

      val rootNode = parseNodeElement(rootNodeElement) { count, pkg ->
        nodeCount = count
        if (packageName == null && pkg != null) {
          packageName = pkg
        }
      }

      val parseTime = System.currentTimeMillis() - startTime

      val tree = UITree(
        root = rootNode,
        captureTimeMs = System.currentTimeMillis(),
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        packageName = packageName,
        rotation = rotation,
      )

      UITreeParseResult.Success(
        tree = tree,
        parseTimeMs = parseTime,
        nodeCount = nodeCount,
      )
    } catch (e: Exception) {
      UITreeParseResult.Error(
        message = "Unexpected error during parsing: ${e.message}",
        rawXml = xml,
        exception = e,
      )
    }
  }


  /**
   * Parse a single node element from the DOM.
   *
   * @param element The DOM Element representing a node
   * @param onNodeParsed Callback invoked with (totalCount, packageName) after parsing
   * @return Parsed UINode with all children
   */
  private fun parseNodeElement(
    element: Element,
    onNodeParsed: (Int, String?) -> Unit,
  ): UINode {
    var totalCount = 0

    fun parseRecursive(elem: Element): UINode {
      totalCount++

      val className = elem.getAttribute(ATTR_CLASS).ifEmpty { DEFAULT_CLASS_NAME }
      val text = elem.getAttribute(ATTR_TEXT).takeIf { it.isNotEmpty() }
      val resourceId = elem.getAttribute(ATTR_RESOURCE_ID).takeIf { it.isNotEmpty() }
      val contentDesc = elem.getAttribute(ATTR_CONTENT_DESC).takeIf { it.isNotEmpty() }
      val packageName = elem.getAttribute(ATTR_PACKAGE).takeIf { it.isNotEmpty() }

      val boundsStr = elem.getAttribute(ATTR_BOUNDS)
      val bounds = UIBounds.Companion.fromString(boundsStr) ?: UIBounds.Companion.EMPTY

      val index = elem.getAttribute(ATTR_INDEX).toIntOrNull() ?: 0

      // Parse boolean attributes
      val isClickable = elem.getAttribute(ATTR_CLICKABLE).toBoolean()
      val isEnabled = elem.getAttribute(ATTR_ENABLED).toBooleanOrDefault(true)
      val isFocused = elem.getAttribute(ATTR_FOCUSED).toBoolean()
      val isSelected = elem.getAttribute(ATTR_SELECTED).toBoolean()
      val isScrollable = elem.getAttribute(ATTR_SCROLLABLE).toBoolean()
      val isCheckable = elem.getAttribute(ATTR_CHECKABLE).toBoolean()
      val isChecked = elem.getAttribute(ATTR_CHECKED).toBoolean()
      val isLongClickable = elem.getAttribute(ATTR_LONG_CLICKABLE).toBoolean()

      // Parse children
      val children = mutableListOf<UINode>()
      val childNodes = elem.childNodes
      for (i in 0 until childNodes.length) {
        val child = childNodes.item(i)
        if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == TAG_NODE) {
          children.add(parseRecursive(child as Element))
        }
      }

      // Report first package name found
      if (totalCount == 1) {
        onNodeParsed(0, packageName)
      }

      return UINode(
        className = className,
        text = text,
        resourceId = resourceId,
        contentDesc = contentDesc,
        bounds = bounds,
        isClickable = isClickable,
        isEnabled = isEnabled,
        isFocused = isFocused,
        isSelected = isSelected,
        isScrollable = isScrollable,
        isCheckable = isCheckable,
        isChecked = isChecked,
        isLongClickable = isLongClickable,
        children = children,
        index = index,
        packageName = packageName,
      )
    }

    val result = parseRecursive(element)
    onNodeParsed(totalCount, null)
    return result
  }

  /**
   * Extension function to safely convert string to boolean.
   * Returns true only if the string is exactly "true" (case-insensitive).
   */
  private fun String.toBoolean(): Boolean =
    this.equals("true", ignoreCase = true)

  /**
   * Extension function to convert string to boolean with default value.
   */
  private fun String.toBooleanOrDefault(default: Boolean): Boolean =
    if (this.isEmpty()) default else this.equals("true", ignoreCase = true)

  companion object {
    // XML tag names
    private const val TAG_HIERARCHY = "hierarchy"
    private const val TAG_NODE = "node"

    // XML attribute names
    private const val ATTR_ROTATION = "rotation"
    private const val ATTR_INDEX = "index"
    private const val ATTR_TEXT = "text"
    private const val ATTR_RESOURCE_ID = "resource-id"
    private const val ATTR_CLASS = "class"
    private const val ATTR_PACKAGE = "package"
    private const val ATTR_CONTENT_DESC = "content-desc"
    private const val ATTR_CHECKABLE = "checkable"
    private const val ATTR_CHECKED = "checked"
    private const val ATTR_CLICKABLE = "clickable"
    private const val ATTR_ENABLED = "enabled"
    private const val ATTR_FOCUSABLE = "focusable"
    private const val ATTR_FOCUSED = "focused"
    private const val ATTR_SCROLLABLE = "scrollable"
    private const val ATTR_LONG_CLICKABLE = "long-clickable"
    private const val ATTR_PASSWORD = "password"
    private const val ATTR_SELECTED = "selected"
    private const val ATTR_BOUNDS = "bounds"

    // Default values
    private const val DEFAULT_CLASS_NAME = "android.view.View"
  }
}
