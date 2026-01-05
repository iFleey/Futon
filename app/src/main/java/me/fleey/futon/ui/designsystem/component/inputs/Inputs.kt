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
package me.fleey.futon.ui.designsystem.component.inputs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * General text input component.
 *
 * @param value Current input value
 * @param onValueChange Value change handler
 * @param modifier Modifier for the input
 * @param label Optional label text above the input
 * @param placeholder Optional placeholder text
 * @param enabled Whether the input is enabled
 * @param error Optional error message to display
 * @param singleLine Whether to restrict to single line
 * @param maxLines Maximum number of lines
 * @param leadingIcon Optional leading icon
 * @param trailingIcon Optional trailing content
 * @param keyboardOptions Keyboard configuration
 */
@Composable
fun FutonInput(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  enabled: Boolean = true,
  error: String? = null,
  singleLine: Boolean = true,
  maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
  leadingIcon: ImageVector? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
  Column(modifier = modifier) {
    label?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.labelMedium,
        color = if (error != null) MaterialTheme.colorScheme.error else FutonTheme.colors.textMuted,
        modifier = Modifier.padding(bottom = FutonSizes.InputLabelSpacing),
      )
    }
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = placeholder?.let { { Text(it, color = FutonTheme.colors.textMuted) } },
      enabled = enabled,
      isError = error != null,
      singleLine = singleLine,
      maxLines = maxLines,
      leadingIcon = leadingIcon?.let {
        { Icon(it, null, tint = FutonTheme.colors.interactiveNormal) }
      },
      trailingIcon = trailingIcon,
      keyboardOptions = keyboardOptions,
      shape = FutonShapes.InputShape,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = FutonTheme.colors.interactiveMuted,
        focusedContainerColor = FutonTheme.colors.channelTextarea,
        unfocusedContainerColor = FutonTheme.colors.channelTextarea,
      ),
      modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(
          top = FutonSizes.InputErrorSpacing,
          start = FutonSizes.InputErrorSpacing,
        ),
      )
    }
  }
}

/**
 * Password input component with visibility toggle.
 *
 * @param value Current password value
 * @param onValueChange Value change handler
 * @param modifier Modifier for the input
 * @param label Optional label text above the input
 * @param placeholder Optional placeholder text
 * @param enabled Whether the input is enabled
 * @param error Optional error message to display
 */
@Composable
fun FutonPasswordInput(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  enabled: Boolean = true,
  error: String? = null,
) {
  var visible by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    label?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.labelMedium,
        color = if (error != null) MaterialTheme.colorScheme.error else FutonTheme.colors.textMuted,
        modifier = Modifier.padding(bottom = FutonSizes.InputLabelSpacing),
      )
    }
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = placeholder?.let { { Text(it, color = FutonTheme.colors.textMuted) } },
      enabled = enabled,
      isError = error != null,
      singleLine = true,
      visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
      leadingIcon = {
        Icon(Icons.Default.Key, null, tint = FutonTheme.colors.interactiveNormal)
      },
      trailingIcon = {
        IconButton(onClick = { visible = !visible }) {
          Icon(
            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (visible)
              stringResource(R.string.action_hide)
            else
              stringResource(R.string.action_show),
            tint = FutonTheme.colors.interactiveNormal,
          )
        }
      },
      shape = FutonShapes.InputShape,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = FutonTheme.colors.interactiveMuted,
        focusedContainerColor = FutonTheme.colors.channelTextarea,
        unfocusedContainerColor = FutonTheme.colors.channelTextarea,
      ),
      modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(
          top = FutonSizes.InputErrorSpacing,
          start = FutonSizes.InputErrorSpacing,
        ),
      )
    }
  }
}

/**
 * Number input component with min/max value constraints.
 *
 * Values outside the [min, max] range will not trigger onValueChange.
 *
 * @param value Current integer value
 * @param onValueChange Value change handler (only called for valid values in range)
 * @param modifier Modifier for the input
 * @param label Optional label text above the input
 * @param enabled Whether the input is enabled
 * @param error Optional error message to display
 * @param min Minimum allowed value (inclusive)
 * @param max Maximum allowed value (inclusive)
 */
@Composable
fun FutonNumberInput(
  value: Int,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  enabled: Boolean = true,
  error: String? = null,
  min: Int = Int.MIN_VALUE,
  max: Int = Int.MAX_VALUE,
) {
  var text by remember(value) { mutableStateOf(value.toString()) }

  FutonInput(
    value = text,
    onValueChange = { newValue ->
      text = newValue
      newValue.toIntOrNull()?.let { intValue ->
        if (intValue in min..max) {
          onValueChange(intValue)
        }
      }
    },
    label = label,
    enabled = enabled,
    error = error,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = modifier,
  )
}
