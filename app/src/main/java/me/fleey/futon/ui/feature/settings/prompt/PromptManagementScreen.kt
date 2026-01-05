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
package me.fleey.futon.ui.feature.settings.prompt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.prompt.models.PromptTemplate
import me.fleey.futon.data.prompt.models.QuickPhrase
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonSegmentedTab
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.navigation.SegmentedTabItem
import me.fleey.futon.ui.designsystem.component.selection.FutonRadioButton
import me.fleey.futon.ui.designsystem.component.selection.FutonSwitch
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun PromptManagementScreen(
  onBack: () -> Unit,
  onEditTemplate: (String?) -> Unit,
  onEditQuickPhrase: (String?) -> Unit,
  viewModel: PromptViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  uiState.error?.let { error ->
    val message = stringResource(error.messageRes)
    LaunchedEffect(error) {
      snackbarHostState.showSnackbar(message)
      viewModel.onEvent(PromptUiEvent.DismissError)
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.prompt_management_title),
        onBackClick = onBack,
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          when (uiState.selectedTab) {
            PromptTab.TEMPLATES -> onEditTemplate(null)
            PromptTab.QUICK_PHRASES -> onEditQuickPhrase(null)
          }
        },
        containerColor = FutonTheme.colors.interactiveActive,
      ) {
        Icon(
          imageVector = FutonIcons.Add,
          contentDescription = stringResource(R.string.prompt_add_new),
          tint = FutonTheme.colors.background,
        )
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      val tabs = remember {
        listOf(
          SegmentedTabItem(R.string.prompt_tab_templates),
          SegmentedTabItem(R.string.prompt_tab_quick_phrases),
        )
      }

      FutonSegmentedTab(
        selectedIndex = uiState.selectedTab.ordinal,
        onTabSelected = { index ->
          viewModel.onEvent(PromptUiEvent.TabSelected(PromptTab.entries[index]))
        },
        tabs = tabs,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )

      AnimatedVisibility(
        visible = uiState.isLoading,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      }

      AnimatedVisibility(
        visible = !uiState.isLoading,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        when (uiState.selectedTab) {
          PromptTab.TEMPLATES -> TemplatesContent(
            systemTemplates = uiState.systemTemplates,
            customTemplates = uiState.customTemplates,
            activePromptId = uiState.activeSystemPromptId,
            onSetActive = { viewModel.onEvent(PromptUiEvent.SetActivePrompt(it)) },
            onEdit = onEditTemplate,
            onDelete = { viewModel.onEvent(PromptUiEvent.DeleteTemplate(it)) },
          )

          PromptTab.QUICK_PHRASES -> QuickPhrasesContent(
            phrases = uiState.quickPhrases,
            enabled = uiState.enableQuickPhrases,
            onEnabledChange = { viewModel.onEvent(PromptUiEvent.SetQuickPhrasesEnabled(it)) },
            onTogglePhrase = { viewModel.onEvent(PromptUiEvent.ToggleQuickPhraseEnabled(it)) },
            onEdit = onEditQuickPhrase,
            onDelete = { viewModel.onEvent(PromptUiEvent.DeleteQuickPhrase(it)) },
          )
        }
      }
    }
  }

  uiState.showDeleteConfirmation?.let { target ->
    DeleteConfirmationDialog(
      target = target,
      onConfirm = { viewModel.onEvent(PromptUiEvent.ConfirmDelete) },
      onDismiss = { viewModel.onEvent(PromptUiEvent.DismissDeleteConfirmation) },
    )
  }
}

@Composable
private fun TemplatesContent(
  systemTemplates: List<PromptTemplate>,
  customTemplates: List<PromptTemplate>,
  activePromptId: String?,
  onSetActive: (String?) -> Unit,
  onEdit: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (systemTemplates.isNotEmpty()) {
      item(key = "builtin_header") {
        Text(
          text = stringResource(R.string.prompt_section_builtin),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      item(key = "builtin_group") {
        TemplateGroup(
          templates = systemTemplates,
          activePromptId = activePromptId,
          onSetActive = onSetActive,
          onEdit = onEdit,
          onDelete = null,
        )
      }
    }

    if (customTemplates.isNotEmpty()) {
      item(key = "custom_header") {
        Text(
          text = stringResource(R.string.prompt_section_custom),
          style = MaterialTheme.typography.titleMedium,
          color = FutonTheme.colors.textMuted,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      item(key = "custom_group") {
        TemplateGroup(
          templates = customTemplates,
          activePromptId = activePromptId,
          onSetActive = onSetActive,
          onEdit = onEdit,
          onDelete = onDelete,
        )
      }
    }

    if (systemTemplates.isEmpty() && customTemplates.isEmpty()) {
      item(key = "empty") {
        EmptyState(message = stringResource(R.string.prompt_empty_templates))
      }
    }
  }
}

@Composable
private fun TemplateGroup(
  templates: List<PromptTemplate>,
  activePromptId: String?,
  onSetActive: (String?) -> Unit,
  onEdit: (String) -> Unit,
  onDelete: ((String) -> Unit)?,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    templates.forEachIndexed { index, template ->
      val shape = getGroupItemShape(index, templates.size)
      TemplateItem(
        template = template,
        isActive = template.id == activePromptId,
        onSetActive = { onSetActive(template.id) },
        onEdit = { onEdit(template.id) },
        onDelete = onDelete?.let { { it(template.id) } },
        shape = shape,
      )
    }
  }
}

@Composable
private fun TemplateItem(
  template: PromptTemplate,
  isActive: Boolean,
  onSetActive: () -> Unit,
  onEdit: () -> Unit,
  onDelete: (() -> Unit)?,
  shape: RoundedCornerShape,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = if (isActive) {
      FutonTheme.colors.interactiveActive.copy(alpha = 0.1f)
    } else {
      FutonTheme.colors.backgroundSecondary
    },
    shape = shape,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FutonRadioButton(
        selected = isActive,
        onClick = onSetActive,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = template.name,
            style = MaterialTheme.typography.bodyMedium,
            color = FutonTheme.colors.textNormal,
          )
          if (template.isBuiltIn) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.prompt_builtin_badge),
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.interactiveActive,
            )
          }
        }
        if (template.description.isNotBlank()) {
          Text(
            text = template.description,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      IconButton(onClick = onEdit) {
        Icon(
          imageVector = if (template.isBuiltIn) FutonIcons.Visibility else FutonIcons.Edit,
          contentDescription = stringResource(R.string.prompt_edit),
          tint = FutonTheme.colors.interactiveNormal,
        )
      }
      if (onDelete != null) {
        IconButton(onClick = onDelete) {
          Icon(
            imageVector = FutonIcons.Delete,
            contentDescription = stringResource(R.string.prompt_delete),
            tint = FutonTheme.colors.statusDanger,
          )
        }
      }
    }
  }
}

