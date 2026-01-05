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
package me.fleey.futon.data.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import me.fleey.futon.data.settings.models.AppLanguage

object LocaleManager {

  fun applyLanguage(language: AppLanguage) {
    val localeList = when (language) {
      AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
      else -> LocaleListCompat.forLanguageTags(language.code)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
  }

  fun applyLanguageForAndroid13Plus(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val localeManager = context.getSystemService(LocaleManager::class.java)
      val localeList = when (language) {
        AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
        else -> LocaleList.forLanguageTags(language.code)
      }
      localeManager.applicationLocales = localeList
    } else {
      applyLanguage(language)
    }
  }

  fun getCurrentLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return AppLanguage.SYSTEM

    val tag = locales.toLanguageTags()
    return AppLanguage.entries.find { it.code == tag } ?: AppLanguage.SYSTEM
  }
}
