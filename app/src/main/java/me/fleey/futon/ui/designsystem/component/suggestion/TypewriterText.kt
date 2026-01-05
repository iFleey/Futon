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
package me.fleey.futon.ui.designsystem.component.suggestion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TypewriterText(
  texts: List<Int>,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.headlineLarge,
  color: Color = MaterialTheme.colorScheme.onSurface,
  typingSpeed: Long = 50L,
  deletingSpeed: Long = 30L,
  pauseDuration: Long = 2000L,
  showCursor: Boolean = true,
  cursorChar: String = "●",
) {
  val resolvedTexts = texts.map { stringResource(it) }

  TypewriterTextContent(
    texts = resolvedTexts,
    modifier = modifier,
    style = style,
    color = color,
    typingSpeed = typingSpeed,
    deletingSpeed = deletingSpeed,
    pauseDuration = pauseDuration,
    showCursor = showCursor,
    cursorChar = cursorChar,
  )
}

@Composable
fun TypewriterText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.headlineLarge,
  color: Color = MaterialTheme.colorScheme.onSurface,
  typingSpeed: Long = 50L,
  showCursor: Boolean = true,
  cursorChar: String = "●",
  onComplete: (() -> Unit)? = null,
) {
  var displayedText by remember { mutableStateOf("") }
  var isComplete by remember { mutableStateOf(false) }

  LaunchedEffect(text) {
    displayedText = ""
    isComplete = false
    text.forEachIndexed { index, _ ->
      delay(typingSpeed)
      displayedText = text.substring(0, index + 1)
    }
    isComplete = true
    onComplete?.invoke()
  }

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = displayedText,
      style = style,
      color = color,
    )
    if (showCursor) {
      BlinkingCursor(
        cursorChar = cursorChar,
        style = style,
        color = color,
        visible = !isComplete,
      )
    }
  }
}

@Composable
private fun TypewriterTextContent(
  texts: List<String>,
  modifier: Modifier,
  style: TextStyle,
  color: Color,
  typingSpeed: Long,
  deletingSpeed: Long,
  pauseDuration: Long,
  showCursor: Boolean,
  cursorChar: String,
) {
  if (texts.isEmpty()) return

  var currentIndex by remember { mutableIntStateOf(0) }
  var displayedText by remember { mutableStateOf("") }
  var isTyping by remember { mutableStateOf(true) }

  LaunchedEffect(texts) {
    while (true) {
      val currentText = texts[currentIndex]

      isTyping = true
      currentText.forEachIndexed { index, _ ->
        delay(typingSpeed)
        displayedText = currentText.substring(0, index + 1)
      }

      delay(pauseDuration)

      isTyping = false
      for (i in currentText.length downTo 0) {
        delay(deletingSpeed)
        displayedText = currentText.substring(0, i)
      }

      delay(300)
      currentIndex = (currentIndex + 1) % texts.size
    }
  }

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = displayedText,
      style = style,
      color = color,
    )
    if (showCursor) {
      BlinkingCursor(
        cursorChar = cursorChar,
        style = style,
        color = color,
      )
    }
  }
}

@Composable
private fun BlinkingCursor(
  cursorChar: String,
  style: TextStyle,
  color: Color,
  visible: Boolean = true,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "cursor")
  val alpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(500, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "cursorAlpha",
  )

  Text(
    text = cursorChar,
    style = style.copy(fontWeight = FontWeight.Bold),
    color = color,
    modifier = Modifier
      .alpha(if (visible) alpha else 0f)
      .offset(x = 2.dp),
  )
}
