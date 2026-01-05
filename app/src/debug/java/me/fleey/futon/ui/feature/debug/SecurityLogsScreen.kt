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
package me.fleey.futon.ui.feature.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.daemon.SecurityLogEntry
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityLogsScreen(
  onBack: () -> Unit,
  viewModel: SecurityLogsViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  var showClearDialog by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.message) {
    uiState.message?.let { message ->
      snackbarHostState.showSnackbar(context.getString(message))
      viewModel.onEvent(SecurityLogsUiEvent.DismissMessage)
    }
  }

  if (showClearDialog) {
    AlertDialog(
      onDismissRequest = { showClearDialog = false },
      title = { Text(stringResource(R.string.debug_clear_logs_title)) },
      text = { Text(stringResource(R.string.debug_clear_logs_message)) },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.onEvent(SecurityLogsUiEvent.ClearLogs)
            showClearDialog = false
          },
        ) {
          Text(stringResource(R.string.action_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.debug_security_logs_title),
        onBackClick = onBack,
        actions = {
          IconButton(onClick = { viewModel.onEvent(SecurityLogsUiEvent.Refresh) }) {
            Icon(
              imageVector = FutonIcons.Refresh,
              contentDescription = stringResource(R.string.action_retry),
              tint = FutonTheme.colors.textMuted,
            )
          }
          IconButton(onClick = { showClearDialog = true }) {
            Icon(
              imageVector = FutonIcons.Clear,
              contentDescription = stringResource(R.string.action_clear),
              tint = FutonTheme.colors.textMuted,
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      SearchBar(
        query = uiState.searchQuery,
        onQueryChange = { viewModel.onEvent(SecurityLogsUiEvent.UpdateSearchQuery(it)) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      )

      FilterChipsRow(
        selectedFilters = uiState.selectedFilters,
        onFilterToggle = { viewModel.onEvent(SecurityLogsUiEvent.ToggleFilter(it)) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      )

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.debug_log_count, uiState.filteredLogs.size),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
        )
        if (uiState.isLoading) {
          Text(
            text = stringResource(R.string.debug_loading),
            style = MaterialTheme.typography.labelMedium,
            color = FutonTheme.colors.textMuted,
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      if (uiState.filteredLogs.isEmpty()) {
        EmptyLogsState(
          hasFilters = uiState.searchQuery.isNotEmpty() || uiState.selectedFilters.isNotEmpty(),
        )
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(
            items = uiState.filteredLogs,
            key = { "${it.timestamp}_${it.eventType.code}" },
          ) { entry ->
            SecurityLogItem(entry = entry)
          }
          item {
            Spacer(modifier = Modifier.height(16.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun SearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = modifier,
    placeholder = {
      Text(
        text = stringResource(R.string.debug_search_logs),
        color = FutonTheme.colors.textMuted,
      )
    },
    leadingIcon = {
      Icon(
        imageVector = FutonIcons.Search,
        contentDescription = null,
        tint = FutonTheme.colors.textMuted,
      )
    },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }) {
          Icon(
            imageVector = FutonIcons.Close,
            contentDescription = stringResource(R.string.action_clear),
            tint = FutonTheme.colors.textMuted,
          )
        }
      }
    },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions.Default,
    shape = RoundedCornerShape(12.dp),
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = MaterialTheme.colorScheme.primary,
      unfocusedBorderColor = FutonTheme.colors.interactiveMuted,
      focusedContainerColor = FutonTheme.colors.backgroundSecondary,
      unfocusedContainerColor = FutonTheme.colors.backgroundSecondary,
    ),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsRow(
  selectedFilters: Set<SecurityEventCategory>,
  onFilterToggle: (SecurityEventCategory) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SecurityEventCategory.entries.forEach { category ->
      val isSelected = category in selectedFilters
      FilterChip(
        selected = isSelected,
        onClick = { onFilterToggle(category) },
        label = {
          Text(
            text = category.displayName,
            style = MaterialTheme.typography.labelMedium,
          )
        },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = category.color.copy(alpha = 0.2f),
          selectedLabelColor = category.color,
        ),
      )
    }
  }
}

@Composable
private fun SecurityLogItem(
  entry: SecurityLogEntry,
) {
  val category = SecurityEventCategory.fromEventType(entry.eventType)
  val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = FutonTheme.colors.backgroundSecondary,
    ),
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(category.color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = entry.eventType.code,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = category.color,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = dateFormat.format(Date(entry.timestamp)),
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
          fontFamily = FontFamily.Monospace,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = entry.details,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textNormal,
        fontFamily = FontFamily.Monospace,
      )

      Spacer(modifier = Modifier.height(4.dp))

      Row {
        Text(
          text = "uid=${entry.uid}",
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
          fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "pid=${entry.pid}",
          style = MaterialTheme.typography.labelSmall,
          color = FutonTheme.colors.textMuted,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun EmptyLogsState(
  hasFilters: Boolean,
) {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        imageVector = FutonIcons.Security,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = FutonTheme.colors.textMuted.copy(alpha = 0.5f),
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = if (hasFilters) {
          stringResource(R.string.debug_no_matching_logs)
        } else {
          stringResource(R.string.debug_no_logs)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}
