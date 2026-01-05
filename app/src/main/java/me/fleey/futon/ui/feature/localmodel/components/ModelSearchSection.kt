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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.localmodel.download.DownloadSource
import me.fleey.futon.data.localmodel.registry.SortOption
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.M3DockedSearchBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme

/**
 * Container component that integrates search, filter, and download source selection.
 *
 * @param searchQuery Current search query text
 * @param onSearchQueryChange Callback when search query changes
 * @param isSearchExpanded Whether the search bar is expanded
 * @param onSearchExpandedChange Callback when search expanded state changes
 * @param downloadSource Current download source
 * @param onDownloadSourceChange Callback when download source changes
 * @param isDownloading Whether a download is in progress (disables source switching)
 * @param modelTypeFilter Current model type filter (true=VLM, false=LLM, null=all)
 * @param ramFilter Current RAM filter in MB (null=no filter)
 * @param sortOption Current sort option
 * @param onModelTypeFilterChange Callback when model type filter changes
 * @param onRamFilterChange Callback when RAM filter changes
 * @param onSortOptionChange Callback when sort option changes
 * @param catalogSource Source of the current catalog data
 * @param isLoadingCatalog Whether the catalog is being loaded
 * @param modifier Modifier for the component
 */
@Composable
fun ModelSearchSection(
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  isSearchExpanded: Boolean,
  onSearchExpandedChange: (Boolean) -> Unit,
  downloadSource: DownloadSource,
  onDownloadSourceChange: (DownloadSource) -> Unit,
  isDownloading: Boolean,
  modelTypeFilter: Boolean?,
  ramFilter: Int?,
  sortOption: SortOption,
  onModelTypeFilterChange: (Boolean?) -> Unit,
  onRamFilterChange: (Int?) -> Unit,
  onSortOptionChange: (SortOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsGroup(
    title = stringResource(R.string.local_model_search_filter),
    modifier = modifier,
  ) {
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Header row: Source selector + Catalog source indicator
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IntegratedSourceSelector(
            selectedSource = downloadSource,
            onSourceSelected = onDownloadSourceChange,
            enabled = !isDownloading,
          )

        }

        M3DockedSearchBar(
          query = searchQuery,
          onQueryChange = onSearchQueryChange,
          expanded = isSearchExpanded,
          onExpandedChange = onSearchExpandedChange,
        )

        FilterChipRow(
          modelTypeFilter = modelTypeFilter,
          ramFilter = ramFilter,
          sortOption = sortOption,
          onModelTypeFilterChange = onModelTypeFilterChange,
          onRamFilterChange = onRamFilterChange,
          onSortOptionChange = onSortOptionChange,
          modifier = Modifier.padding(horizontal = 0.dp),
        )
      }
    }
  }
}

@Composable
private fun CatalogSourceIndicator(
  isLoading: Boolean,
) {
  val (icon, label, color) = when {
    isLoading -> Triple(
      FutonIcons.Refresh,
      stringResource(R.string.local_model_catalog_loading),
      FutonTheme.colors.textMuted,
    )

    else -> Triple(
      FutonIcons.Info,
      stringResource(R.string.local_model_catalog_unknown),
      FutonTheme.colors.textMuted,
    )
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(14.dp),
      tint = color,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
    )
  }
}
