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
package me.fleey.futon.ui.designsystem.component.selection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Switch component based on Material 3 Switch.
 *
 * @param checked Whether the switch is checked
 * @param onCheckedChange Callback when the switch state changes
 * @param modifier Modifier for the switch
 * @param enabled Whether the switch is enabled
 */
@Composable
fun FutonSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    enabled = enabled,
    colors = SwitchDefaults.colors(
      checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
      checkedTrackColor = MaterialTheme.colorScheme.primary,
      checkedBorderColor = MaterialTheme.colorScheme.primary,
      uncheckedThumbColor = FutonTheme.colors.interactiveNormal,
      uncheckedTrackColor = FutonTheme.colors.interactiveMuted,
      uncheckedBorderColor = FutonTheme.colors.interactiveMuted,
    ),
  )
}


/**
 * Radio button component.
 *
 * Displays a filled Blurple circle when selected, empty circle with border when unselected.
 *
 * @param selected Whether the radio button is selected
 * @param onClick Callback when the radio button is clicked
 * @param modifier Modifier for the radio button
 * @param enabled Whether the radio button is enabled
 */
@Composable
fun FutonRadioButton(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val primaryColor = MaterialTheme.colorScheme.primary

  val borderColor by animateColorAsState(
    targetValue = when {
      !enabled -> FutonTheme.colors.interactiveMuted.copy(alpha = 0.5f)
      selected -> primaryColor
      else -> FutonTheme.colors.interactiveMuted
    },
    animationSpec = tween(durationMillis = 150),
    label = "RadioButtonBorderColor",
  )

  val fillColor by animateColorAsState(
    targetValue = when {
      !enabled && selected -> primaryColor.copy(alpha = 0.5f)
      selected -> primaryColor
      else -> Color.Transparent
    },
    animationSpec = tween(durationMillis = 150),
    label = "RadioButtonFillColor",
  )

  Box(
    modifier = modifier
      .size(FutonSizes.RadioButtonSize)
      .clip(CircleShape)
      .border(
        width = 2.dp,
        color = borderColor,
        shape = CircleShape,
      )
      .clickable(
        enabled = enabled,
        onClick = onClick,
        role = Role.RadioButton,
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(bounded = true, radius = FutonSizes.RadioButtonSize / 2),
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Box(
        modifier = Modifier.Companion
          .size(FutonSizes.RadioButtonInnerSize)
          .clip(CircleShape)
          .background(fillColor),
      )
    }
  }
}

/**
 * Checkbox component.
 *
 * Displays Blurple background with white checkmark when checked,
 * transparent background with border when unchecked.
 *
 * @param checked Whether the checkbox is checked
 * @param onCheckedChange Callback when the checkbox state changes
 * @param modifier Modifier for the checkbox
 * @param enabled Whether the checkbox is enabled
 */
@Composable
fun FutonCheckbox(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val primaryColor = MaterialTheme.colorScheme.primary

  val backgroundColor by animateColorAsState(
    targetValue = when {
      !enabled && checked -> primaryColor.copy(alpha = 0.5f)
      checked -> primaryColor
      else -> Color.Transparent
    },
    animationSpec = tween(durationMillis = 150),
    label = "CheckboxBackgroundColor",
  )

  val borderColor by animateColorAsState(
    targetValue = when {
      !enabled -> FutonTheme.colors.interactiveMuted.copy(alpha = 0.5f)
      checked -> primaryColor
      else -> FutonTheme.colors.interactiveMuted
    },
    animationSpec = tween(durationMillis = 150),
    label = "CheckboxBorderColor",
  )

  val checkmarkColor by animateColorAsState(
    targetValue = if (checked) Color.White else Color.Transparent,
    animationSpec = tween(durationMillis = 150),
    label = "CheckboxCheckmarkColor",
  )

  Box(
    modifier = modifier
      .size(FutonSizes.CheckboxSize)
      .clip(FutonShapes.CheckboxShape)
      .background(backgroundColor)
      .border(
        width = 2.dp,
        color = borderColor,
        shape = FutonShapes.CheckboxShape,
      )
      .clickable(
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        role = Role.Checkbox,
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(bounded = true),
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (checked) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = checkmarkColor,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

/**
 * Material 3 based slider component.
 *
 * @param value Current slider value
 * @param onValueChange Callback when the value changes
 * @param modifier Modifier for the slider
 * @param enabled Whether the slider is enabled
 * @param valueRange Range of valid values
 * @param steps Number of discrete steps (0 for continuous mode)
 * @param showLabel Whether to show the current value label
 */
@Composable
fun FutonSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  showLabel: Boolean = false,
) {
  Column(modifier = modifier) {
    if (showLabel) {
      Text(
        text = "%.0f".format(value),
        style = MaterialTheme.typography.labelMedium,
        color = FutonTheme.colors.textMuted,
        modifier = Modifier.align(Alignment.CenterHorizontally),
      )
      Spacer(modifier = Modifier.height(4.dp))
    }

    Slider(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      valueRange = valueRange,
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = FutonTheme.colors.interactiveMuted,
      ),
    )
  }
}
