package me.fleey.futon.ui.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.presenter.presenterOf
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.runtime.ui.ui
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.fleey.futon.R
import me.fleey.futon.data.history.TaskHistoryRepository
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.TaskHistoryItem
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.buttons.FutonIconButton
import me.fleey.futon.ui.designsystem.component.feedback.FutonEmptyState
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.swipe.CountdownSnackbarHost
import me.fleey.futon.ui.designsystem.component.swipe.CountdownSnackbarState
import me.fleey.futon.ui.designsystem.component.swipe.SwipeAction
import me.fleey.futon.ui.designsystem.component.swipe.SwipeActionBox
import me.fleey.futon.ui.designsystem.component.swipe.SwipeActionMode
import me.fleey.futon.ui.designsystem.component.swipe.SwipeUndoConfig
import me.fleey.futon.ui.designsystem.component.swipe.rememberCountdownSnackbarState
import me.fleey.futon.ui.designsystem.components.getStatusStyling
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface HistoryUiItem {
  val id: String

  data class Header(
    override val id: String,
    val titleRes: Int,
    val dateArgs: List<Any> = emptyList(),
  ) : HistoryUiItem

  data class Content(
    val item: TaskHistoryItem,
    val shape: RoundedCornerShape,
    val timeString: String,
  ) : HistoryUiItem {
    override val id: String get() = item.id
  }
}

@Parcelize
data object CircuitHistoryScreen : Screen {
  data class State(
    val uiItems: List<HistoryUiItem> = emptyList(),
    val totalItemCount: Int = 0,
    val isLoading: Boolean = true,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val expandedItemId: String? = null,
    val eventSink: (Event) -> Unit,
  ) : CircuitUiState {
    val selectedCount: Int get() = selectedIds.size
    val isAllSelected: Boolean
      get() = selectedIds.size == totalItemCount && totalItemCount > 0

    fun isSelected(id: String): Boolean = id in selectedIds
    fun isExpanded(id: String): Boolean = expandedItemId == id
  }

  sealed interface Event : CircuitUiEvent {
    data object ClearHistory : Event
    data class DeleteItem(val itemId: String) : Event

    data class EnterMultiSelect(val itemId: String) : Event
    data object ExitMultiSelect : Event
    data class ToggleSelection(val itemId: String) : Event
    data object SelectAll : Event
    data object DeleteSelected : Event

    data class ToggleExpand(val itemId: String) : Event
    data object CollapseAll : Event

    data class ViewLogDetail(val logId: String) : Event
    data class RetryTask(val description: String) : Event
  }
}

@Parcelize
data class TaskWithPrefillScreen(val task: String) : Screen

@Parcelize
data class ExecutionLogDetailScreen(val logId: String) : Screen


