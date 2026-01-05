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

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@Composable
fun WelcomeHeader(
  @StringRes titleRes: Int,
  modifier: Modifier = Modifier,
  @StringRes subtitleRes: Int? = null,
  animated: Boolean = true,
) {
  val title = stringResource(titleRes)
  val subtitle = subtitleRes?.let { stringResource(it) }

  WelcomeHeaderContent(
    title = title,
    subtitle = subtitle,
    modifier = modifier,
    animated = animated,
  )
}

@Composable
fun WelcomeHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  animated: Boolean = true,
) {
  WelcomeHeaderContent(
    title = title,
    subtitle = subtitle,
    modifier = modifier,
    animated = animated,
  )
}

@Composable
fun WelcomeHeaderAnimated(
  titles: List<Int>,
  modifier: Modifier = Modifier,
  @StringRes subtitleRes: Int? = null,
) {
  val subtitle = subtitleRes?.let { stringResource(it) }

  Column(
    modifier = modifier.padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    TypewriterText(
      texts = titles,
      style = MaterialTheme.typography.headlineLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
      ),
      color = FutonTheme.colors.textNormal,
      typingSpeed = 100L,
      deletingSpeed = 50L,
      pauseDuration = 3000L,
      cursorChar = "●",
    )

    if (subtitle != null) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = FutonTheme.colors.textMuted,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun WelcomeHeaderContent(
  title: String,
  subtitle: String?,
  modifier: Modifier,
  animated: Boolean,
) {
  var visible by remember { mutableStateOf(!animated) }

  LaunchedEffect(Unit) {
    if (animated) {
      visible = true
    }
  }

  Column(
    modifier = modifier.padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    AnimatedVisibility(
      visible = visible,
      enter = fadeIn(tween(400)) + slideInVertically(
        animationSpec = tween(400),
        initialOffsetY = { -20 },
      ),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge.copy(
          fontWeight = FontWeight.SemiBold,
          fontSize = 28.sp,
          lineHeight = 36.sp,
        ),
        color = FutonTheme.colors.textNormal,
        textAlign = TextAlign.Center,
      )
    }

    if (subtitle != null) {
      Spacer(modifier = Modifier.height(8.dp))
      AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
          animationSpec = tween(400, delayMillis = 100),
          initialOffsetY = { -10 },
        ),
      ) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyLarge,
          color = FutonTheme.colors.textMuted,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
