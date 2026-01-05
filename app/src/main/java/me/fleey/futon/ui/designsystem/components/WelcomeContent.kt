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
package me.fleey.futon.ui.designsystem.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.suggestion.Suggestion
import me.fleey.futon.ui.designsystem.component.suggestion.SuggestionAction
import me.fleey.futon.ui.designsystem.component.suggestion.SuggestionLayout
import me.fleey.futon.ui.designsystem.component.suggestion.SuggestionLayoutType
import me.fleey.futon.ui.designsystem.component.suggestion.SuggestionStyle
import me.fleey.futon.ui.designsystem.component.suggestion.WelcomeHeaderAnimated

private val defaultTitles = listOf(
  R.string.welcome_greeting_1,
  R.string.welcome_greeting_2,
  R.string.welcome_greeting_3,
)

private data class IdeaChip(
  val labelRes: Int,
  val actionRes: Int,
)

private val ideaChips = listOf(
  IdeaChip(R.string.example_task_idea_1, R.string.example_task_idea_1_action),
  IdeaChip(R.string.example_task_idea_2, R.string.example_task_idea_2_action),
  IdeaChip(R.string.example_task_idea_3, R.string.example_task_idea_3_action),
  IdeaChip(R.string.example_task_idea_4, R.string.example_task_idea_4_action),
  IdeaChip(R.string.example_task_idea_5, R.string.example_task_idea_5_action),
  IdeaChip(R.string.example_task_idea_6, R.string.example_task_idea_6_action),
)

private fun createDefaultSuggestions(randomChips: List<IdeaChip>) = listOf(
  Suggestion(
    id = "camera",
    labelRes = R.string.example_task_camera,
    descriptionRes = R.string.example_task_camera_desc,
    icon = Icons.Outlined.CameraAlt,
    action = SuggestionAction.SendTextRes(R.string.example_task_camera_action),
    style = SuggestionStyle.LargeCard,
  ),
  Suggestion(
    id = "browser",
    labelRes = R.string.example_task_browser,
    descriptionRes = R.string.example_task_browser_desc,
    icon = Icons.Outlined.Language,
    action = SuggestionAction.SendTextRes(R.string.example_task_browser_action),
    style = SuggestionStyle.LargeCard,
  ),
  Suggestion(
    id = "settings",
    labelRes = R.string.example_task_settings,
    descriptionRes = R.string.example_task_settings_desc,
    icon = Icons.Outlined.Settings,
    action = SuggestionAction.SendTextRes(R.string.example_task_settings_action),
    style = SuggestionStyle.Card,
  ),
) + randomChips.mapIndexed { index, chip ->
  Suggestion(
    id = "idea_$index",
    labelRes = chip.labelRes,
    icon = Icons.Outlined.Lightbulb,
    action = SuggestionAction.SendTextRes(chip.actionRes),
    style = SuggestionStyle.Chip,
  )
}

@Composable
fun WelcomeContent(
  onSuggestionClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  titles: List<Int> = defaultTitles,
  layoutType: SuggestionLayoutType = SuggestionLayoutType.Mixed,
) {
  val context = LocalContext.current

  val suggestions = remember {
    val randomChips = ideaChips.shuffled().take(2)
    createDefaultSuggestions(randomChips)
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp),
    ) {
      WelcomeHeaderAnimated(
        titles = titles,
        subtitleRes = R.string.welcome_subtitle,
      )

      Spacer(modifier = Modifier.height(48.dp))

      SuggestionLayout(
        suggestions = suggestions,
        onAction = { action ->
          handleSuggestionAction(action, context, onSuggestionClick)
        },
        layoutType = layoutType,
        animated = true,
        staggerDelay = 100L,
      )
    }
  }
}

private fun handleSuggestionAction(
  action: SuggestionAction,
  context: Context,
  onSuggestionClick: (String) -> Unit,
) {
  when (action) {
    is SuggestionAction.SendText -> onSuggestionClick(action.text)
    is SuggestionAction.SendTextRes -> onSuggestionClick(context.getString(action.textRes))
    is SuggestionAction.Custom -> {}
  }
}
