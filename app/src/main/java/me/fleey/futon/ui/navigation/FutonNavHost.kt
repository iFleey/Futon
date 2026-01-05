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

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.PopResult
import com.slack.circuit.runtime.screen.Screen
import me.fleey.futon.data.daemon.models.DaemonState
import me.fleey.futon.ui.MainViewModel
import me.fleey.futon.ui.feature.agent.AgentScreen
import me.fleey.futon.ui.feature.history.CircuitHistoryScreen
import me.fleey.futon.ui.feature.history.ExecutionLogDetailScreen
import me.fleey.futon.ui.feature.history.TaskWithPrefillScreen
import me.fleey.futon.ui.feature.localmodel.LocalModelSettingsScreen
import me.fleey.futon.ui.feature.onboarding.OnboardingScreen
import me.fleey.futon.ui.feature.settings.SettingsScreen
import me.fleey.futon.ui.feature.settings.about.AboutScreen
import me.fleey.futon.ui.feature.settings.ai.AIProviderDetailScreen
import me.fleey.futon.ui.feature.settings.ai.AIProviderSettingsScreen
import me.fleey.futon.ui.feature.settings.ai.InferenceMetricsScreen
import me.fleey.futon.ui.feature.settings.ai.InferenceRoutingSettingsScreen
import me.fleey.futon.ui.feature.settings.automation.AutomationSettingsScreen
import me.fleey.futon.ui.feature.settings.automation.ExecutionSettingsScreen
import me.fleey.futon.ui.feature.settings.automation.PrivacySettingsScreen
import me.fleey.futon.ui.feature.settings.daemon.CapabilityStatusScreen
import me.fleey.futon.ui.feature.settings.daemon.DaemonSettingsScreen
import me.fleey.futon.ui.feature.settings.integration.IntegrationsSettingsScreen
import me.fleey.futon.ui.feature.settings.perception.AppDiscoverySettingsScreen
import me.fleey.futon.ui.feature.settings.prompt.PromptEditorScreen
import me.fleey.futon.ui.feature.settings.prompt.PromptManagementScreen
import me.fleey.futon.ui.feature.settings.prompt.QuickPhraseEditorScreen
import me.fleey.futon.ui.feature.settings.ui.AppearanceSettingsScreen
import me.fleey.futon.ui.feature.settings.ui.LanguageSettingsScreen
import me.fleey.futon.ui.feature.history.ExecutionLogDetailScreen as CircuitExecutionLogDetailScreen

private const val ANIM_DURATION = 300

