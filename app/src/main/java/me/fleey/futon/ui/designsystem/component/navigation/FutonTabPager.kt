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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.fleey.futon.ui.designsystem.theme.FutonTheme

sealed interface TabStatus {
  data object None : TabStatus
  data object Active : TabStatus
  data object Warning : TabStatus
  data object Error : TabStatus
}

data class PagerTabItem(
  @param:StringRes val titleRes: Int? = null,
  val title: String? = null,
  val status: TabStatus = TabStatus.None,
  val enabled: Boolean = true,
) {
  init {
    require(titleRes != null || title != null) { "Either titleRes or title must be provided" }
  }

  @Composable
  fun getTitle(): String = titleRes?.let { stringResource(it) } ?: title ?: ""
}

@Composable
fun FutonTabPagerWithState(
  pagerState: PagerState,
  tabs: List<PagerTabItem>,
  modifier: Modifier = Modifier,
  tabsModifier: Modifier = Modifier,
  containerColor: Color = FutonTheme.colors.backgroundTertiary,
  indicatorColor: Color = FutonTheme.colors.backgroundSecondary,
  userScrollEnabled: Boolean = true,
  pageContent: @Composable (index: Int) -> Unit,
) {
  val scope = rememberCoroutineScope()

  Column(modifier = modifier) {
    FutonPagerTabs(
      selectedIndex = pagerState.currentPage,
      onTabSelected = { index ->
        scope.launch { pagerState.animateScrollToPage(index) }
      },
      tabs = tabs,
      modifier = tabsModifier,
      containerColor = containerColor,
      indicatorColor = indicatorColor,
    )

    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
      userScrollEnabled = userScrollEnabled,
    ) { page ->
      pageContent(page)
    }
  }
}

@Composable
fun FutonPagerTabs(
  selectedIndex: Int,
  onTabSelected: (Int) -> Unit,
  tabs: List<PagerTabItem>,
  modifier: Modifier = Modifier,
  containerColor: Color = FutonTheme.colors.backgroundTertiary,
  indicatorColor: Color = FutonTheme.colors.backgroundSecondary,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .height(PagerTabHeight)
      .clip(RoundedCornerShape(PagerTabCornerRadius))
      .background(containerColor)
      .padding(PagerTabPadding),
  ) {
    val tabCount = tabs.size
    val tabWidth = (maxWidth - PagerTabPadding * 2) / tabCount

    val indicatorOffset by animateDpAsState(
      targetValue = tabWidth * selectedIndex,
      animationSpec = tween(durationMillis = 200),
      label = "pager_tab_indicator",
    )

    Box(
      modifier = Modifier
        .offset(x = indicatorOffset)
        .width(tabWidth)
        .height(PagerTabHeight - PagerTabPadding * 2)
        .clip(RoundedCornerShape(PagerIndicatorCornerRadius))
        .background(indicatorColor),
    )

    Row(modifier = Modifier.fillMaxSize()) {
      tabs.forEachIndexed { index, tab ->
        PagerTabButton(
          tab = tab,
          selected = index == selectedIndex,
          onClick = { onTabSelected(index) },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
fun FutonPagerTabsWithState(
  pagerState: PagerState,
  tabs: List<PagerTabItem>,
  modifier: Modifier = Modifier,
  containerColor: Color = FutonTheme.colors.backgroundTertiary,
  indicatorColor: Color = FutonTheme.colors.backgroundSecondary,
) {
  val scope = rememberCoroutineScope()

  FutonPagerTabs(
    selectedIndex = pagerState.currentPage,
    onTabSelected = { index ->
      scope.launch { pagerState.animateScrollToPage(index) }
    },
    tabs = tabs,
    modifier = modifier,
    containerColor = containerColor,
    indicatorColor = indicatorColor,
  )
}

@Composable
private fun RowScope.PagerTabButton(
  tab: PagerTabItem,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .clip(RoundedCornerShape(PagerIndicatorCornerRadius))
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = tab.enabled,
        role = Role.Tab,
        onClick = onClick,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = tab.getTitle(),
        style = MaterialTheme.typography.labelLarge,
        color = when {
          !tab.enabled -> FutonTheme.colors.interactiveMuted
          selected -> FutonTheme.colors.textNormal
          else -> FutonTheme.colors.textMuted
        },
        textAlign = TextAlign.Center,
      )

      if (tab.status != TabStatus.None) {
        Spacer(modifier = Modifier.width(6.dp))
        StatusIndicator(status = tab.status)
      }
    }
  }
}

@Composable
private fun StatusIndicator(
  status: TabStatus,
  modifier: Modifier = Modifier,
) {
  val color = when (status) {
    TabStatus.None -> return
    TabStatus.Active -> FutonTheme.colors.statusPositive
    TabStatus.Warning -> FutonTheme.colors.statusWarning
    TabStatus.Error -> FutonTheme.colors.statusDanger
  }

  Box(
    modifier = modifier
      .size(StatusIndicatorSize)
      .clip(CircleShape)
      .background(color),
  )
}

private val PagerTabHeight = 40.dp
private val PagerTabPadding = 4.dp
private val PagerTabCornerRadius = 10.dp
private val PagerIndicatorCornerRadius = 8.dp
private val StatusIndicatorSize = 6.dp
