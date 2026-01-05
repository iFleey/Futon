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
package me.fleey.futon.ui.designsystem.component.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons

/**
 * Material 3 Docked Search Bar component following M3 Search specifications.
 * *
 * @param query Current search query text
 * @param onQueryChange Callback when query text changes
 * @param expanded Whether the search bar is in expanded state
 * @param onExpandedChange Callback when expanded state changes
 * @param modifier Modifier for the component
 * @param placeholderRes String resource ID for placeholder text
 * @param onSearch Callback when search action is triggered (IME search)
 * @param content Optional content to show when expanded (e.g., filter chips, suggestions)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3DockedSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  @StringRes placeholderRes: Int = R.string.local_model_search_placeholder,
  onSearch: ((String) -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit = {},
) {
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val interactionSource = remember { MutableInteractionSource() }

  // Animate corner radius based on expanded state
  val cornerRadius by animateDpAsState(
    targetValue = if (expanded) M3SearchBarExpandedCornerRadius else M3SearchBarCornerRadius,
    animationSpec = tween(durationMillis = 200),
    label = "SearchBarCornerRadius",
  )

  Column(modifier = modifier.fillMaxWidth()) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(M3SearchBarHeight),
      shape = RoundedCornerShape(cornerRadius),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 3.dp,
    ) {
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester)
          .onFocusChanged { focusState ->
            if (focusState.isFocused && !expanded) {
              onExpandedChange(true)
            }
          },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
          color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
          imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
          onSearch = {
            onSearch?.invoke(query)
            focusManager.clearFocus()
          },
        ),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
          TextFieldDefaults.DecorationBox(
            value = query,
            innerTextField = innerTextField,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            placeholder = {
              Text(
                text = stringResource(placeholderRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            },
            leadingIcon = {
              Icon(
                imageVector = FutonIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            },
            trailingIcon = {
              if (query.isNotEmpty()) {
                IconButton(
                  onClick = { onQueryChange("") },
                ) {
                  Icon(
                    imageVector = FutonIcons.Close,
                    contentDescription = stringResource(R.string.content_description_clear_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            },
            colors = TextFieldDefaults.colors(
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
              disabledContainerColor = Color.Transparent,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              disabledIndicatorColor = Color.Transparent,
              cursorColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(
              start = 0.dp,
              end = 0.dp,
              top = 0.dp,
              bottom = 0.dp,
            ),
          )
        },
      )
    }

    // Expandable content area (for filters, suggestions, etc.)
    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically(
        animationSpec = tween(durationMillis = 200),
      ) + fadeIn(
        animationSpec = tween(durationMillis = 150),
      ),
      exit = shrinkVertically(
        animationSpec = tween(durationMillis = 200),
      ) + fadeOut(
        animationSpec = tween(durationMillis = 150),
      ),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
      ) {
        content()
      }
    }
  }
}

// Material 3 Search Bar specifications
private val M3SearchBarHeight = 56.dp
private val M3SearchBarCornerRadius = 28.dp
private val M3SearchBarExpandedCornerRadius = 28.dp
