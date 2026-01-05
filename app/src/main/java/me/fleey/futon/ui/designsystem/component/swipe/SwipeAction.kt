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
package me.fleey.futon.ui.designsystem.component.swipe

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import me.fleey.futon.ui.designsystem.component.FutonIcons

@Immutable
data class SwipeAction(
  val icon: ImageVector,
  val backgroundColor: Color,
  val iconTint: Color = Color.White,
  val onAction: () -> Unit,
  val weight: Float = 1f,
) {
  companion object {
    fun delete(
      onDelete: () -> Unit,
      backgroundColor: Color,
      iconTint: Color = Color.White,
    ) = SwipeAction(
      icon = FutonIcons.Delete,
      backgroundColor = backgroundColor,
      iconTint = iconTint,
      onAction = onDelete,
    )

    fun archive(
      onArchive: () -> Unit,
      backgroundColor: Color,
      iconTint: Color = Color.White,
    ) = SwipeAction(
      icon = FutonIcons.Archive,
      backgroundColor = backgroundColor,
      iconTint = iconTint,
      onAction = onArchive,
    )
  }
}
