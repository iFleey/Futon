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
package me.fleey.futon.ui.designsystem.component.suggestion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier = Modifier,
  layoutType: SuggestionLayoutType = SuggestionLayoutType.Mixed,
  animated: Boolean = true,
  staggerDelay: Long = 80L,
) {
  when (layoutType) {
    SuggestionLayoutType.Flow -> FlowSuggestionLayout(
      suggestions = suggestions,
      onAction = onAction,
      modifier = modifier,
      animated = animated,
      staggerDelay = staggerDelay,
    )

    SuggestionLayoutType.Column -> ColumnSuggestionLayout(
      suggestions = suggestions,
      onAction = onAction,
      modifier = modifier,
      animated = animated,
      staggerDelay = staggerDelay,
    )

    SuggestionLayoutType.Grid -> GridSuggestionLayout(
      suggestions = suggestions,
      onAction = onAction,
      modifier = modifier,
      animated = animated,
      staggerDelay = staggerDelay,
    )

    SuggestionLayoutType.Staggered -> StaggeredSuggestionLayout(
      suggestions = suggestions,
      onAction = onAction,
      modifier = modifier,
      animated = animated,
      staggerDelay = staggerDelay,
    )

    SuggestionLayoutType.Mixed -> MixedSuggestionLayout(
      suggestions = suggestions,
      onAction = onAction,
      modifier = modifier,
      animated = animated,
      staggerDelay = staggerDelay,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowSuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier,
  animated: Boolean,
  staggerDelay: Long,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    suggestions.forEachIndexed { index, suggestion ->
      AnimatedSuggestionItem(
        suggestion = suggestion,
        onAction = onAction,
        index = index,
        animated = animated,
        staggerDelay = staggerDelay,
      )
    }
  }
}

@Composable
private fun ColumnSuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier,
  animated: Boolean,
  staggerDelay: Long,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    suggestions.forEachIndexed { index, suggestion ->
      AnimatedSuggestionItem(
        suggestion = suggestion,
        onAction = onAction,
        index = index,
        animated = animated,
        staggerDelay = staggerDelay,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun GridSuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier,
  animated: Boolean,
  staggerDelay: Long,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    suggestions.chunked(2).forEachIndexed { rowIndex, rowItems ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        rowItems.forEachIndexed { colIndex, suggestion ->
          val index = rowIndex * 2 + colIndex
          AnimatedSuggestionItem(
            suggestion = suggestion,
            onAction = onAction,
            index = index,
            animated = animated,
            staggerDelay = staggerDelay,
            modifier = Modifier.weight(1f),
          )
        }
        if (rowItems.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun StaggeredSuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier,
  animated: Boolean,
  staggerDelay: Long,
) {
  val leftColumn = suggestions.filterIndexed { index, _ -> index % 2 == 0 }
  val rightColumn = suggestions.filterIndexed { index, _ -> index % 2 == 1 }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      leftColumn.forEachIndexed { index, suggestion ->
        AnimatedSuggestionItem(
          suggestion = suggestion,
          onAction = onAction,
          index = index * 2,
          animated = animated,
          staggerDelay = staggerDelay,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Spacer(modifier = Modifier.height(24.dp))
      rightColumn.forEachIndexed { index, suggestion ->
        AnimatedSuggestionItem(
          suggestion = suggestion,
          onAction = onAction,
          index = index * 2 + 1,
          animated = animated,
          staggerDelay = staggerDelay,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MixedSuggestionLayout(
  suggestions: List<Suggestion>,
  onAction: (SuggestionAction) -> Unit,
  modifier: Modifier,
  animated: Boolean,
  staggerDelay: Long,
) {
  val largeCards = suggestions.filter { it.style == SuggestionStyle.LargeCard }
  val standardCards = suggestions.filter { it.style == SuggestionStyle.Card }
  val chips = suggestions.filter { it.style == SuggestionStyle.Chip }

  var itemIndex = 0

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (largeCards.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        largeCards.take(2).forEach { suggestion ->
          AnimatedSuggestionItem(
            suggestion = suggestion,
            onAction = onAction,
            index = itemIndex++,
            animated = animated,
            staggerDelay = staggerDelay,
            modifier = Modifier.weight(1f),
          )
        }
        if (largeCards.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }

    if (standardCards.isNotEmpty()) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        standardCards.forEach { suggestion ->
          AnimatedSuggestionItem(
            suggestion = suggestion,
            onAction = onAction,
            index = itemIndex++,
            animated = animated,
            staggerDelay = staggerDelay,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    if (chips.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        chips.forEach { suggestion ->
          AnimatedSuggestionItem(
            suggestion = suggestion,
            onAction = onAction,
            index = itemIndex++,
            animated = animated,
            staggerDelay = staggerDelay,
          )
        }
      }
    }
  }
}

@Composable
private fun AnimatedSuggestionItem(
  suggestion: Suggestion,
  onAction: (SuggestionAction) -> Unit,
  index: Int,
  animated: Boolean,
  staggerDelay: Long,
  modifier: Modifier = Modifier,
) {
  var visible by remember { mutableStateOf(!animated) }

  LaunchedEffect(Unit) {
    if (animated) {
      delay(staggerDelay * index)
      visible = true
    }
  }

  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(300)) + slideInVertically(
      animationSpec = tween(300),
      initialOffsetY = { 20 },
    ),
  ) {
    SuggestionCard(
      suggestion = suggestion,
      onAction = onAction,
      modifier = modifier,
    )
  }
}
