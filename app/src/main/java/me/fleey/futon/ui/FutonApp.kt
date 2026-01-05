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
package me.fleey.futon.ui

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.fleey.futon.ui.designsystem.components.NotificationPermissionHandler
import me.fleey.futon.ui.designsystem.components.NotificationPermissionState
import me.fleey.futon.ui.designsystem.components.checkNotificationPermission
import me.fleey.futon.ui.designsystem.components.isNotificationPermissionRequired
import me.fleey.futon.ui.feature.onboarding.OnboardingViewModel
import me.fleey.futon.ui.navigation.DrawerDestination
import me.fleey.futon.ui.navigation.FutonDrawerContent
import me.fleey.futon.ui.navigation.FutonNavHost
import me.fleey.futon.ui.navigation.FutonRoute
import org.koin.compose.koinInject

@Composable
fun FutonApp(
  mainViewModel: MainViewModel,
  navController: NavHostController = rememberNavController(),
) {
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val context = LocalContext.current
  val dataStore: DataStore<Preferences> = koinInject()
  val circuit: com.slack.circuit.foundation.Circuit = koinInject()

  val daemonState by mainViewModel.daemonState.collectAsState()

  var onboardingCompleted by remember { mutableStateOf<Boolean?>(null) }
  var isCheckingOnboarding by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    val completed = dataStore.data.map { prefs ->
      prefs[OnboardingViewModel.KEY_ONBOARDING_COMPLETED] ?: false
    }.first()
    onboardingCompleted = completed
    isCheckingOnboarding = false

    if (completed) {
      mainViewModel.onEvent(MainUiEvent.RetryDaemonConnection)
    }
  }

  val startDestination: FutonRoute = remember(onboardingCompleted) {
    if (onboardingCompleted == false) FutonRoute.Onboarding else FutonRoute.Task
  }

  var shouldRequestNotificationPermission by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (isNotificationPermissionRequired()) {
      val permissionState = checkNotificationPermission(context)
      shouldRequestNotificationPermission = permissionState == NotificationPermissionState.Denied
    }
  }

  if (isCheckingOnboarding) {
    return
  }

  NotificationPermissionHandler(
    requestOnLaunch = shouldRequestNotificationPermission,
    onPermissionResult = { _ ->
      shouldRequestNotificationPermission = false
    },
  ) { _, _, _ ->

    val currentRoute by remember(navBackStackEntry) {
      derivedStateOf {
        navBackStackEntry?.destination?.route?.let { route ->
          parseRoute(route)
        }
      }
    }

    val selectedDrawerDestination by remember(currentRoute) {
      derivedStateOf {
        when (currentRoute) {
          FutonRoute.History -> DrawerDestination.HISTORY
          FutonRoute.Settings,
          FutonRoute.SettingsAIProvider,
          FutonRoute.SettingsAppearance,
          FutonRoute.SettingsAutomation,
          FutonRoute.SettingsAdvanced,
          FutonRoute.SettingsSom,
          FutonRoute.SettingsLocalModel,
          FutonRoute.SettingsExecution,
          FutonRoute.SettingsPrivacy,
          FutonRoute.SettingsCapability,
            -> DrawerDestination.SETTINGS

          else -> null
        }
      }
    }

    val gesturesEnabled by remember(currentRoute) {
      derivedStateOf {
        currentRoute in listOf(
          FutonRoute.Task,
          FutonRoute.History,
          FutonRoute.Settings,
        ) || currentRoute is FutonRoute.TaskWithPrefill
      }
    }

    val isOnboarding = currentRoute == FutonRoute.Onboarding

    if (isOnboarding) {
      FutonNavHost(
        navController = navController,
        mainViewModel = mainViewModel,
        daemonState = daemonState,
        startDestination = startDestination,
        onRetryConnection = { mainViewModel.onEvent(MainUiEvent.RetryDaemonConnection) },
        onOpenDrawer = { },
        onOnboardingComplete = {
          onboardingCompleted = true
          mainViewModel.onEvent(MainUiEvent.RetryDaemonConnection)
          navController.navigate(FutonRoute.Task) {
            popUpTo(FutonRoute.Onboarding) { inclusive = true }
          }
        },
        onOnboardingExit = {
          (context as? Activity)?.finish()
        },
      )
      return@NotificationPermissionHandler
    }

    com.slack.circuit.foundation.CircuitCompositionLocals(circuit) {
      ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
          FutonDrawerContent(
            selectedDestination = selectedDrawerDestination,
            onNavigateToHistory = {
              scope.launch {
                drawerState.close()
                navController.navigate(FutonRoute.History) {
                  popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                  }
                  launchSingleTop = true
                  restoreState = true
                }
              }
            },
            onNavigateToSettings = {
              scope.launch {
                drawerState.close()
                navController.navigate(FutonRoute.Settings) {
                  popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                  }
                  launchSingleTop = true
                  restoreState = true
                }
              }
            },
            onNewConversation = {
              scope.launch {
                drawerState.close()
                mainViewModel.clearConversation()
                val isOnTaskScreen = currentRoute == FutonRoute.Task ||
                  currentRoute is FutonRoute.TaskWithPrefill
                if (!isOnTaskScreen) {
                  navController.navigate(FutonRoute.Task) {
                    popUpTo(navController.graph.findStartDestination().id) {
                      saveState = true
                    }
                    launchSingleTop = true
                  }
                }
              }
            },
            onConversationClick = { historyItem ->
              scope.launch {
                drawerState.close()
                mainViewModel.loadConversation(historyItem)
                val isOnTaskScreen = currentRoute == FutonRoute.Task ||
                  currentRoute is FutonRoute.TaskWithPrefill
                if (!isOnTaskScreen) {
                  navController.navigate(FutonRoute.Task) {
                    popUpTo(navController.graph.findStartDestination().id) {
                      saveState = true
                    }
                    launchSingleTop = true
                  }
                }
              }
            },
          )
        },
        modifier = Modifier.fillMaxSize(),
      ) {
        FutonNavHost(
          navController = navController,
          mainViewModel = mainViewModel,
          daemonState = daemonState,
          startDestination = startDestination,
          onRetryConnection = { mainViewModel.onEvent(MainUiEvent.RetryDaemonConnection) },
          onOpenDrawer = { scope.launch { drawerState.open() } },
          onOnboardingComplete = {
            onboardingCompleted = true
            mainViewModel.onEvent(MainUiEvent.RetryDaemonConnection)
            navController.navigate(FutonRoute.Task) {
              popUpTo(FutonRoute.Onboarding) { inclusive = true }
            }
          },
          onOnboardingExit = {
            (context as? Activity)?.finish()
          },
        )
      }
    }
  }
}

