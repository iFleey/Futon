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
package me.fleey.futon.ui.designsystem.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.inputs.FutonInput
import me.fleey.futon.ui.designsystem.component.inputs.FutonNumberInput
import me.fleey.futon.ui.designsystem.component.inputs.FutonPasswordInput
import me.fleey.futon.ui.designsystem.component.selection.FutonRadioButton
import me.fleey.futon.ui.designsystem.component.selection.FutonSlider
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Data class representing a radio option for SettingsRadioGroup.
 *
 * @param T The type of the option value
 * @param value The actual value of the option
 * @param label The display label for the option
 * @param description Optional description text displayed below the label
 */
data class RadioOption<T>(
  val value: T,
  val label: String,
  val description: String? = null,
)

interface SettingsGroupScope {
  fun item(content: @Composable () -> Unit)
}

private class SettingsGroupScopeImpl : SettingsGroupScope {
  val items = mutableListOf<@Composable () -> Unit>()

  override fun item(content: @Composable () -> Unit) {
    items.add(content)
  }
}

/**
 * Settings group component.
 * Each item is an independent card with 2dp gap between them.
 * First card has large top corners, last card has large bottom corners.
 */
@Composable
fun SettingsGroup(
  modifier: Modifier = Modifier,
  title: String? = null,
  content: SettingsGroupScope.() -> Unit,
) {
  val scope = SettingsGroupScopeImpl()
  scope.content()

  val items = scope.items

  Column(modifier = modifier.fillMaxWidth()) {
    if (title != null) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textMuted,
        modifier = Modifier.padding(
          top = 4.dp,
          bottom = 8.dp,
        ),
      )
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      items.forEachIndexed { index, itemContent ->
        val shape = when {
          items.size == 1 -> RoundedCornerShape(16.dp)
          index == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
          )

          index == items.lastIndex -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp,
          )

          else -> RoundedCornerShape(4.dp)
        }
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = FutonTheme.colors.backgroundSecondary,
          shape = shape,
        ) {
          itemContent()
        }
      }
    }
  }
}

/**
 * Settings section component for grouping related settings.
 * Legacy component - prefer SettingsGroup for new implementations.
 *
 * @param title Section title
 * @param modifier Modifier for the section
 * @param content Section content (settings items)
 */
@Composable
fun SettingsSection(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = FutonTheme.colors.textMuted,
      modifier = Modifier.padding(
        start = FutonSizes.ListItemHorizontalPadding,
        bottom = 8.dp,
      ),
    )
    Surface(
      color = FutonTheme.colors.backgroundSecondary,
      shape = MaterialTheme.shapes.medium,
    ) {
      Column(content = content)
    }
  }
}

/**
 * Basic settings item component.
 *
 * @param title Item title
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 * @param onClick Optional click handler
 * @param trailing Optional trailing content
 */
@Composable
fun SettingsItem(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  onClick: (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    onClick = onClick ?: {},
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
      trailing?.invoke()
    }
  }
}

/**
 * Toggle settings item with switch.
 *
 * @param title Item title
 * @param checked Whether the switch is checked
 * @param onCheckedChange Switch state change handler
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 * @param enabled Whether the switch is enabled
 */
@Composable
fun SettingsSwitchItem(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  enabled: Boolean = true,
) {
  SettingsItem(
    title = title,
    description = description,
    leadingIcon = leadingIcon,
    modifier = modifier,
    trailing = {
      FutonSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
      )
    },
  )
}

/**
 * Radio group settings component for single selection.
 *
 * Options are displayed in a vertical list with optional descriptions.
 *
 * @param T The type of the option values
 * @param title Group title displayed above the options
 * @param options List of radio options to display
 * @param selectedValue Currently selected value
 * @param onValueChange Callback when selection changes
 * @param modifier Modifier for the component
 */
@Composable
fun <T> SettingsRadioGroup(
  title: String,
  options: List<RadioOption<T>>,
  selectedValue: T,
  onValueChange: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = FutonTheme.colors.textMuted,
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = 8.dp,
      ),
    )
    Surface(
      color = FutonTheme.colors.backgroundSecondary,
      shape = MaterialTheme.shapes.medium,
    ) {
      Column {
        options.forEachIndexed { index, option ->
          Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onValueChange(option.value) },
            color = FutonTheme.colors.backgroundSecondary,
          ) {
            Row(
              modifier = Modifier.padding(
                horizontal = FutonSizes.ListItemHorizontalPadding,
                vertical = FutonSizes.ListItemVerticalPadding,
              ),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              FutonRadioButton(
                selected = selectedValue == option.value,
                onClick = { onValueChange(option.value) },
              )
              Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = option.label,
                  style = MaterialTheme.typography.bodyMedium,
                )
                option.description?.let {
                  Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = FutonTheme.colors.textMuted,
                  )
                }
              }
            }
          }
          // Add subtle divider between options (except after last)
          if (index < options.lastIndex) {
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FutonSizes.ListItemHorizontalPadding)
                .height(0.5.dp),
              color = FutonTheme.colors.interactiveMuted,
            ) {}
          }
        }
      }
    }
  }
}

/**
 * Slider settings item for range values.
 *
 * Uses FutonSlider (Material 3 based) with Material You dynamic color.
 * Supports both continuous and discrete (stepped) modes.
 *
 * @param title Item title
 * @param value Current slider value
 * @param onValueChange Value change handler
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 * @param valueRange Range of valid values
 * @param steps Number of discrete steps (0 for continuous mode)
 * @param valueFormatter Function to format the current value for display
 * @param minLabel Optional label for minimum value (displayed below slider)
 * @param maxLabel Optional label for maximum value (displayed below slider)
 */
