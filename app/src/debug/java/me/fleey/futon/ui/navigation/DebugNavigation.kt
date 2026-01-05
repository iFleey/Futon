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
package me.fleey.futon.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import me.fleey.futon.ui.feature.debug.ChaosTestingScreen
import me.fleey.futon.ui.feature.debug.DebugDashboardScreen
import me.fleey.futon.ui.feature.debug.SecurityLogsScreen

fun NavGraphBuilder.debugNavigation(navController: NavHostController) {
  composable<FutonRoute.DebugDashboard> {
    DebugDashboardScreen(onBack = { navController.popBackStack() })
  }

  composable<FutonRoute.DebugSecurityLogs> {
    SecurityLogsScreen(onBack = { navController.popBackStack() })
  }

  composable<FutonRoute.DebugChaosTesting> {
    ChaosTestingScreen(onBack = { navController.popBackStack() })
  }
}

const val isDebugBuild: Boolean = true
