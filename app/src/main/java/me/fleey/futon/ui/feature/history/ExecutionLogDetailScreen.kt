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
package me.fleey.futon.ui.feature.history

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.history.models.AutomationResultType
import me.fleey.futon.data.history.models.ErrorLogEntry
import me.fleey.futon.data.history.models.ErrorType
import me.fleey.futon.domain.automation.models.ActionResult
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.component.FutonSizes
import me.fleey.futon.ui.designsystem.component.buttons.FutonIconButton
import me.fleey.futon.ui.designsystem.component.feedback.FutonEmptyState
import me.fleey.futon.ui.designsystem.component.feedback.FutonLoadingOverlay
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionLogDetailScreen(
  logId: String,
  onBack: () -> Unit,
  viewModel: ExecutionLogDetailViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current

  LaunchedEffect(logId) {
    viewModel.loadLog(logId)
  }

  LaunchedEffect(viewModel.effect) {
    viewModel.effect.collect { effect ->
      when (effect) {
        is LogDetailEffect.ShareLog -> {
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, effect.json)
            putExtra(Intent.EXTRA_SUBJECT, "Futon Execution Log")
          }

          val chooserIntent = Intent.createChooser(shareIntent, "Share Execution Log")
          context.startActivity(chooserIntent)
        }

        is LogDetailEffect.ShowError -> {
          Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.execution_log_detail),
        onBackClick = onBack,
        actions = {
          if (uiState.header != null) {
            FutonIconButton(
              icon = Icons.Default.Share,
              onClick = { viewModel.onEvent(ExecutionLogDetailEvent.ExportLog) },
              contentDescription = stringResource(R.string.export_log),
            )
          }
        },
      )
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    FutonLoadingOverlay(loading = uiState.isLoading) {
      when {
        uiState.error != null -> {
          FutonEmptyState(
            icon = FutonIcons.Error,
            title = uiState.error ?: stringResource(R.string.error_unknown),
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
          )
        }

        uiState.header == null && !uiState.isLoading -> {
          FutonEmptyState(
            icon = FutonIcons.EmptyHistory,
            title = stringResource(R.string.log_not_found),
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
          )
        }

        uiState.header != null -> {
          ExecutionLogContent(
            header = uiState.header!!,
            steps = uiState.steps,
            errors = uiState.errors,
            modifier = Modifier.padding(padding),
          )
        }
      }
    }
  }
}

@Composable
private fun ExecutionLogContent(
  header: LogHeaderUiModel,
  steps: List<MergedStepUiModel>,
  errors: List<ErrorLogEntry>,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    item(key = "summary") {
      ExecutionSummaryCard(header = header)
      Spacer(modifier = Modifier.height(16.dp))
    }

    if (steps.isNotEmpty()) {
      item(key = "steps-header") {
        SectionHeader(
          title = stringResource(R.string.execution_steps_section),
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }

      itemsIndexed(
        items = steps,
        key = { index, _ -> "step-$index" },
      ) { index, step ->
        val shape = getItemShape(index, steps.size)
        StepLogItem(step = step, shape = shape)
      }
    }

    if (errors.isNotEmpty()) {
      item(key = "errors-header") {
        SectionHeader(
          title = stringResource(R.string.errors_section),
          modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
      }

      itemsIndexed(
        items = errors,
        key = { index, _ -> "error-$index" },
      ) { index, error ->
        val shape = getItemShape(index, errors.size)
        ErrorLogItem(error = error, shape = shape)
      }
    }
  }
}

@Composable
private fun ExecutionSummaryCard(
  header: LogHeaderUiModel,
  modifier: Modifier = Modifier,
) {
  val (statusColor, statusText) = when (header.result) {
    AutomationResultType.SUCCESS -> FutonTheme.colors.statusPositive to stringResource(R.string.result_success)
    AutomationResultType.FAILURE -> FutonTheme.colors.statusDanger to stringResource(R.string.result_failure)
    AutomationResultType.CANCELLED -> FutonTheme.colors.textMuted to stringResource(R.string.result_cancelled)
    AutomationResultType.TIMEOUT -> FutonTheme.colors.statusWarning to stringResource(R.string.result_timeout)
  }

  val statusIcon = when (header.result) {
    AutomationResultType.SUCCESS -> FutonIcons.Success
    AutomationResultType.FAILURE -> FutonIcons.Error
    AutomationResultType.CANCELLED -> FutonIcons.Close
    AutomationResultType.TIMEOUT -> FutonIcons.Warning
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = FutonShapes.CardShape,
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Column(modifier = Modifier.padding(FutonSizes.CardPadding)) {
      Surface(
        shape = FutonShapes.ButtonShape,
        color = statusColor.copy(alpha = 0.15f),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = header.taskDescription,
        style = MaterialTheme.typography.titleMedium,
        color = FutonTheme.colors.textNormal,
      )

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        StatItem(
          label = stringResource(R.string.start_time),
          value = header.startTime,
        )
        StatItem(
          label = stringResource(R.string.duration),
          value = header.duration,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        StatItem(
          label = stringResource(R.string.steps_executed),
          value = header.stepCount.toString(),
        )
        StatItem(
          label = stringResource(R.string.errors_count),
          value = header.errorCount.toString(),
        )
      }
    }
  }
}

@Composable
private fun StatItem(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textNormal,
    )
  }
}

