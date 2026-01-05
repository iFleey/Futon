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
package me.fleey.futon.ui.designsystem.component.datetime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import me.fleey.futon.R
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.toDuration

sealed interface RelativeTimeState {
  data object JustNow : RelativeTimeState
  data class MinutesAgo(val minutes: Int) : RelativeTimeState
  data class HoursAgo(val hours: Int) : RelativeTimeState
  data class DaysAgo(val days: Int) : RelativeTimeState
  data class ExactDate(val month: Int, val day: Int) : RelativeTimeState
}

fun calculateRelativeTime(timestampMillis: Long, nowMillis: Long): RelativeTimeState {
  val diff = (nowMillis - timestampMillis).coerceAtLeast(0).toDuration(DurationUnit.MILLISECONDS)

  return when {
    diff < 1.minutes -> RelativeTimeState.JustNow
    diff < 60.minutes -> RelativeTimeState.MinutesAgo(diff.inWholeMinutes.toInt())
    diff < 24.hours -> RelativeTimeState.HoursAgo(diff.inWholeHours.toInt())
    diff < 7.days -> RelativeTimeState.DaysAgo(diff.inWholeDays.toInt())
    else -> {
      val dateTime = Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
      RelativeTimeState.ExactDate(dateTime.monthValue, dateTime.dayOfMonth)
    }
  }
}

@Composable
fun formatRelativeTime(
  timestampMillis: Long,
  autoRefresh: Boolean = false,
): String {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  if (autoRefresh) {
    LaunchedEffect(timestampMillis) {
      while (true) {
        delay(60_000L)
        now = System.currentTimeMillis()
      }
    }
  }

  val timeState = remember(timestampMillis, now) {
    calculateRelativeTime(timestampMillis, now)
  }

  return when (timeState) {
    is RelativeTimeState.JustNow -> stringResource(R.string.time_just_now)

    is RelativeTimeState.MinutesAgo -> pluralStringResource(
      id = R.plurals.time_minutes_ago,
      count = timeState.minutes,
      timeState.minutes,
    )

    is RelativeTimeState.HoursAgo -> pluralStringResource(
      id = R.plurals.time_hours_ago,
      count = timeState.hours,
      timeState.hours,
    )

    is RelativeTimeState.DaysAgo -> pluralStringResource(
      id = R.plurals.time_days_ago,
      count = timeState.days,
      timeState.days,
    )

    is RelativeTimeState.ExactDate -> stringResource(
      R.string.time_date_format,
      timeState.month,
      timeState.day,
    )
  }
}
