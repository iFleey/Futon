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
package me.fleey.futon.domain.automation

import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.daemon.models.AIDecisionMode
import me.fleey.futon.data.daemon.models.AutomationMode
import me.fleey.futon.domain.automation.models.AutomationResult
import me.fleey.futon.domain.automation.models.AutomationState
import me.fleey.futon.domain.automation.models.DaemonHealth

interface AutomationEngine {
  val state: StateFlow<AutomationState>

  val automationMode: StateFlow<AutomationMode>

  val aiDecisionMode: StateFlow<AIDecisionMode>

  val daemonHealth: StateFlow<DaemonHealth>

  suspend fun startTask(taskDescription: String): AutomationResult
  fun stopTask()
}