@Composable
private fun SectionHeader(
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

@Composable
private fun StepLogItem(
  step: MergedStepUiModel,
  shape: RoundedCornerShape,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val isSuccess = step.result is ActionResult.Success
  val resultColor = if (isSuccess) FutonTheme.colors.statusPositive else FutonTheme.colors.statusDanger

  val hasExpandableContent = step.reasoning != null || step.formattedParams != null ||
    step.result is ActionResult.Failure

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .then(
        if (hasExpandableContent) Modifier.clickable { expanded = !expanded }
        else Modifier,
      ),
    shape = shape,
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(FutonShapes.StatusDotShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = step.index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = step.actionName,
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal,
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = step.durationText,
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
            )
            step.aiResponseTimeMs?.let { aiTimeMs ->
              Text(
                text = stringResource(R.string.ai_response_ms, aiTimeMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
              )
            }
          }
        }

        Icon(
          imageVector = if (isSuccess) FutonIcons.Success else FutonIcons.Error,
          contentDescription = null,
          tint = resultColor,
          modifier = Modifier.size(18.dp),
        )

        if (hasExpandableContent) {
          Spacer(modifier = Modifier.width(4.dp))
          Icon(
            imageVector = if (expanded) FutonIcons.ExpandLess else FutonIcons.ExpandMore,
            contentDescription = null,
            tint = FutonTheme.colors.textMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
          step.formattedParams?.let { params ->
            DetailRow(
              label = stringResource(R.string.parameters),
              value = params,
            )
            Spacer(modifier = Modifier.height(8.dp))
          }

          step.reasoning?.let { reasoning ->
            DetailRow(
              label = stringResource(R.string.ai_reasoning),
              value = reasoning,
            )
            Spacer(modifier = Modifier.height(8.dp))
          }

          if (step.result is ActionResult.Failure) {
            DetailRow(
              label = stringResource(R.string.failure_reason),
              value = step.result.reason,
              valueColor = FutonTheme.colors.statusDanger,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ErrorLogItem(
  error: ErrorLogEntry,
  shape: RoundedCornerShape,
  modifier: Modifier = Modifier,
) {
  val errorTypeText = when (error.errorType) {
    ErrorType.NETWORK_ERROR -> stringResource(R.string.error_type_network)
    ErrorType.TIMEOUT_ERROR -> stringResource(R.string.error_type_timeout)
    ErrorType.API_ERROR -> stringResource(R.string.error_type_api)
    ErrorType.INVALID_RESPONSE -> stringResource(R.string.error_type_invalid_response)
    ErrorType.ACTION_FAILED -> stringResource(R.string.error_type_action_failed)
    ErrorType.UNKNOWN -> stringResource(R.string.error_type_unknown)
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = FutonTheme.colors.statusDanger.copy(alpha = 0.1f),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = FutonIcons.Error,
        contentDescription = null,
        tint = FutonTheme.colors.statusDanger,
        modifier = Modifier.size(20.dp),
      )

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.step_number, error.step),
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.statusDanger,
          )
          Surface(
            shape = FutonShapes.ButtonShape,
            color = FutonTheme.colors.statusDanger.copy(alpha = 0.2f),
          ) {
            Text(
              text = errorTypeText,
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.statusDanger,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = error.message,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textNormal,
        )
      }

      if (error.isRetryable) {
        Icon(
          imageVector = FutonIcons.Retry,
          contentDescription = stringResource(R.string.retryable),
          tint = FutonTheme.colors.textMuted,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  valueColor: Color = FutonTheme.colors.textNormal,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = valueColor,
    )
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
