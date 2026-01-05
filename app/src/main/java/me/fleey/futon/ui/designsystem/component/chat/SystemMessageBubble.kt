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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.ui.feature.agent.models.SystemMessageType

@Composable
fun SystemMessageBubble(
  type: SystemMessageType,
  content: String,
  onActionClick: (() -> Unit)?,
  onExampleClick: ((String) -> Unit)?,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .clip(RoundedCornerShape(20.dp))
        .background(FutonTheme.colors.backgroundSecondary.copy(alpha = 0.6f))
        .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      when (type) {
        SystemMessageType.WELCOME -> WelcomeContent(
          content = content,
          onExampleClick = onExampleClick,
        )

        SystemMessageType.SETTINGS_REQUIRED -> SettingsRequiredContent(
          content = content,
          onActionClick = onActionClick,
        )

        SystemMessageType.CLEARED -> ClearedContent(content = content)
      }
    }
  }
}

@Composable
fun SettingsRequiredCard(
  message: String,
  onSettingsClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    SetupRequiredHeader()

    Spacer(modifier = Modifier.height(48.dp))

    SetupActionCard(
      message = message,
      onSettingsClick = onSettingsClick,
    )
  }
}

@Composable
private fun SetupRequiredHeader() {
  Box(
    modifier = Modifier
      .size(72.dp)
      .clip(RoundedCornerShape(20.dp))
      .background(FutonTheme.colors.statusWarning.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = FutonIcons.Settings,
      contentDescription = null,
      modifier = Modifier.size(36.dp),
      tint = FutonTheme.colors.statusWarning,
    )
  }

  Text(
    text = stringResource(R.string.settings_required_title),
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    color = FutonTheme.colors.textNormal,
  )

  Text(
    text = stringResource(R.string.settings_required_subtitle),
    style = MaterialTheme.typography.bodyMedium,
    color = FutonTheme.colors.textMuted,
    textAlign = TextAlign.Center,
  )
}

@Composable
private fun SetupActionCard(
  message: String,
  onSettingsClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = FutonTheme.colors.backgroundSecondary,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
          modifier = Modifier.size(44.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = FutonIcons.AI,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.setup_ai_provider),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = FutonTheme.colors.textNormal,
          )
          Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = FutonTheme.colors.textMuted,
          )
        }
      }

      FilledTonalButton(
        onClick = onSettingsClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(
          imageVector = FutonIcons.Settings,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = stringResource(R.string.go_to_settings),
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun WelcomeContent(
  content: String,
  onExampleClick: ((String) -> Unit)?,
) {
  val exampleTasks = listOf(
    stringResource(R.string.example_task_camera),
    stringResource(R.string.example_task_browser),
    stringResource(R.string.example_task_settings),
  )

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = FutonIcons.AI,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = stringResource(R.string.welcome_title),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      color = FutonTheme.colors.textNormal,
    )
  }

  Spacer(modifier = Modifier.height(8.dp))

  Text(
    text = content.ifEmpty { stringResource(R.string.welcome_message) },
    style = MaterialTheme.typography.bodyMedium,
    color = FutonTheme.colors.textMuted,
    textAlign = TextAlign.Center,
  )

  Spacer(modifier = Modifier.height(16.dp))

  Text(
    text = stringResource(R.string.welcome_try_examples),
    style = MaterialTheme.typography.labelMedium,
    color = FutonTheme.colors.textMuted,
  )

  Spacer(modifier = Modifier.height(8.dp))

  ExampleTaskChips(
    examples = exampleTasks,
    onExampleClick = onExampleClick,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExampleTaskChips(
  examples: List<String>,
  onExampleClick: ((String) -> Unit)?,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    examples.forEach { example ->
      AssistChip(
        onClick = { onExampleClick?.invoke(example) },
        label = {
          Text(
            text = example,
            style = MaterialTheme.typography.labelMedium,
          )
        },
        colors = AssistChipDefaults.assistChipColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
          labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      )
    }
  }
}

@Composable
private fun SettingsRequiredContent(
  content: String,
  onActionClick: (() -> Unit)?,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = FutonIcons.Warning,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = FutonTheme.colors.statusWarning,
    )
    Text(
      text = stringResource(R.string.settings_required_title),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      color = FutonTheme.colors.textNormal,
    )
  }

  Spacer(modifier = Modifier.height(8.dp))

  Text(
    text = content.ifEmpty { stringResource(R.string.settings_required_message) },
    style = MaterialTheme.typography.bodyMedium,
    color = FutonTheme.colors.textMuted,
    textAlign = TextAlign.Center,
  )

  Spacer(modifier = Modifier.height(16.dp))

  FilledTonalButton(onClick = { onActionClick?.invoke() }) {
    Icon(
      imageVector = FutonIcons.Settings,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = stringResource(R.string.go_to_settings),
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

@Composable
private fun ClearedContent(content: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = FutonIcons.Clear,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = FutonTheme.colors.textMuted,
    )
    Text(
      text = content.ifEmpty { stringResource(R.string.conversation_cleared) },
      style = MaterialTheme.typography.bodyMedium,
      color = FutonTheme.colors.textMuted,
    )
  }
}
