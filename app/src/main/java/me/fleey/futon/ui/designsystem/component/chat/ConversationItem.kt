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
package me.fleey.futon.ui.designsystem.component.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.history.models.TaskHistoryItem
import me.fleey.futon.ui.designsystem.component.swipe.SwipeAction
import me.fleey.futon.ui.designsystem.component.swipe.SwipeActionBox
import me.fleey.futon.ui.designsystem.component.swipe.SwipeActionMode
import me.fleey.futon.ui.designsystem.component.swipe.SwipeUndoConfig
import me.fleey.futon.ui.designsystem.theme.FutonTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(
  item: TaskHistoryItem,
  isMultiSelectMode: Boolean,
  isSelected: Boolean,
  isExpanded: Boolean,
  onToggleExpand: () -> Unit,
  onRetry: () -> Unit,
  onViewDetails: () -> Unit,
  onDelete: () -> Unit,
  onLongPress: () -> Unit,
  onToggleSelection: () -> Unit,
  snackbarHostState: SnackbarHostState? = null,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(16.dp)
  val dangerColor = FutonTheme.colors.statusDanger
  val deleteAction = remember(onDelete, dangerColor) {
    SwipeAction.delete(
      onDelete = onDelete,
      backgroundColor = dangerColor,
    )
  }

  SwipeActionBox(
    modifier = modifier,
    endActions = listOf(deleteAction),
    enabled = !isMultiSelectMode,
    shape = shape,
    actionMode = SwipeActionMode.WithUndo,
    undoConfig = SwipeUndoConfig(messageResId = R.string.swipe_deleted_record),
    snackbarHostState = snackbarHostState,
  ) {
    ConversationContent(
      item = item,
      isMultiSelectMode = isMultiSelectMode,
      isSelected = isSelected,
      isExpanded = isExpanded,
      onToggleExpand = onToggleExpand,
      onRetry = onRetry,
      onViewDetails = onViewDetails,
      onDelete = onDelete,
      onLongPress = onLongPress,
      onToggleSelection = onToggleSelection,
      shape = shape,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationContent(
  item: TaskHistoryItem,
  isMultiSelectMode: Boolean,
  isSelected: Boolean,
  isExpanded: Boolean,
  onToggleExpand: () -> Unit,
  onRetry: () -> Unit,
  onViewDetails: () -> Unit,
  onDelete: () -> Unit,
  onLongPress: () -> Unit,
  onToggleSelection: () -> Unit,
  shape: RoundedCornerShape,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(FutonTheme.colors.backgroundSecondary)
      .combinedClickable(
        onClick = {
          if (isMultiSelectMode) {
            onToggleSelection()
          } else {
            onToggleExpand()
          }
        },
        onLongClick = {
          if (!isMultiSelectMode) {
            onLongPress()
          }
        },
      )
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (isMultiSelectMode) {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onToggleSelection() },
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TaskBubble(taskDescription = item.taskDescription)

        ResultBubble(
          result = item.result,
          stepCount = item.stepCount,
          timestamp = item.timestamp,
        )
      }
    }

    AnimatedVisibility(
      visible = isExpanded && !isMultiSelectMode,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      ConversationActionBar(
        hasExecutionLog = item.executionLogId != null,
        onRetry = onRetry,
        onViewDetails = onViewDetails,
        onDelete = onDelete,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