@Composable
private fun QuickPhrasesContent(
  phrases: List<QuickPhrase>,
  enabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onTogglePhrase: (String) -> Unit,
  onEdit: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item(key = "enable_switch") {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FutonTheme.colors.backgroundSecondary,
        shape = RoundedCornerShape(16.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.prompt_quick_phrases_enable),
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              text = stringResource(R.string.prompt_quick_phrases_description),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
            )
          }
          FutonSwitch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
          )
        }
      }
    }

    if (phrases.isNotEmpty()) {
      item(key = "phrases_group") {
        QuickPhraseGroup(
          phrases = phrases,
          globalEnabled = enabled,
          onTogglePhrase = onTogglePhrase,
          onEdit = onEdit,
          onDelete = onDelete,
        )
      }
    } else {
      item(key = "empty") {
        EmptyState(message = stringResource(R.string.prompt_empty_phrases))
      }
    }
  }
}

@Composable
private fun QuickPhraseGroup(
  phrases: List<QuickPhrase>,
  globalEnabled: Boolean,
  onTogglePhrase: (String) -> Unit,
  onEdit: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    phrases.forEachIndexed { index, phrase ->
      val shape = getGroupItemShape(index, phrases.size)
      QuickPhraseItem(
        phrase = phrase,
        globalEnabled = globalEnabled,
        onToggle = { onTogglePhrase(phrase.id) },
        onEdit = { onEdit(phrase.id) },
        onDelete = { onDelete(phrase.id) },
        shape = shape,
      )
    }
  }
}

@Composable
private fun QuickPhraseItem(
  phrase: QuickPhrase,
  globalEnabled: Boolean,
  onToggle: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  shape: RoundedCornerShape,
) {
  val contentAlpha = if (globalEnabled && phrase.isEnabled) 1f else 0.5f

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = FutonTheme.colors.backgroundSecondary,
    shape = shape,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FutonSwitch(
        checked = phrase.isEnabled,
        onCheckedChange = { onToggle() },
        enabled = globalEnabled,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = phrase.trigger,
          style = MaterialTheme.typography.bodyMedium,
          color = FutonTheme.colors.interactiveActive.copy(alpha = contentAlpha),
        )
        Text(
          text = phrase.expansion,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted.copy(alpha = contentAlpha),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onEdit) {
        Icon(
          imageVector = FutonIcons.Edit,
          contentDescription = stringResource(R.string.prompt_edit),
          tint = FutonTheme.colors.interactiveNormal.copy(alpha = contentAlpha),
        )
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = FutonIcons.Delete,
          contentDescription = stringResource(R.string.prompt_delete),
          tint = FutonTheme.colors.statusDanger.copy(alpha = contentAlpha),
        )
      }
    }
  }
}

private fun getGroupItemShape(index: Int, totalCount: Int): RoundedCornerShape {
  return when {
    totalCount == 1 -> RoundedCornerShape(16.dp)
    index == 0 -> RoundedCornerShape(
      topStart = 16.dp,
      topEnd = 16.dp,
      bottomStart = 4.dp,
      bottomEnd = 4.dp,
    )

    index == totalCount - 1 -> RoundedCornerShape(
      topStart = 4.dp,
      topEnd = 4.dp,
      bottomStart = 16.dp,
      bottomEnd = 16.dp,
    )

    else -> RoundedCornerShape(4.dp)
  }
}

@Composable
private fun EmptyState(message: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
        imageVector = FutonIcons.Prompt,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = FutonTheme.colors.textMuted.copy(alpha = 0.5f),
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun DeleteConfirmationDialog(
  target: DeleteTarget,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val title = when (target) {
    is DeleteTarget.Template -> stringResource(R.string.prompt_delete_template_title)
    is DeleteTarget.QuickPhrase -> stringResource(R.string.prompt_delete_phrase_title)
  }
  val message = when (target) {
    is DeleteTarget.Template -> stringResource(R.string.prompt_delete_template_message, target.name)
    is DeleteTarget.QuickPhrase -> stringResource(
      R.string.prompt_delete_phrase_message,
      target.trigger,
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(message) },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(R.string.common_delete), color = FutonTheme.colors.statusDanger)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.common_cancel))
      }
    },
  )
}
