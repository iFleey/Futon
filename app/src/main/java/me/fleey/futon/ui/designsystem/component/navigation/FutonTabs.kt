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
package me.fleey.futon.ui.designsystem.component.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.fleey.futon.ui.designsystem.component.FutonShapes
import me.fleey.futon.ui.designsystem.theme.FutonTheme

data class TabItem(
  val title: String,
  val icon: ImageVector? = null,
  val badge: String? = null,
  val enabled: Boolean = true,
)

/** Scrollable tab row with optional icons and badges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutonTabRow(
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
  tabs: List<TabItem>,
  modifier: Modifier = Modifier,
  containerColor: Color = FutonTheme.colors.backgroundSecondary,
) {
  SecondaryScrollableTabRow(
    selectedTabIndex = selectedTabIndex,
    modifier = modifier,
    containerColor = containerColor,
    contentColor = FutonTheme.colors.textNormal,
    edgePadding = 0.dp,
    indicator = {
      TabRowDefaults.SecondaryIndicator(
        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = false),
        color = MaterialTheme.colorScheme.primary,
      )
    },
    divider = {},
  ) {
    tabs.forEachIndexed { index, tabItem ->
      val selected = selectedTabIndex == index
      Tab(
        selected = selected,
        onClick = { if (tabItem.enabled) onTabSelected(index) },
        enabled = tabItem.enabled,
        modifier = Modifier.height(TabRowHeight),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = TabRowHorizontalPadding),
        ) {
          tabItem.icon?.let { icon ->
            Icon(
              imageVector = icon,
              contentDescription = null,
              modifier = Modifier.size(TabRowIconSize),
              tint = if (selected) FutonTheme.colors.textNormal else FutonTheme.colors.textMuted,
            )
            Spacer(modifier = Modifier.width(TabRowIconSpacing))
          }

          Text(
            text = tabItem.title,
            style = MaterialTheme.typography.labelLarge,
            color = when {
              !tabItem.enabled -> FutonTheme.colors.interactiveMuted
              selected -> FutonTheme.colors.textNormal
              else -> FutonTheme.colors.textMuted
            },
          )

          tabItem.badge?.let { badge ->
            Spacer(modifier = Modifier.width(TabRowBadgeSpacing))
            Surface(
              shape = FutonShapes.StatusDotShape,
              color = FutonTheme.colors.statusWarning.copy(alpha = 0.2f),
            ) {
              Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = FutonTheme.colors.statusWarning,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }
      }
    }
  }
}

private val TabRowHeight = 48.dp
private val TabRowHorizontalPadding = 16.dp
private val TabRowIconSize = 18.dp
private val TabRowIconSpacing = 8.dp
private val TabRowBadgeSpacing = 6.dp

data class SegmentedTabItem(
  @param:StringRes val titleRes: Int,
  val enabled: Boolean = true,
)

/** Capsule-style segmented tab with sliding indicator animation. */
@Composable
fun FutonSegmentedTab(
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  tabs: List<SegmentedTabItem>,
  modifier: Modifier = Modifier,
  containerColor: Color = FutonTheme.colors.backgroundTertiary,
  indicatorColor: Color = FutonTheme.colors.backgroundSecondary,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .height(SegmentedTabHeight)
      .clip(RoundedCornerShape(SegmentedTabCornerRadius))
      .background(containerColor)
      .padding(SegmentedTabPadding),
  ) {
    val tabCount = tabs.size
    val tabWidth = (maxWidth - SegmentedTabPadding * 2) / tabCount

    val indicatorOffset by animateDpAsState(
      targetValue = tabWidth * selectedIndex,
      animationSpec = tween(durationMillis = 200),
      label = "segmented_indicator_offset",
    )

    Box(
      modifier = Modifier
        .offset(x = indicatorOffset)
        .width(tabWidth)
        .height(SegmentedTabHeight - SegmentedTabPadding * 2)
        .clip(RoundedCornerShape(SegmentedIndicatorCornerRadius))
        .background(indicatorColor),
    )

    Row(modifier = Modifier.fillMaxSize()) {
      tabs.forEachIndexed { index, tab ->
        SegmentedTabButton(
          titleRes = tab.titleRes,
          selected = index == selectedIndex,
          enabled = tab.enabled,
          onClick = { onTabSelected(index) },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun SegmentedTabButton(
  @StringRes titleRes: Int,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .clip(RoundedCornerShape(SegmentedIndicatorCornerRadius))
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = Role.Tab,
        onClick = onClick,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(titleRes),
      style = MaterialTheme.typography.labelLarge,
      color = when {
        !enabled -> FutonTheme.colors.interactiveMuted
        selected -> FutonTheme.colors.textNormal
        else -> FutonTheme.colors.textMuted
      },
      textAlign = TextAlign.Center,
    )
  }
}

private val SegmentedTabHeight = 40.dp
private val SegmentedTabPadding = 4.dp
private val SegmentedTabCornerRadius = 10.dp
private val SegmentedIndicatorCornerRadius = 8.dp
