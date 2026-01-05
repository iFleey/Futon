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
package me.fleey.futon.ui.designsystem.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import sv.lib.squircleshape.SquircleShape

/** Unified shape definitions for Futon UI components using Squircle shapes. */
object FutonShapes {
  /** Shape for buttons - subtle squircle */
  val ButtonShape: Shape = SquircleShape(8.dp)

  /** Shape for cards - moderate squircle */
  val CardShape: Shape = SquircleShape(16.dp)

  /** Shape for large cards (welcome cards, suggestion cards) */
  val LargeCardShape: Shape = SquircleShape(20.dp)

  /** Shape for input fields - subtle squircle */
  val InputShape: Shape = SquircleShape(8.dp)

  /** Shape for dialogs - moderate squircle */
  val DialogShape: Shape = SquircleShape(12.dp)

  /** Shape for status indicators - small squircle */
  val StatusDotShape: Shape = SquircleShape(4.dp)

  /** Shape for progress bars - subtle rounding */
  val ProgressBarShape: Shape = RoundedCornerShape(2.dp)

  /** Shape for search bar */
  val SearchBarShape: Shape = SquircleShape(12.dp)

  /** Shape for Material 3 search bar - pill shape */
  val M3SearchBarShape: Shape = SquircleShape(28.dp)

  /** Shape for checkbox - subtle squircle */
  val CheckboxShape: Shape = SquircleShape(6.dp)

  /** Shape for chips */
  val ChipShape: Shape = SquircleShape(12.dp)

  /** Shape for icon containers */
  val IconContainerShape: Shape = SquircleShape(12.dp)

  /** Shape for message bubbles */
  val BubbleShape: Shape = SquircleShape(16.dp)

  /** Shape for FAB */
  val FabShape: Shape = SquircleShape(16.dp)

  /** Shape for bottom sheet */
  val BottomSheetShape: Shape = SquircleShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
  )
}

/** Unified size definitions for Futon UI components. */
object FutonSizes {
  // Header sizes
  val MinimalHeaderHeight = 44.dp

  // Button sizes
  val ButtonHeight = 40.dp
  val ButtonHorizontalPadding = 16.dp
  val ButtonIconSpacing = 8.dp

  // Icon sizes
  val IconSize = 20.dp
  val SmallIconSize = 18.dp
  val LargeIconSize = 48.dp
  val ButtonLoadingSize = 16.dp
  val ResultIconSize = 24.dp

  // Card sizes
  val CardPadding = 16.dp
  val CardElementSpacing = 12.dp
  val StatusDotSize = 8.dp

  // List item sizes
  val ListItemHorizontalPadding = 16.dp
  val ListItemVerticalPadding = 14.dp
  val ListItemIconSpacing = 12.dp

  // Selection control sizes
  val RadioButtonSize = 20.dp
  val RadioButtonInnerSize = 10.dp
  val CheckboxSize = 20.dp

  // Search bar sizes
  val SearchBarHeight = 40.dp
  val SearchBarHorizontalPadding = 12.dp
  val SearchBarIconSize = 20.dp

  // Material 3 Search bar sizes
  val M3SearchBarHeight = 56.dp
  val M3SearchBarCornerRadius = 28.dp

  // Input sizes
  val InputLabelSpacing = 8.dp
  val InputErrorSpacing = 4.dp

  // Progress sizes
  val ProgressBarHeight = 4.dp
  val ProgressTextSpacing = 8.dp

  // Empty state sizes
  val EmptyStatePadding = 32.dp
  val EmptyStateSpacing = 16.dp
  val EmptyStateSmallSpacing = 4.dp

  // Stroke widths
  val LoadingStrokeWidth = 2.dp
}