@Composable
fun HistoryPresenter(
  navigator: Navigator,
  repository: TaskHistoryRepository = koinInject(),
): CircuitHistoryScreen.State {
  val scope = rememberCoroutineScope()

  val rawItems by repository.getHistoryFlow().collectAsState(initial = emptyList())
  val isLoading = false

  var isMultiSelectMode by remember { mutableStateOf(false) }
  var selectedIds by remember { mutableStateOf(emptySet<String>()) }
  var expandedItemId by remember { mutableStateOf<String?>(null) }

  val uiItems = remember(rawItems) {
    processHistoryItems(rawItems)
  }

  fun eventSink(event: CircuitHistoryScreen.Event) {
    when (event) {
      CircuitHistoryScreen.Event.ClearHistory -> {
        scope.launch { repository.clearHistory() }
      }

      is CircuitHistoryScreen.Event.DeleteItem -> {
        scope.launch { repository.deleteByIds(setOf(event.itemId)) }
      }

      is CircuitHistoryScreen.Event.EnterMultiSelect -> {
        isMultiSelectMode = true
        selectedIds = setOf(event.itemId)
      }

      CircuitHistoryScreen.Event.ExitMultiSelect -> {
        isMultiSelectMode = false
        selectedIds = emptySet()
      }

      is CircuitHistoryScreen.Event.ToggleSelection -> {
        val itemId = event.itemId
        val newSelectedIds = if (itemId in selectedIds) {
          selectedIds - itemId
        } else {
          selectedIds + itemId
        }

        if (newSelectedIds.isEmpty()) {
          isMultiSelectMode = false
          selectedIds = emptySet()
        } else {
          selectedIds = newSelectedIds
        }
      }

      CircuitHistoryScreen.Event.SelectAll -> {
        selectedIds = if (selectedIds.size == rawItems.size) {
          emptySet()
        } else {
          rawItems.map { it.id }.toSet()
        }
      }

      CircuitHistoryScreen.Event.DeleteSelected -> {
        val idsToDelete = selectedIds
        if (idsToDelete.isNotEmpty()) {
          scope.launch {
            repository.deleteByIds(idsToDelete)
            isMultiSelectMode = false
            selectedIds = emptySet()
          }
        }
      }

      is CircuitHistoryScreen.Event.ToggleExpand -> {
        expandedItemId = if (expandedItemId == event.itemId) null else event.itemId
      }

      CircuitHistoryScreen.Event.CollapseAll -> {
        expandedItemId = null
      }

      is CircuitHistoryScreen.Event.RetryTask -> {
        navigator.goTo(TaskWithPrefillScreen(event.description))
      }

      is CircuitHistoryScreen.Event.ViewLogDetail -> {
        navigator.goTo(ExecutionLogDetailScreen(event.logId))
      }
    }
  }

  return CircuitHistoryScreen.State(
    uiItems = uiItems,
    totalItemCount = rawItems.size,
    isLoading = isLoading,
    isMultiSelectMode = isMultiSelectMode,
    selectedIds = selectedIds,
    expandedItemId = expandedItemId,
    eventSink = ::eventSink,
  )
}

private fun processHistoryItems(items: List<TaskHistoryItem>): List<HistoryUiItem> {
  if (items.isEmpty()) return emptyList()

  val sorted = items.sortedByDescending { it.timestamp }
  val zoneId = ZoneId.systemDefault()
  val now = Instant.now().atZone(zoneId).toLocalDate()

  val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)

  val result = mutableListOf<HistoryUiItem>()
  val grouped = sorted.groupBy { item ->
    val itemDate = Instant.ofEpochMilli(item.timestamp).atZone(zoneId).toLocalDate()
    when {
      itemDate.isEqual(now) -> DateGroup.TODAY to null
      itemDate.isEqual(now.minusDays(1)) -> DateGroup.YESTERDAY to null
      itemDate.isAfter(now.minusDays(7)) -> DateGroup.THIS_WEEK to null
      else -> DateGroup.OTHER to "${itemDate.monthValue}|${itemDate.dayOfMonth}"
    }
  }

  grouped.forEach { (key, groupItems) ->
    val (groupType, dateString) = key
    val headerId = "header_${groupType}_$dateString"

    val (titleRes, args) = when (groupType) {
      DateGroup.TODAY -> R.string.time_today to emptyList()
      DateGroup.YESTERDAY -> R.string.time_yesterday to emptyList()
      DateGroup.THIS_WEEK -> R.string.time_this_week to emptyList()
      DateGroup.OTHER -> {
        val parts = dateString?.split("|") ?: listOf("0", "0")
        R.string.time_date_format to listOf(parts[0].toInt(), parts[1].toInt())
      }
    }
    result.add(HistoryUiItem.Header(headerId, titleRes, args))

    groupItems.forEachIndexed { index, item ->
      val shape = getItemShape(index, groupItems.size)
      result.add(
        HistoryUiItem.Content(
          item = item,
          shape = shape,
          timeString = timeFormatter.format(Instant.ofEpochMilli(item.timestamp)),
        ),
      )
    }
  }
  return result
}

class HistoryPresenterFactory : Presenter.Factory {
  override fun create(
    screen: Screen,
    navigator: Navigator,
    context: CircuitContext,
  ): Presenter<*>? {
    return when (screen) {
      is CircuitHistoryScreen -> presenterOf { HistoryPresenter(navigator) }
      else -> null
    }
  }
}