@Composable
fun SettingsSliderItem(
  title: String,
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  valueFormatter: (Float) -> String = { "%.0f".format(it) },
  minLabel: String? = null,
  maxLabel: String? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = 12.dp,
      ),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
      Text(
        text = valueFormatter(value),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    FutonSlider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      steps = steps,
    )
    if (minLabel != null || maxLabel != null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = minLabel ?: "",
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
        Text(
          text = maxLabel ?: "",
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
        )
      }
    }
  }
}

/**
 * Text input settings item.
 *
 * @param title Item title
 * @param value Current input value
 * @param onValueChange Value change handler
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 * @param placeholder Optional placeholder text
 * @param isPassword Whether to use password input
 * @param error Optional error message
 * @param multiline Whether to allow multiple lines of input
 * @param maxLines Maximum number of lines (only used when multiline is true)
 */
@Composable
fun SettingsInputItem(
  title: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  placeholder: String? = null,
  isPassword: Boolean = false,
  error: String? = null,
  multiline: Boolean = false,
  maxLines: Int = if (multiline) 10 else 1,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (isPassword) {
      FutonPasswordInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        error = error,
      )
    } else {
      FutonInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        error = error,
        singleLine = !multiline,
        maxLines = maxLines,
      )
    }
  }
}

/**
 * Number input settings item.
 *
 * @param title Item title
 * @param value Current integer value
 * @param onValueChange Value change handler
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 * @param min Minimum allowed value
 * @param max Maximum allowed value
 * @param error Optional error message
 */
@Composable
fun SettingsNumberItem(
  title: String,
  value: Int,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  min: Int = Int.MIN_VALUE,
  max: Int = Int.MAX_VALUE,
  error: String? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    FutonNumberInput(
      value = value,
      onValueChange = onValueChange,
      min = min,
      max = max,
      error = error,
    )
  }
}


/**
 * Radio selection settings item with inline options.
 *
 * @param T The type of the option values
 * @param title Item title
 * @param options List of label-value pairs for the radio options
 * @param selectedOption Currently selected option value
 * @param onOptionSelected Callback when an option is selected
 * @param modifier Modifier for the item
 * @param description Optional item description
 * @param leadingIcon Optional leading icon
 */
@Composable
fun <T> SettingsRadioItem(
  title: String,
  options: List<Pair<String, T>>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal,
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Column {
      options.forEach { (label, value) ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          onClick = { onOptionSelected(value) },
          color = FutonTheme.colors.backgroundSecondary,
        ) {
          Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FutonRadioButton(
              selected = selectedOption == value,
              onClick = { onOptionSelected(value) },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }
  }
}


/**
 * Single radio selection item for use within a SettingsSection.
 * This is a standalone radio item that can be used individually.
 *
 * @param title Item title
 * @param description Optional item description
 * @param selected Whether this item is selected
 * @param onClick Click handler
 * @param modifier Modifier for the item
 * @param leadingIcon Optional leading icon
 * @param enabled Whether the item is enabled
 * @param recommended Whether to show a "recommended" badge
 */
@Composable
fun SettingsRadioItem(
  title: String,
  description: String? = null,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
  enabled: Boolean = true,
  recommended: Boolean = false,
) {
  val contentAlpha = if (enabled) 1f else 0.5f

  Surface(
    modifier = modifier.fillMaxWidth(),
    onClick = { if (enabled) onClick() },
    color = FutonTheme.colors.backgroundSecondary,
    enabled = enabled,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal.copy(alpha = contentAlpha),
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal.copy(alpha = contentAlpha),
          )
          if (recommended) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              color = FutonTheme.colors.statusPositive.copy(alpha = 0.2f),
              shape = MaterialTheme.shapes.small,
            ) {
              Text(
                text = stringResource(R.string.local_model_recommended),
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusPositive,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted.copy(alpha = contentAlpha),
          )
        }
      }
      FutonRadioButton(
        selected = selected,
        onClick = { if (enabled) onClick() },
        enabled = enabled,
      )
    }
  }
}

/**
 * Navigation settings item with chevron indicator.
 * Used for navigating to sub-screens.
 */
@Composable
fun SettingsNavigationItem(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  leadingIcon: ImageVector? = null,
  enabled: Boolean = true,
) {
  val contentAlpha = if (enabled) 1f else 0.5f

  Surface(
    modifier = modifier.fillMaxWidth(),
    onClick = { if (enabled) onClick() },
    color = FutonTheme.colors.backgroundSecondary,
    enabled = enabled,
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = FutonSizes.ListItemHorizontalPadding,
        vertical = FutonSizes.ListItemVerticalPadding,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      leadingIcon?.let {
        Icon(
          imageVector = it,
          contentDescription = null,
          tint = FutonTheme.colors.interactiveNormal.copy(alpha = contentAlpha),
          modifier = Modifier.size(FutonSizes.IconSize),
        )
        Spacer(modifier = Modifier.width(FutonSizes.ListItemIconSpacing))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal.copy(alpha = contentAlpha),
        )
        description?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted.copy(alpha = contentAlpha),
          )
        }
      }
      Icon(
        imageVector = FutonIcons.ChevronRight,
        contentDescription = null,
        tint = FutonTheme.colors.textMuted.copy(alpha = contentAlpha),
        modifier = Modifier.size(FutonSizes.IconSize),
      )
    }
  }
}
