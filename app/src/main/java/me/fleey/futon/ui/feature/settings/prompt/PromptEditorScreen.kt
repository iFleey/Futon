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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.data.prompt.models.PromptVariable
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun PromptEditorScreen(
  templateId: String?,
  onBack: () -> Unit,
  viewModel: PromptEditorViewModel = koinViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(templateId) {
    viewModel.loadTemplate(templateId)
  }

  uiState.error?.let { error ->
    val message = stringResource(error.messageRes)
    LaunchedEffect(error) {
      snackbarHostState.showSnackbar(message)
      viewModel.onEvent(PromptEditorUiEvent.DismissError)
    }
  }

  LaunchedEffect(uiState.saveSuccess) {
    if (uiState.saveSuccess) {
      onBack()
    }
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(
          if (templateId == null) R.string.prompt_editor_title_new
          else if (uiState.isReadOnly) R.string.prompt_editor_title_view
          else R.string.prompt_editor_title_edit,
        ),
        onBackClick = onBack,
        actions = {
          if (!uiState.isReadOnly) {
            TextButton(
              onClick = { viewModel.onEvent(PromptEditorUiEvent.Save) },
              enabled = uiState.canSave,
            ) {
              Text(stringResource(R.string.common_save))
            }
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
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    ) {
      OutlinedTextField(
        value = uiState.name,
        onValueChange = { viewModel.onEvent(PromptEditorUiEvent.NameChanged(it)) },
        label = { Text(stringResource(R.string.prompt_editor_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        readOnly = uiState.isReadOnly,
      )

      Spacer(modifier = Modifier.height(16.dp))

      OutlinedTextField(
        value = uiState.description,
        onValueChange = { viewModel.onEvent(PromptEditorUiEvent.DescriptionChanged(it)) },
        label = { Text(stringResource(R.string.prompt_editor_description)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        readOnly = uiState.isReadOnly,
      )

      Spacer(modifier = Modifier.height(16.dp))

      if (!uiState.isReadOnly) {
        VariablesSection(
          variables = uiState.availableVariables,
          onInsert = { viewModel.onEvent(PromptEditorUiEvent.InsertVariable(it)) },
        )
        Spacer(modifier = Modifier.height(16.dp))
      }

      Text(
        text = stringResource(R.string.prompt_editor_content),
        style = MaterialTheme.typography.titleSmall,
        color = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.height(8.dp))

      var contentFieldValue by remember(uiState.content) {
        mutableStateOf(TextFieldValue(uiState.content))
      }

      LaunchedEffect(uiState.insertionRequest) {
        uiState.insertionRequest?.let { variable ->
          val newText = contentFieldValue.text.substring(0, contentFieldValue.selection.start) +
            variable +
            contentFieldValue.text.substring(contentFieldValue.selection.end)
          val newCursor = contentFieldValue.selection.start + variable.length
          contentFieldValue = TextFieldValue(
            text = newText,
            selection = TextRange(newCursor),
          )
          viewModel.onEvent(PromptEditorUiEvent.ContentChanged(newText))
          viewModel.onEvent(PromptEditorUiEvent.ClearInsertionRequest)
        }
      }

      OutlinedTextField(
        value = contentFieldValue,
        onValueChange = {
          contentFieldValue = it
          viewModel.onEvent(PromptEditorUiEvent.ContentChanged(it.text))
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp),
        readOnly = uiState.isReadOnly,
        textStyle = MaterialTheme.typography.bodyMedium,
      )

      if (uiState.isReadOnly && uiState.isBuiltIn) {
        Spacer(modifier = Modifier.height(16.dp))
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(
            containerColor = FutonTheme.colors.statusWarning.copy(alpha = 0.1f),
          ),
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = FutonIcons.Info,
              contentDescription = null,
              tint = FutonTheme.colors.statusWarning,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = stringResource(R.string.prompt_editor_builtin_hint),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textNormal,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariablesSection(
  variables: List<PromptVariable>,
  onInsert: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = FutonTheme.colors.backgroundSecondary),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.prompt_editor_variables),
          style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            imageVector = if (expanded) FutonIcons.ExpandLess else FutonIcons.ExpandMore,
            contentDescription = null,
          )
        }
      }

      if (expanded) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          variables.forEach { variable ->
            AssistChip(
              onClick = { onInsert(variable.placeholder) },
              label = { Text(variable.placeholder) },
            )
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
        variables.forEach { variable ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
          ) {
            Text(
              text = variable.placeholder,
              style = MaterialTheme.typography.labelMedium,
              color = FutonTheme.colors.interactiveActive,
              modifier = Modifier.width(140.dp),
            )
            Text(
              text = stringResource(variable.descriptionRes),
              style = MaterialTheme.typography.bodySmall,
              color = FutonTheme.colors.textMuted,
            )
          }
        }
      }
    }
  }
}
