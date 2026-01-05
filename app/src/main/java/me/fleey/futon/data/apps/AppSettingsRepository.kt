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
package me.fleey.futon.data.apps

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.fleey.futon.R
import me.fleey.futon.data.apps.models.AppAlias
import me.fleey.futon.data.apps.models.AppCategory
import me.fleey.futon.data.apps.models.AppSettings
import org.koin.core.annotation.Single

// TODO: Get these from cloud
interface AppSettingsRepository {
  val settings: Flow<AppSettings>
  suspend fun getSettings(): AppSettings
  suspend fun updateSettings(settings: AppSettings)
  suspend fun addCategory(category: AppCategory)
  suspend fun removeCategory(categoryId: String)
  suspend fun setAppAliases(packageName: String, aliases: List<String>)
  suspend fun hideApp(packageName: String)
  suspend fun unhideApp(packageName: String)
  suspend fun resetToDefaults()
}

enum class DefaultAppCategory(
  val id: String,
  @param:StringRes val nameRes: Int,
  val packagePatterns: List<String>,
) {
  SOCIAL(
    id = "social",
    nameRes = R.string.app_category_social,
    packagePatterns = listOf(
      "com.tencent.mm", "com.tencent.mobileqq", "com.sina.weibo",
      "com.ss.android.ugc.aweme", "com.zhiliaoapp.musically",
      "com.instagram.android", "com.facebook.katana", "com.twitter.android",
      "org.telegram.messenger", "com.whatsapp", "com.snapchat.android",
    ),
  ),
  COMMUNICATION(
    id = "communication",
    nameRes = R.string.app_category_communication,
    packagePatterns = listOf(
      "com.android.dialer", "com.android.contacts", "com.android.mms",
      "com.google.android.apps.messaging",
    ),
  ),
  BROWSER(
    id = "browser",
    nameRes = R.string.app_category_browser,
    packagePatterns = listOf(
      "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
      "com.opera.browser", "com.UCMobile",
    ),
  ),
  MEDIA(
    id = "media",
    nameRes = R.string.app_category_media,
    packagePatterns = listOf(
      "com.google.android.youtube", "tv.danmaku.bili", "com.qiyi.video",
      "com.youku.phone", "com.tencent.qqlive", "com.netease.cloudmusic",
      "com.kugou.android", "com.tencent.qqmusic", "com.spotify.music",
    ),
  ),
  FINANCE(
    id = "finance",
    nameRes = R.string.app_category_finance,
    packagePatterns = listOf("com.eg.android.AlipayGphone", "com.tencent.mm"),
  ),
  SHOPPING(
    id = "shopping",
    nameRes = R.string.app_category_shopping,
    packagePatterns = listOf(
      "com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
      "com.amazon.mShop.android.shopping",
    ),
  ),
  TRAVEL(
    id = "travel",
    nameRes = R.string.app_category_travel,
    packagePatterns = listOf(
      "com.autonavi.minimap", "com.baidu.BaiduMap", "com.sdu.didi.psnger",
      "com.Qunar", "com.ctrip.ct",
    ),
  ),
  PRODUCTIVITY(
    id = "productivity",
    nameRes = R.string.app_category_productivity,
    packagePatterns = listOf(
      "com.microsoft.office", "com.google.android.apps.docs",
      "com.evernote", "notion.id",
    ),
  ),
  OTHER(
    id = "other",
    nameRes = R.string.app_category_other,
    packagePatterns = emptyList(),
  );

  fun toAppCategory(): AppCategory = AppCategory(
    id = id,
    nameRes = nameRes,
    packagePatterns = packagePatterns,
    isUserDefined = false,
  )

  companion object {
    fun fromId(id: String): DefaultAppCategory? = entries.find { it.id == id }
  }
}

@Single(binds = [AppSettingsRepository::class])
class AppSettingsRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
) : AppSettingsRepository {

  companion object {
    private val KEY_SETTINGS = stringPreferencesKey("app_settings")
  }

  override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
    prefs[KEY_SETTINGS]?.let {
      try {
        json.decodeFromString<AppSettings>(it)
      } catch (e: Exception) {
        null
      }
    } ?: createDefaultSettings()
  }

  override suspend fun getSettings(): AppSettings = settings.first()

  override suspend fun updateSettings(settings: AppSettings) {
    dataStore.edit { prefs ->
      prefs[KEY_SETTINGS] = json.encodeToString(settings)
    }
  }

  override suspend fun addCategory(category: AppCategory) {
    val current = getSettings()
    val updated = current.copy(
      categories = current.categories + category.copy(isUserDefined = true),
    )
    updateSettings(updated)
  }

  override suspend fun removeCategory(categoryId: String) {
    val current = getSettings()
    val updated = current.copy(
      categories = current.categories.filter { it.id != categoryId || !it.isUserDefined },
    )
    updateSettings(updated)
  }

  override suspend fun setAppAliases(packageName: String, aliases: List<String>) {
    val current = getSettings()
    val existingAliases = current.userAliases.filter { it.packageName != packageName }
    val updated = current.copy(
      userAliases = if (aliases.isNotEmpty()) {
        existingAliases + AppAlias(packageName, aliases)
      } else {
        existingAliases
      },
    )
    updateSettings(updated)
  }

  override suspend fun hideApp(packageName: String) {
    val current = getSettings()
    if (packageName !in current.hiddenApps) {
      updateSettings(current.copy(hiddenApps = current.hiddenApps + packageName))
    }
  }

  override suspend fun unhideApp(packageName: String) {
    val current = getSettings()
    updateSettings(current.copy(hiddenApps = current.hiddenApps - packageName))
  }

  override suspend fun resetToDefaults() {
    updateSettings(createDefaultSettings())
  }

  private fun createDefaultSettings(): AppSettings {
    return AppSettings(
      enabled = true,
      includeSystemApps = false,
      maxAppsInContext = 30,
      categories = DefaultAppCategory.entries.map { it.toAppCategory() },
      userAliases = emptyList(),
      hiddenApps = emptyList(),
    )
  }
}
