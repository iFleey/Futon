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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized icon management for Futon UI.
 *
 * All icons used in the app should be accessed through this object
 * to ensure consistency and easy maintenance.
 *
 * Icons are organized by category:
 * - Navigation: Icons for app navigation
 * - Actions: Icons for user actions
 * - Status: Icons for status indicators
 * - Settings: Icons for settings-related items
 * - Visibility: Icons for show/hide toggles
 * - Empty States: Icons for empty content states
 * - UI Controls: Icons for UI control elements
 */
object FutonIcons {

  // Navigation
  // Icons used for app navigation and routing

  /** Back navigation arrow */
  val Back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack

  /** Home screen icon */
  val Home: ImageVector = Icons.Rounded.Home

  /** Settings screen icon */
  val Settings: ImageVector = Icons.Rounded.Settings

  /** History screen icon */
  val History: ImageVector = Icons.Rounded.History

  /** Task screen icon */
  val Task: ImageVector = Icons.Rounded.PlayArrow

  // Actions
  // Icons for user-triggered actions

  /** Search action */
  val Search: ImageVector = Icons.Rounded.Search

  /** Play/start action */
  val Play: ImageVector = Icons.Rounded.PlayArrow

  /** Stop action */
  val Stop: ImageVector = Icons.Rounded.Stop

  /** Refresh/reload action */
  val Refresh: ImageVector = Icons.Rounded.Refresh

  /** Send message action */
  val Send: ImageVector = Icons.AutoMirrored.Rounded.Send

  /** Add/create action */
  val Add: ImageVector = Icons.Rounded.Add

  /** Delete/remove action */
  val Delete: ImageVector = Icons.Rounded.Delete

  /** Edit/modify action */
  val Edit: ImageVector = Icons.Rounded.Edit

  /** Save action */
  val Save: ImageVector = Icons.Rounded.Save

  /** Close/dismiss action */
  val Close: ImageVector = Icons.Rounded.Close

  /** Copy action */
  val Copy: ImageVector = Icons.Rounded.ContentCopy

  /** Confirm/check action */
  val Check: ImageVector = Icons.Rounded.Check

  /** Retry action */
  val Retry: ImageVector = Icons.Rounded.Replay

  /** Archive action */
  val Archive: ImageVector = Icons.Rounded.Archive

  /** Select all action */
  val SelectAll: ImageVector = Icons.Rounded.SelectAll

  /** Deselect all action */
  val DeselectAll: ImageVector = Icons.Rounded.Deselect

  /** Attach file action */
  val AttachFile: ImageVector = Icons.Rounded.AttachFile

  /** Image/photo icon */
  val Image: ImageVector = Icons.Rounded.Image

  /** Crop/bounding box icon */
  val Crop: ImageVector = Icons.Rounded.Image

  /** Text size icon */
  val TextSize: ImageVector = Icons.Rounded.Style

  /** List icon */
  val List: ImageVector = Icons.Rounded.FilterList

  // Status
  // Icons for status indicators and feedback

  /** Success status */
  val Success: ImageVector = Icons.Rounded.CheckCircle

  /** Check circle icon (alias for Success) */
  val CheckCircle: ImageVector = Icons.Rounded.CheckCircle

  /** Error status */
  val Error: ImageVector = Icons.Rounded.Error

  /** Warning status */
  val Warning: ImageVector = Icons.Rounded.Warning

  /** Info status */
  val Info: ImageVector = Icons.Rounded.Info

  // Settings
  // Icons for settings-related items

  /** Server/HTTP icon */
  val Server: ImageVector = Icons.Rounded.Dns

  /** API icon */
  val Api: ImageVector = Icons.Rounded.Api

  /** API key icon */
  val Key: ImageVector = Icons.Rounded.Key

  /** URL/link icon */
  val Link: ImageVector = Icons.Rounded.Link

  /** AI model icon */
  val Model: ImageVector = Icons.Rounded.SmartToy

  /** Steps/progress icon */
  val Steps: ImageVector = Icons.Rounded.LinearScale

