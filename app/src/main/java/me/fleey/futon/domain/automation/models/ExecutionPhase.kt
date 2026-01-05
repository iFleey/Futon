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
package me.fleey.futon.domain.automation.models

enum class ExecutionPhase {
  /**
   * Capturing a screenshot of the current screen state
   */
  CAPTURING_SCREENSHOT,

  /**
   * Sending screenshot to AI and waiting for analysis
   */
  ANALYZING_WITH_AI,

  /**
   * Executing the action returned by AI (tap, swipe, input, etc.)
   */
  EXECUTING_ACTION,

  /**
   * Waiting between steps (step delay)
   */
  WAITING,

  /**
   * Retrying a failed operation
   */
  RETRYING
}
