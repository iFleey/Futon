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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

sealed interface SuggestionAction {
  data class SendText(val text: String) : SuggestionAction
  data class SendTextRes(@param:StringRes val textRes: Int) : SuggestionAction
  data class Custom(val id: String, val payload: Any? = null) : SuggestionAction
}

data class Suggestion(
  val id: String,
  @param:StringRes val labelRes: Int,
  @param:StringRes val descriptionRes: Int? = null,
  val icon: ImageVector? = null,
  @param:DrawableRes val iconRes: Int? = null,
  val iconTint: Color? = null,
  val action: SuggestionAction,
  val style: SuggestionStyle = SuggestionStyle.Card,
)

enum class SuggestionStyle {
  Chip,
  Card,
  LargeCard
}

enum class SuggestionLayoutType {
  Flow,
  Column,
  Grid,
  Staggered,
  Mixed
}