  /** System prompt icon */
  val Prompt: ImageVector = Icons.Rounded.Description

  /** Theme mode icon */
  val Theme: ImageVector = Icons.Rounded.Style

  /** Light mode icon */
  val LightMode: ImageVector = Icons.Rounded.LightMode

  /** Dark mode icon */
  val DarkMode: ImageVector = Icons.Rounded.DarkMode

  /** Dynamic color/palette icon */
  val Palette: ImageVector = Icons.Rounded.Palette

  /** Language icon */
  val Language: ImageVector = Icons.Rounded.Language

  /** AI icon */
  val AI: ImageVector = Icons.Rounded.AutoAwesome

  /** Automation icon */
  val Automation: ImageVector = Icons.Rounded.SmartToy

  /** Screenshot icon */
  val Screenshot: ImageVector = Icons.Rounded.CameraAlt

  /** Timer icon */
  val Timer: ImageVector = Icons.Rounded.Timer

  /** Token icon */
  val Token: ImageVector = Icons.Rounded.Api

  /** Root/Admin icon */
  val Root: ImageVector = Icons.Rounded.AdminPanelSettings

  /** Memory/Cache icon */
  val Cache: ImageVector = Icons.Rounded.Memory

  /** Learning/AI icon */
  val Learning: ImageVector = Icons.Rounded.Psychology

  /** Clear/Sweep icon */
  val Clear: ImageVector = Icons.Rounded.DeleteSweep

  /** Speed/Performance icon */
  val Speed: ImageVector = Icons.Rounded.Speed

  /** Tree/Hierarchy icon */
  val Tree: ImageVector = Icons.Rounded.AccountTree

  /** Hybrid/Merge icon */
  val Hybrid: ImageVector = Icons.Rounded.Merge

  /** Merge icon */
  val Merge: ImageVector = Icons.Rounded.Merge

  /** Filter icon */
  val Filter: ImageVector = Icons.Rounded.FilterList

  /** Security icon */
  val Security: ImageVector = Icons.Rounded.Security

  /** Shield icon */
  val Shield: ImageVector = Icons.Rounded.Shield

  /** Touch/Input icon */
  val Touch: ImageVector = Icons.Rounded.TouchApp

  /** Location icon */
  val Location: ImageVector = Icons.Rounded.LocationOn

  /** WiFi icon */
  val Wifi: ImageVector = Icons.Rounded.Wifi

  /** Perception/Vision icon */
  val Perception: ImageVector = Icons.Rounded.Visibility

  /** License icon */
  val License: ImageVector = Icons.Rounded.WorkspacePremium

  // Visibility
  // Icons for show/hide toggles

  /** Show/visible icon */
  val Visibility: ImageVector = Icons.Rounded.Visibility

  /** Hide/invisible icon */
  val VisibilityOff: ImageVector = Icons.Rounded.VisibilityOff

  /** Apps/Applications icon */
  val Apps: ImageVector = Icons.Rounded.Apps

  // Empty States
  // Icons for empty content states

  /** Empty history state */
  val EmptyHistory: ImageVector = Icons.Rounded.HistoryToggleOff

  /** Empty task state */
  val EmptyTask: ImageVector = Icons.AutoMirrored.Rounded.Assignment

  // UI Controls
  // Icons for UI control elements

  /** More options menu */
  val MoreVert: ImageVector = Icons.Rounded.MoreVert

  /** Navigate forward/details */
  val ChevronRight: ImageVector = Icons.Rounded.ChevronRight

  /** Expand content */
  val ExpandMore: ImageVector = Icons.Rounded.ExpandMore

  /** Collapse content */
  val ExpandLess: ImageVector = Icons.Rounded.ExpandLess

  /** Drag handle for reorderable items */
  val DragHandle: ImageVector = Icons.Rounded.DragHandle

  // Slash Commands
  // Icons for slash command functionality

  /** Terminal/command icon */
  val Terminal: ImageVector = Icons.Rounded.Terminal

  /** Keyboard return/enter icon */
  val KeyboardReturn: ImageVector = Icons.Rounded.ChevronRight
}
