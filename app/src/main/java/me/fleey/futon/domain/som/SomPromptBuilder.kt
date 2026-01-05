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
package me.fleey.futon.domain.som

import me.fleey.futon.domain.som.models.SomAnnotation
import me.fleey.futon.domain.som.models.SomElement
import me.fleey.futon.domain.som.models.SomElementType
import org.koin.core.annotation.Single

interface SomPromptBuilder {
  fun buildSystemPrompt(): String

  fun buildUserMessage(
    task: String,
    annotation: SomAnnotation,
    actionHistory: List<String> = emptyList(),
    appContext: String? = null,
  ): String

  /**
   * Build element list for prompt context.
   */
  fun buildElementList(annotation: SomAnnotation): String
}

@Single(binds = [SomPromptBuilder::class])
class SomPromptBuilderImpl : SomPromptBuilder {

  override fun buildSystemPrompt(): String = SOM_SYSTEM_PROMPT

  override fun buildUserMessage(
    task: String,
    annotation: SomAnnotation,
    actionHistory: List<String>,
    appContext: String?,
  ): String = buildString {
    appendLine("## Task")
    appendLine(task)
    appendLine()

    if (!appContext.isNullOrBlank()) {
      appendLine("## App Context")
      appendLine(appContext)
      appendLine()
    }

    appendLine("## Screen Elements")
    appendLine("The screenshot shows numbered markers [1], [2], [3]... on detected UI elements.")
    appendLine("Screen size: ${annotation.screenWidth}x${annotation.screenHeight}")
    appendLine()
    appendLine(buildElementList(annotation))

    if (actionHistory.isNotEmpty()) {
      appendLine()
      appendLine("## Previous Actions")
      actionHistory.takeLast(5).forEachIndexed { index, action ->
        appendLine("${index + 1}. $action")
      }
      appendLine()
      appendLine("IMPORTANT: Avoid repeating failed patterns. If previous taps didn't work, try a different element.")
    }

    appendLine()
    appendLine("## Instructions")
    appendLine("1. Analyze the screenshot and element list")
    appendLine("2. Identify the element to interact with by its [id]")
    appendLine("3. Return the action in JSON format")
  }

  override fun buildElementList(annotation: SomAnnotation): String = buildString {
    val clickable = annotation.getClickableElements()
    val textElements = annotation.getElementsByType(SomElementType.TEXT)

    appendLine("### Clickable Elements (${clickable.size})")
    clickable.forEach { element ->
      appendLine(formatElement(element))
    }

    if (textElements.isNotEmpty()) {
      appendLine()
      appendLine("### Text Labels (${textElements.size})")
      textElements.filter { !it.isClickable }.forEach { element ->
        appendLine(formatElement(element))
      }
    }
  }

  private fun formatElement(element: SomElement): String = buildString {
    append("[${element.id}] ${element.type.label}")
    if (!element.text.isNullOrBlank()) {
      val truncatedText = element.text.take(50).let {
        if (element.text.length > 50) "$it..." else it
      }
      append(": \"$truncatedText\"")
    }
    append(" @(${element.centerX},${element.centerY})")
    if (element.confidence < 0.8f) {
      append(" [conf=${String.format("%.0f%%", element.confidence * 100)}]")
    }
  }

  companion object {
    private val SOM_SYSTEM_PROMPT =
      """You are Futon, an Android automation agent using Set-of-Mark (SoM) visual grounding.

## How SoM Works
- The screenshot has numbered markers [1], [2], [3]... overlaid on detected UI elements
- Each marker corresponds to an element in the element list
- Use the element [id] to specify which element to interact with
- This is MORE ACCURATE than raw coordinates

## Output Format
You MUST respond in this exact format:
<think>{reasoning}</think><answer>{action_json}</answer>

Where:
- {reasoning}: Brief explanation of your decision (1-2 sentences)
- {action_json}: A single valid JSON object with the action to execute

## Actions

### Element-based actions (PREFERRED - use element_id)
| Action | Format |
|--------|--------|
| tap | {"action":"tap","element_id":5,"taskComplete":false} |
| long_press | {"action":"long_press","element_id":3,"parameters":{"duration":500},"taskComplete":false} |
| input | {"action":"input","element_id":2,"parameters":{"text":"hello"},"taskComplete":false} |

### Coordinate-based actions (fallback when element not detected)
| Action | Format |
|--------|--------|
| tap_coordinate | {"action":"tap_coordinate","parameters":{"x":540,"y":1200},"taskComplete":false} |
| swipe | {"action":"swipe","parameters":{"x1":540,"y1":1500,"x2":540,"y2":500},"taskComplete":false} |
| scroll | {"action":"scroll","parameters":{"direction":"down","distance":500},"taskComplete":false} |

### System actions
| Action | Format |
|--------|--------|
| back | {"action":"back","taskComplete":false} |
| home | {"action":"home","taskComplete":false} |
| launch_app | {"action":"launch_app","parameters":{"package":"com.example"},"taskComplete":false} |
| wait | {"action":"wait","parameters":{"duration":1000},"taskComplete":false} |

### Completion
| Action | Format |
|--------|--------|
| complete | {"action":"complete","parameters":{"message":"done"},"taskComplete":true} |
| error | {"action":"error","parameters":{"message":"reason"},"taskComplete":true} |

## Decision Priority
1. **Element-based tap** - Use [id] when the target element is detected (most reliable)
2. **launch_app** - For opening apps, use package name directly
3. **Coordinate tap** - Only when target is visible but not in element list
4. **Scroll** - When target might be off-screen

## Response Examples

**Task: "Click the Settings button"**
Elements: [3] button: "Settings" @(540,800)
<think>The task is to click Settings. Element [3] is a button labeled "Settings".</think><answer>{"action":"tap","element_id":3,"taskComplete":false}</answer>

**Task: "Open Chrome"**
<think>Need to launch Chrome app directly</think><answer>{"action":"launch_app","parameters":{"package":"com.android.chrome"},"taskComplete":false}</answer>

**Task: "Type hello in search"**
Elements: [5] input: "Search" @(540,200)
<think>Element [5] is the search input field, will type hello</think><answer>{"action":"input","element_id":5,"parameters":{"text":"hello"},"taskComplete":false}</answer>

## Critical Rules
1. Output ONLY the <think>...</think><answer>...</answer> format
2. JSON must be valid - use double quotes, no trailing commas
3. ALWAYS prefer element_id over raw coordinates when element is detected
4. Set taskComplete=true only when the task is fully done
5. If stuck, try scrolling or going back
6. Never repeat failed actions - try a different element""".trimIndent()
  }
}
