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
package me.fleey.futon

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.fleey.futon.data.daemon.ConfigurationSynchronizer
import me.fleey.futon.data.daemon.ProcessFreezerDisabler
import me.fleey.futon.data.localmodel.download.DownloadNotificationManager
import me.fleey.futon.data.settings.LocaleManager
import me.fleey.futon.data.settings.SettingsRepository
import me.fleey.futon.di.CircuitModule
import me.fleey.futon.di.DatabaseModule
import me.fleey.futon.di.FutonAppModule
import me.fleey.futon.di.ServiceModule
import me.fleey.futon.di.debugModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.ksp.generated.module

class FutonApplication : Application() {

  private val downloadNotificationManager: DownloadNotificationManager by inject()
  private val settingsRepository: SettingsRepository by inject()
  private val configurationSynchronizer: ConfigurationSynchronizer by inject()
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreate() {
    super.onCreate()

    ProcessFreezerDisabler.disable()

    startKoin {
      androidLogger(Level.ERROR)
      androidContext(this@FutonApplication)
      modules(
        debugModule,
        FutonAppModule().module,
        CircuitModule().module,
        DatabaseModule().module,
        ServiceModule().module,
      )
    }

    downloadNotificationManager.createNotificationChannel()
    applySavedLanguage()

    configurationSynchronizer.start()
  }

  private fun applySavedLanguage() {
    applicationScope.launch {
      val prefs = settingsRepository.getThemePreferences()
      LocaleManager.applyLanguage(prefs.appLanguage)
    }
  }
}
