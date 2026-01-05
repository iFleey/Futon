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
package me.fleey.futon.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.TaskHistoryItem
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.datetime.formatRelativeTime
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

enum class DrawerDestination(
  val icon: ImageVector,
  val labelResId: Int,
) {
  NEW_CONVERSATION(
    icon = Icons.Outlined.Add,
    labelResId = R.string.content_description_new_conversation,
  ),
  HISTORY(
    icon = Icons.Outlined.History,
    labelResId = R.string.nav_history,
  ),
  SETTINGS(
    icon = Icons.Outlined.Settings,
    labelResId = R.string.nav_settings,
  )
}

@Composable
fun FutonDrawerContent(
  selectedDestination: DrawerDestination?,
  onNavigateToHistory: () -> Unit,
  onNavigateToSettings: () -> Unit,
  onNewConversation: () -> Unit,
  onConversationClick: (TaskHistoryItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DrawerViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

  BackHandler(enabled = isSearchExpanded) {
    isSearchExpanded = false
    viewModel.clearSearch()
  }

  ModalDrawerSheet(
    modifier = modifier
      .widthIn(max = 360.dp)
      .fillMaxWidth(0.85f),
    drawerContainerColor = FutonTheme.colors.backgroundSecondary,
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxHeight()
        .statusBarsPadding()
        .navigationBarsPadding()
        .imePadding(),
      contentPadding = PaddingValues(vertical = 24.dp),
    ) {
      item("search_header") {
        DockedSearchBar(
          query = uiState.searchQuery,
          onQueryChange = viewModel::onSearchQueryChange,
          isExpanded = isSearchExpanded,
          onExpandedChange = { expanded ->
            isSearchExpanded = expanded
            if (!expanded) viewModel.clearSearch()
          },
          results = uiState.searchResults,
          onResultClick = { item ->
            isSearchExpanded = false
            viewModel.clearSearch()
            onConversationClick(item)
          },
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
      }

      item(key = "nav_section") {
        DrawerNavSection(
          selectedDestination = selectedDestination,
          onNewConversation = onNewConversation,
          onNavigateToHistory = onNavigateToHistory,
          onNavigateToSettings = onNavigateToSettings,
          modifier = Modifier.padding(horizontal = 12.dp),
        )
      }

      item(key = "recent_title") {
        Text(
          text = stringResource(R.string.drawer_recent_conversations),
          style = MaterialTheme.typography.labelMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
      }

      if (uiState.recentConversations.isEmpty()) {
        item(key = "empty_state") {
          EmptyConversationState()
        }
      } else {
        itemsIndexed(
          items = uiState.recentConversations,
          key = { _, item -> item.id },
        ) { index, item ->
          val shape = when {
            uiState.recentConversations.size == 1 -> RoundedCornerShape(16.dp)
            index == 0 -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
            index == uiState.recentConversations.lastIndex -> RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)
            else -> RoundedCornerShape(4.dp)
          }

          ConversationItemContent(
            item = item,
            onClick = { onConversationClick(item) },
            shape = shape,
            modifier = Modifier
              .padding(horizontal = 12.dp),
          )

          if (index < uiState.recentConversations.lastIndex) {
            Spacer(modifier = Modifier.height(2.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyConversationState() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(120.dp)
      .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = stringResource(R.string.drawer_no_conversations),
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.drawer_start_new_task),
        style = MaterialTheme.typography.labelSmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun DockedSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  isExpanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  results: List<TaskHistoryItem>,
  onResultClick: (TaskHistoryItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  LaunchedEffect(isExpanded) {
    if (isExpanded) focusRequester.requestFocus()
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(28.dp))
        .clickable(enabled = !isExpanded) { onExpandedChange(true) },
      shape = RoundedCornerShape(28.dp),
      color = FutonTheme.colors.background,
      tonalElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (isExpanded) {
          IconButton(
            onClick = {
              focusManager.clearFocus()
              onExpandedChange(false)
            },
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
              tint = FutonTheme.colors.textNormal,
            )
          }
        } else {
          Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.padding(start = 16.dp),
            tint = FutonTheme.colors.textMuted,
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
          if (query.isEmpty()) {
            Text(
              text = stringResource(R.string.drawer_search_hint),
              style = MaterialTheme.typography.bodyLarge,
              color = FutonTheme.colors.textMuted,
            )
          }
          if (isExpanded) {
            BasicTextField(
              value = query,
              onValueChange = onQueryChange,
              modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
              textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = FutonTheme.colors.textNormal,
              ),
              singleLine = true,
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
              keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
          }
        }

        AnimatedVisibility(
          visible = query.isNotEmpty(),
          enter = fadeIn() + scaleIn(),
          exit = fadeOut() + scaleOut(),
        ) {
          IconButton(
            onClick = { onQueryChange("") },
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.action_clear),
              tint = FutonTheme.colors.textMuted,
            )
          }
        }
      }
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically() + fadeIn(tween(150)),
      exit = shrinkVertically() + fadeOut(tween(150)),
    ) {
      SearchResultsView(
        query = query,
        results = results,
        onResultClick = onResultClick,
      )
    }
  }
}

@Composable
private fun SearchResultsView(
  query: String,
  results: List<TaskHistoryItem>,
  onResultClick: (TaskHistoryItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = 4.dp),
    shape = RoundedCornerShape(16.dp),
    color = FutonTheme.colors.background,
    tonalElevation = 0.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      when {
        query.isEmpty() -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(120.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = FutonTheme.colors.textMuted,
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = stringResource(R.string.drawer_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = FutonTheme.colors.textMuted,
              )
            }
          }
        }

        results.isEmpty() -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(120.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = stringResource(R.string.drawer_search_no_result, query),
                style = MaterialTheme.typography.bodyMedium,
                color = FutonTheme.colors.textNormal,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.drawer_search_try_other),
                style = MaterialTheme.typography.bodySmall,
                color = FutonTheme.colors.textMuted,
              )
            }
          }
        }

        else -> {
          results.take(8).forEach { item ->
            SearchResultItem(
              item = item,
              onClick = { onResultClick(item) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SearchResultItem(
  item: TaskHistoryItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val relativeTime = formatRelativeTime(item.timestamp)

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Outlined.History,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = FutonTheme.colors.textMuted,
    )

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.taskDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textNormal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = relativeTime,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun DrawerNavSection(
  selectedDestination: DrawerDestination?,
  onNewConversation: () -> Unit,
  onNavigateToHistory: () -> Unit,
  onNavigateToSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.padding(horizontal = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    DrawerNavItem(
      icon = DrawerDestination.NEW_CONVERSATION.icon,
      label = stringResource(DrawerDestination.NEW_CONVERSATION.labelResId),
      selected = false,
      onClick = onNewConversation,
      position = NavItemPosition.FIRST,
    )

    DrawerNavItem(
      icon = DrawerDestination.HISTORY.icon,
      label = stringResource(DrawerDestination.HISTORY.labelResId),
      selected = selectedDestination == DrawerDestination.HISTORY,
      onClick = onNavigateToHistory,
      position = NavItemPosition.MIDDLE,
    )

    DrawerNavItem(
      icon = DrawerDestination.SETTINGS.icon,
      label = stringResource(DrawerDestination.SETTINGS.labelResId),
      selected = selectedDestination == DrawerDestination.SETTINGS,
      onClick = onNavigateToSettings,
      position = NavItemPosition.LAST,
    )
  }
}

private enum class NavItemPosition {
  SINGLE, FIRST, MIDDLE, LAST
}

@Composable
private fun DrawerNavItem(
  icon: ImageVector,
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  position: NavItemPosition,
  modifier: Modifier = Modifier,
) {
  val containerAlpha by animateFloatAsState(
    targetValue = if (selected) 0.12f else 0f,
    label = "containerAlpha",
  )

  val containerColor = MaterialTheme.colorScheme.primaryContainer
  val contentColor = if (selected) {
    MaterialTheme.colorScheme.primary
  } else {
    FutonTheme.colors.textMuted
  }

  val shape = when (position) {
    NavItemPosition.SINGLE -> RoundedCornerShape(16.dp)
    NavItemPosition.FIRST -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
    NavItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
    NavItemPosition.LAST -> RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = FutonTheme.colors.background,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(containerColor.copy(alpha = containerAlpha))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = ripple(),
          onClick = onClick,
        )
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = contentColor,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
      )
    }
  }
}

@Composable
private fun ConversationItemContent(
  item: TaskHistoryItem,
  onClick: () -> Unit,
  shape: RoundedCornerShape,
  modifier: Modifier = Modifier,
) {
  val relativeTime = formatRelativeTime(item.timestamp)
  val resultInfo = formatResultInfo(item.result, item.stepCount)

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = FutonTheme.colors.background,
    onClick = onClick,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.taskDescription,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.textNormal,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = resultInfo,
            style = MaterialTheme.typography.labelSmall,
            color = when (item.result) {
              AutomationResultType.SUCCESS -> FutonTheme.colors.statusPositive
              AutomationResultType.FAILURE -> FutonTheme.colors.statusDanger
              else -> FutonTheme.colors.textMuted
            },
          )
          Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }

      Icon(
        imageVector = FutonIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = FutonTheme.colors.textMuted,
      )
    }
  }
}


@Composable
private fun formatResultInfo(result: AutomationResultType, stepCount: Int): String {
  val statusText = when (result) {
    AutomationResultType.SUCCESS -> stringResource(R.string.status_success)
    AutomationResultType.FAILURE -> stringResource(R.string.status_failure)
    AutomationResultType.CANCELLED -> stringResource(R.string.status_cancelled)
    AutomationResultType.TIMEOUT -> stringResource(R.string.status_timeout)
  }
  return if (stepCount > 0) {
    stringResource(R.string.status_with_steps, statusText, stepCount)
  } else {
    statusText
  }
}