private fun parseRoute(route: String): FutonRoute? {
  return when {
    route.contains("Onboarding") -> FutonRoute.Onboarding
    route.contains("Task") && route.contains("prefill") -> null
    route.contains("FutonRoute.Task") || route == "me.fleey.futon.ui.navigation.FutonRoute.Task" -> FutonRoute.Task
    route.contains("FutonRoute.History") || route == "me.fleey.futon.ui.navigation.FutonRoute.History" -> FutonRoute.History
    route.contains("FutonRoute.Settings") && !route.contains("Settings.") -> FutonRoute.Settings
    route.contains("SettingsAIProvider") -> FutonRoute.SettingsAIProvider
    route.contains("SettingsAppearance") -> FutonRoute.SettingsAppearance
    route.contains("SettingsAutomation") -> FutonRoute.SettingsAutomation
    route.contains("SettingsAdvanced") -> FutonRoute.SettingsAdvanced
    route.contains("SettingsSom") -> FutonRoute.SettingsSom
    route.contains("SettingsLocalModel") -> FutonRoute.SettingsLocalModel
    route.contains("SettingsExecution") -> FutonRoute.SettingsExecution
    route.contains("SettingsPrivacy") -> FutonRoute.SettingsPrivacy
    route.contains("SettingsCapability") -> FutonRoute.SettingsCapability
    route.contains("ExecutionLogDetail") -> FutonRoute.ExecutionLogDetail("")
    else -> null
  }
}
