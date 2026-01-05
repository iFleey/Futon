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
package me.fleey.futon.ui.feature.localmodel.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.registry.SortOption
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.theme.FutonTheme

private val ChipHeight = 32.dp
private val ChipCornerRadius = 8.dp

@Composable
fun FilterChipRow(
  modelTypeFilter: Boolean?,
  ramFilter: Int?,
  sortOption: SortOption,
  onModelTypeFilterChange: (Boolean?) -> Unit,
  onRamFilterChange: (Int?) -> Unit,
  onSortOptionChange: (SortOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ModelTypeFilterChips(
      modelTypeFilter = modelTypeFilter,
      onModelTypeFilterChange = onModelTypeFilterChange,
    )

    FilterDivider()

    RamFilterChips(
      ramFilter = ramFilter,
      onRamFilterChange = onRamFilterChange,
    )

    Spacer(modifier = Modifier.width(8.dp))

    SortDropdownButton(
      sortOption = sortOption,
      onSortOptionChange = onSortOptionChange,
    )
  }
}

@Composable
private fun ModelTypeFilterChips(
  modelTypeFilter: Boolean?,
  onModelTypeFilterChange: (Boolean?) -> Unit,
) {
  val chipShape = RoundedCornerShape(ChipCornerRadius)

  FilterChip(
    selected = modelTypeFilter == null,
    onClick = { onModelTypeFilterChange(null) },
    label = {
      Text(
        text = stringResource(R.string.filter_all),
        style = MaterialTheme.typography.labelLarge,
      )
    },
    modifier = Modifier.height(ChipHeight),
    shape = chipShape,
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
  )

  FilterChip(
    selected = modelTypeFilter == true,
    onClick = {
      onModelTypeFilterChange(if (modelTypeFilter == true) null else true)
    },
    label = {
      Text(
        text = "VLM",
        style = MaterialTheme.typography.labelLarge,
      )
    },
    modifier = Modifier.height(ChipHeight),
    shape = chipShape,
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
  )

  FilterChip(
    selected = modelTypeFilter == false,
    onClick = {
      onModelTypeFilterChange(if (modelTypeFilter == false) null else false)
    },
    label = {
      Text(
        text = "LLM",
        style = MaterialTheme.typography.labelLarge,
      )
    },
    modifier = Modifier.height(ChipHeight),
    shape = chipShape,
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
  )
}

@Composable
private fun RamFilterChips(
  ramFilter: Int?,
  onRamFilterChange: (Int?) -> Unit,
) {
  val chipShape = RoundedCornerShape(ChipCornerRadius)

  val ramOptions = listOf(
    null to R.string.filter_any_ram,
    4096 to R.string.filter_ram_4gb,
    6144 to R.string.filter_ram_6gb,
    8192 to R.string.filter_ram_8gb,
    12288 to R.string.filter_ram_12gb,
  )

  ramOptions.forEach { (ram, labelRes) ->
    FilterChip(
      selected = ramFilter == ram,
      onClick = { onRamFilterChange(if (ramFilter == ram) null else ram) },
      label = {
        Text(
          text = stringResource(labelRes),
          style = MaterialTheme.typography.labelLarge,
        )
      },
      modifier = Modifier.height(ChipHeight),
      shape = chipShape,
      colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
      ),
    )
  }
}

@Composable
private fun FilterDivider() {
  VerticalDivider(
    modifier = Modifier
      .height(20.dp)
      .padding(horizontal = 4.dp),
    thickness = 1.dp,
    color = FutonTheme.colors.interactiveMuted,
  )
}

@Composable
private fun SortDropdownButton(
  sortOption: SortOption,
  onSortOptionChange: (SortOption) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }

  val sortLabelRes = when (sortOption) {
    SortOption.NAME -> R.string.sort_by_name
    SortOption.SIZE -> R.string.sort_by_size
    SortOption.POPULARITY -> R.string.sort_by_popularity
  }

  Box {
    Surface(
      onClick = { expanded = true },
      color = FutonTheme.colors.backgroundTertiary,
      shape = RoundedCornerShape(ChipCornerRadius),
      modifier = Modifier.height(ChipHeight),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = FutonIcons.Filter,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = FutonTheme.colors.textMuted,
        )
        Text(
          text = stringResource(sortLabelRes),
          style = MaterialTheme.typography.labelLarge,
          color = FutonTheme.colors.textNormal,
        )
        Icon(
          imageVector = FutonIcons.ExpandMore,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = FutonTheme.colors.textMuted,
        )
      }
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      SortDropdownMenuItem(
        text = stringResource(R.string.sort_by_name_action),
        isSelected = sortOption == SortOption.NAME,
        onClick = {
          onSortOptionChange(SortOption.NAME)
          expanded = false
        },
      )
      SortDropdownMenuItem(
        text = stringResource(R.string.sort_by_size_action),
        isSelected = sortOption == SortOption.SIZE,
        onClick = {
          onSortOptionChange(SortOption.SIZE)
          expanded = false
        },
      )
      SortDropdownMenuItem(
        text = stringResource(R.string.sort_by_popularity_action),
        isSelected = sortOption == SortOption.POPULARITY,
        onClick = {
          onSortOptionChange(SortOption.POPULARITY)
          expanded = false
        },
      )
    }
  }
}

@Composable
private fun SortDropdownMenuItem(
  text: String,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(text) },
    onClick = onClick,
    leadingIcon = {
      if (isSelected) {
        Icon(
          imageVector = FutonIcons.Check,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    },
  )
}