class HistoryUiFactory : Ui.Factory {
  override fun create(screen: Screen, context: CircuitContext): Ui<*>? {
    return when (screen) {
      is CircuitHistoryScreen -> ui<CircuitHistoryScreen.State> { state, modifier ->
        HistoryUi(state, modifier)
      }

      else -> null
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryUi(state: CircuitHistoryScreen.State, modifier: Modifier = Modifier) {
  val countdownSnackbarState = rememberCountdownSnackbarState()

  BackHandler(enabled = state.isMultiSelectMode) {
    state.eventSink(CircuitHistoryScreen.Event.ExitMultiSelect)
  }

  Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
      topBar = {
        if (state.isMultiSelectMode) {
          FutonTopBar(
            title = stringResource(R.string.history_selected_count, state.selectedCount),
            onBackClick = { state.eventSink(CircuitHistoryScreen.Event.ExitMultiSelect) },
            actions = {
              FutonIconButton(
                icon = if (state.isAllSelected) FutonIcons.DeselectAll else FutonIcons.SelectAll,
                onClick = { state.eventSink(CircuitHistoryScreen.Event.SelectAll) },
                contentDescription = stringResource(
                  if (state.isAllSelected) R.string.history_deselect_all
                  else R.string.history_select_all,
                ),
              )
              FutonIconButton(
                icon = FutonIcons.Delete,
                onClick = { state.eventSink(CircuitHistoryScreen.Event.DeleteSelected) },
                contentDescription = stringResource(R.string.history_delete_selected),
                enabled = state.selectedCount > 0,
              )
            },
          )
        } else {
          FutonTopBar(
            title = stringResource(R.string.nav_history),
          )
        }
      },
      containerColor = FutonTheme.colors.background,
    ) { padding ->
      FutonLoadingOverlay(loading = state.isLoading) {
        if (state.totalItemCount == 0 && !state.isLoading) {
          FutonEmptyState(
            icon = FutonIcons.EmptyHistory,
            title = stringResource(R.string.no_history),
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
          )
        } else {
          HistoryContent(
            uiItems = state.uiItems,
            state = state,
            countdownSnackbarState = countdownSnackbarState,
            modifier = Modifier.padding(padding),
          )
        }
      }
    }

    CountdownSnackbarHost(
      state = countdownSnackbarState,
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

@Composable
private fun HistoryContent(
  uiItems: List<HistoryUiItem>,
  state: CircuitHistoryScreen.State,
  countdownSnackbarState: CountdownSnackbarState,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
  ) {
    items(
      count = uiItems.size,
      key = { index -> uiItems[index].id },
      contentType = { index ->
        when (uiItems[index]) {
          is HistoryUiItem.Header -> 0
          is HistoryUiItem.Content -> 1
        }
      },
    ) { index ->
      when (val item = uiItems[index]) {
        is HistoryUiItem.Header -> {
          val title = if (item.dateArgs.isNotEmpty()) {
            stringResource(item.titleRes, *item.dateArgs.toTypedArray())
          } else {
            stringResource(item.titleRes)
          }

          HistorySectionHeader(
            title = title,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
          )
        }

        is HistoryUiItem.Content -> {
          Box(modifier = Modifier.padding(vertical = 1.dp)) {
            HistoryItemCard(
              item = item.item,
              timeString = item.timeString,
              shape = item.shape,
              state = state,
              countdownSnackbarState = countdownSnackbarState,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HistorySectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleMedium,
    color = FutonTheme.colors.textMuted,
    modifier = modifier,
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemCard(
  item: TaskHistoryItem,
  timeString: String,
  shape: RoundedCornerShape,
  state: CircuitHistoryScreen.State,
  countdownSnackbarState: CountdownSnackbarState,
) {
  val dangerColor = FutonTheme.colors.statusDanger
  val onDelete = { state.eventSink(CircuitHistoryScreen.Event.DeleteItem(item.id)) }
  val deleteAction = remember(onDelete, dangerColor) {
    SwipeAction.delete(
      onDelete = onDelete,
      backgroundColor = dangerColor,
    )
  }

  SwipeActionBox(
    endActions = listOf(deleteAction),
    enabled = !state.isMultiSelectMode,
    shape = shape,
    actionMode = SwipeActionMode.WithUndo,
    undoConfig = SwipeUndoConfig(messageResId = R.string.swipe_deleted_record),
    countdownSnackbarState = countdownSnackbarState,
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .combinedClickable(
          onClick = {
            if (state.isMultiSelectMode) {
              state.eventSink(CircuitHistoryScreen.Event.ToggleSelection(item.id))
            } else {
              state.eventSink(CircuitHistoryScreen.Event.ToggleExpand(item.id))
            }
          },
          onLongClick = {
            if (!state.isMultiSelectMode) {
              state.eventSink(CircuitHistoryScreen.Event.EnterMultiSelect(item.id))
            }
          },
        ),
      color = FutonTheme.colors.backgroundSecondary,
      shape = shape,
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (state.isMultiSelectMode) {
            Checkbox(
              checked = state.isSelected(item.id),
              onCheckedChange = { state.eventSink(CircuitHistoryScreen.Event.ToggleSelection(item.id)) },
              modifier = Modifier.size(24.dp),
            )
          }

          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TaskContent(taskDescription = item.taskDescription)
            ResultContent(
              result = item.result,
              stepCount = item.stepCount,
              timeText = timeString,
            )
          }
        }

        AnimatedVisibility(
          visible = state.isExpanded(item.id) && !state.isMultiSelectMode,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          ActionBar(
            hasExecutionLog = item.executionLogId != null,
            onRetry = { state.eventSink(CircuitHistoryScreen.Event.RetryTask(item.taskDescription)) },
            onViewDetails = { item.executionLogId?.let { state.eventSink(CircuitHistoryScreen.Event.ViewLogDetail(it)) } },
          )
        }
      }
    }
  }
}

@Composable
private fun TaskContent(
  taskDescription: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = taskDescription,
    style = MaterialTheme.typography.bodyLarge,
    color = FutonTheme.colors.textNormal,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
private fun ResultContent(
  result: AutomationResultType,
  stepCount: Int,
  timeText: String,
  modifier: Modifier = Modifier,
) {
  val styling = getStatusStyling(result)

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      color = styling.color.copy(alpha = 0.15f),
      shape = RoundedCornerShape(6.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = styling.icon,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = styling.color,
        )
        Text(
          text = timeText,
          style = MaterialTheme.typography.labelMedium,
          color = styling.color,
        )
      }
    }

    Text(
      text = stringResource(R.string.history_steps, stepCount),
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )

    Text(
      text = "·",
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )

    Text(
      text = timeText,
      style = MaterialTheme.typography.bodySmall,
      color = FutonTheme.colors.textMuted,
    )
  }
}

@Composable
private fun ActionBar(
  hasExecutionLog: Boolean,
  onRetry: () -> Unit,
  onViewDetails: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      onClick = onRetry,
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
      shape = RoundedCornerShape(8.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = FutonIcons.Retry,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.action_retry),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    if (hasExecutionLog) {
      Surface(
        onClick = onViewDetails,
        color = FutonTheme.colors.backgroundTertiary,
        shape = RoundedCornerShape(8.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = FutonIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = FutonTheme.colors.textMuted,
          )
          Text(
            text = stringResource(R.string.view_details),
            style = MaterialTheme.typography.labelMedium,
            color = FutonTheme.colors.textMuted,
          )
        }
      }
    }
  }
}

private fun getItemShape(index: Int, totalItems: Int): RoundedCornerShape {
  return when {
    totalItems == 1 -> RoundedCornerShape(16.dp)
    index == 0 -> RoundedCornerShape(
      topStart = 16.dp,
      topEnd = 16.dp,
      bottomStart = 4.dp,
      bottomEnd = 4.dp,
    )

    index == totalItems - 1 -> RoundedCornerShape(
      topStart = 4.dp,
      topEnd = 4.dp,
      bottomStart = 16.dp,
      bottomEnd = 16.dp,
    )

    else -> RoundedCornerShape(4.dp)
  }
}

private enum class DateGroup {
  TODAY, YESTERDAY, THIS_WEEK, OTHER
}