@Composable
fun FutonNavHost(
  navController: NavHostController,
  mainViewModel: MainViewModel,
  daemonState: DaemonState,
  onRetryConnection: () -> Unit,
  onOpenDrawer: () -> Unit,
  startDestination: FutonRoute = FutonRoute.Task,
  onOnboardingComplete: () -> Unit = {},
  onOnboardingExit: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  NavHost(
    navController = navController,
    startDestination = startDestination,
    modifier = modifier.fillMaxSize(),
    enterTransition = {
      slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(ANIM_DURATION),
      ) + fadeIn(animationSpec = tween(ANIM_DURATION))
    },
    exitTransition = {
      slideOutHorizontally(
        targetOffsetX = { -it / 3 },
        animationSpec = tween(ANIM_DURATION),
      ) + fadeOut(animationSpec = tween(ANIM_DURATION))
    },
    popEnterTransition = {
      slideInHorizontally(
        initialOffsetX = { -it / 3 },
        animationSpec = tween(ANIM_DURATION),
      ) + fadeIn(animationSpec = tween(ANIM_DURATION))
    },
    popExitTransition = {
      slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(ANIM_DURATION),
      ) + fadeOut(animationSpec = tween(ANIM_DURATION))
    },
  ) {
    composable<FutonRoute.Onboarding>(
      enterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      exitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
    ) {
      OnboardingScreen(
        onComplete = onOnboardingComplete,
        onExit = onOnboardingExit,
      )
    }

    composable<FutonRoute.Task>(
      enterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      exitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
      popEnterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      popExitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
    ) {
      AgentScreen(
        onNavigateToSettings = { navController.navigate(FutonRoute.Settings) },
        onNavigateToDaemonSettings = { navController.navigate(FutonRoute.SettingsDaemon) },
        onNavigateToLogDetail = { logId ->
          navController.navigate(
            FutonRoute.ExecutionLogDetail(
              logId,
            ),
          )
        },
        onOpenDrawer = onOpenDrawer,
        daemonState = daemonState,
        onRetryConnection = onRetryConnection,
      )
    }

    composable<FutonRoute.TaskWithPrefill> { backStackEntry ->
      val route = backStackEntry.toRoute<FutonRoute.TaskWithPrefill>()
      AgentScreen(
        onNavigateToSettings = { navController.navigate(FutonRoute.Settings) },
        onNavigateToDaemonSettings = { navController.navigate(FutonRoute.SettingsDaemon) },
        onNavigateToLogDetail = { logId ->
          navController.navigate(
            FutonRoute.ExecutionLogDetail(
              logId,
            ),
          )
        },
        onOpenDrawer = onOpenDrawer,
        prefillTask = route.prefill,
        daemonState = daemonState,
        onRetryConnection = onRetryConnection,
      )
    }

    composable<FutonRoute.History>(
      enterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      exitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
      popEnterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      popExitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
    ) {
      val navigator = androidx.compose.runtime.remember(navController) {
        object : Navigator {
          override fun goTo(screen: Screen): Boolean {
            return when (screen) {
              is TaskWithPrefillScreen -> {
                navController.navigate(FutonRoute.TaskWithPrefill(screen.task))
                true
              }

              is CircuitExecutionLogDetailScreen -> {
                navController.navigate(FutonRoute.ExecutionLogDetail(screen.logId))
                true
              }

              else -> false
            }
          }

          override fun pop(result: PopResult?): Screen? {
            navController.popBackStack()
            return null
          }

          override fun resetRoot(newRoot: Screen, options: Navigator.StateOptions): List<Screen> = emptyList()

          override fun peek(): Screen? = null
          override fun peekBackStack(): List<Screen> = emptyList()
        }
      }

      CircuitContent(
        screen = CircuitHistoryScreen,
        navigator = navigator,
      )
    }

    composable<FutonRoute.Settings>(
      enterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      exitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
      popEnterTransition = { fadeIn(animationSpec = tween(ANIM_DURATION)) },
      popExitTransition = { fadeOut(animationSpec = tween(ANIM_DURATION)) },
    ) {
      SettingsScreen(
        onNavigateToAIProvider = { navController.navigate(FutonRoute.SettingsAIProvider) },
        onNavigateToAppearance = { navController.navigate(FutonRoute.SettingsAppearance) },
        onNavigateToLanguage = { navController.navigate(FutonRoute.SettingsLanguage) },
        onNavigateToAutomation = { navController.navigate(FutonRoute.SettingsAutomation) },
        onNavigateToLocalModel = { navController.navigate(FutonRoute.SettingsLocalModel) },
        onNavigateToExecution = { navController.navigate(FutonRoute.SettingsExecution) },
        onNavigateToPrivacy = { navController.navigate(FutonRoute.SettingsPrivacy) },
        onNavigateToCapability = { navController.navigate(FutonRoute.SettingsCapability) },
        onNavigateToPromptManagement = { navController.navigate(FutonRoute.SettingsPromptManagement) },
        onNavigateToAppDiscovery = { navController.navigate(FutonRoute.SettingsAppDiscovery) },
        onNavigateToDaemon = { navController.navigate(FutonRoute.SettingsDaemon) },
        onNavigateToInferenceRouting = { navController.navigate(FutonRoute.SettingsInferenceRouting) },
        onNavigateToInferenceMetrics = { navController.navigate(FutonRoute.SettingsInferenceMetrics) },
        onNavigateToIntegrations = { navController.navigate(FutonRoute.SettingsIntegrations) },
        onNavigateToSomSettings = { navController.navigate(FutonRoute.SettingsSom) },
        onNavigateToDebugDashboard = { navController.navigate(FutonRoute.DebugDashboard) },
        onNavigateToDebugSecurityLogs = { navController.navigate(FutonRoute.DebugSecurityLogs) },
        onNavigateToDebugChaosTesting = { navController.navigate(FutonRoute.DebugChaosTesting) },
        onNavigateToAbout = { navController.navigate(FutonRoute.SettingsAbout) },
      )
    }

    composable<FutonRoute.SettingsAIProvider> {
      AIProviderSettingsScreen(
        onBack = { navController.popBackStack() },
        onNavigateToProviderDetail = { providerId ->
          navController.navigate(FutonRoute.SettingsAIProviderDetail(providerId))
        },
      )
    }

    composable<FutonRoute.SettingsAIProviderDetail> { backStackEntry ->
      val route = backStackEntry.toRoute<FutonRoute.SettingsAIProviderDetail>()
      AIProviderDetailScreen(
        providerId = route.providerId,
        onBack = { navController.popBackStack() },
      )
    }

    composable<FutonRoute.SettingsAppearance> {
      AppearanceSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsLanguage> {
      LanguageSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsAutomation> {
      AutomationSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsLocalModel> {
      LocalModelSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsExecution> {
      ExecutionSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsPrivacy> {
      PrivacySettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsCapability> {
      CapabilityStatusScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsSom> {
      me.fleey.futon.ui.feature.settings.perception.SomSettingsScreen(
        onBack = { navController.popBackStack() },
      )
    }

    composable<FutonRoute.SettingsAppDiscovery> {
      AppDiscoverySettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsDaemon> {
      DaemonSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsInferenceRouting> {
      InferenceRoutingSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsInferenceMetrics> {
      InferenceMetricsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsIntegrations> {
      IntegrationsSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsAbout> {
      AboutScreen(onBack = { navController.popBackStack() })
    }

    composable<FutonRoute.SettingsPromptManagement> {
      PromptManagementScreen(
        onBack = { navController.popBackStack() },
        onEditTemplate = { templateId ->
          navController.navigate(FutonRoute.SettingsPromptEditor(templateId))
        },
        onEditQuickPhrase = { phraseId ->
          navController.navigate(FutonRoute.SettingsQuickPhraseEditor(phraseId))
        },
      )
    }

    composable<FutonRoute.SettingsPromptEditor> { backStackEntry ->
      val route = backStackEntry.toRoute<FutonRoute.SettingsPromptEditor>()
      PromptEditorScreen(
        templateId = route.templateId,
        onBack = { navController.popBackStack() },
      )
    }

    composable<FutonRoute.SettingsQuickPhraseEditor> { backStackEntry ->
      val route = backStackEntry.toRoute<FutonRoute.SettingsQuickPhraseEditor>()
      QuickPhraseEditorScreen(
        phraseId = route.phraseId,
        onBack = { navController.popBackStack() },
      )
    }

    composable<FutonRoute.ExecutionLogDetail> { backStackEntry ->
      val route = backStackEntry.toRoute<FutonRoute.ExecutionLogDetail>()
      ExecutionLogDetailScreen(
        logId = route.logId,
        onBack = { navController.popBackStack() },
      )
    }

    // Debug screens (debug builds only)
    debugNavigation(navController)
  }
}
