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

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.futon.R
import me.fleey.futon.data.apps.models.AppSettings
import me.fleey.futon.data.apps.models.InstalledApp
import org.koin.core.annotation.Single
import java.util.Locale

@Single(binds = [AppDiscovery::class])
class AppDiscoveryImpl(
  private val context: Context,
  private val settingsRepository: AppSettingsRepository,
) : AppDiscovery {

  private val mutex = Mutex()
  private var cachedApps: List<InstalledApp>? = null
  private val _appsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

  override fun isPermissionGranted(): Boolean {
    return try {
      val pm = context.packageManager
      pm.getInstalledApplications(PackageManager.GET_META_DATA)
      true
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun getLaunchableApps(): List<InstalledApp> = mutex.withLock {
    cachedApps?.let { return it }

    val settings = settingsRepository.getSettings()
    if (!settings.enabled) return emptyList()

    val apps = withContext(Dispatchers.IO) {
      val pm = context.packageManager
      val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
      }

      try {
        pm.queryIntentActivities(mainIntent, 0)
          .mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val packageName = appInfo.packageName

            if (packageName == context.packageName) return@mapNotNull null
            if (packageName in settings.hiddenApps) return@mapNotNull null

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp && !settings.includeSystemApps) return@mapNotNull null

            val appName = pm.getApplicationLabel(appInfo).toString()
            val versionName = try {
              pm.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
              null
            }

            val categoryId = findCategoryForPackage(packageName, settings)
            val userAliases = settings.userAliases
              .find { it.packageName == packageName }
              ?.aliases ?: emptyList()

            InstalledApp(
              packageName = packageName,
              appName = appName,
              versionName = versionName,
              isSystemApp = isSystemApp,
              categoryId = categoryId,
              userAliases = userAliases,
            )
          }
          .sortedBy { it.appName.lowercase(Locale.getDefault()) }
      } catch (e: Exception) {
        emptyList()
      }
    }

    cachedApps = apps
    _appsFlow.value = apps
    apps
  }

  override suspend fun searchApps(query: String): List<InstalledApp> {
    val apps = getLaunchableApps()
    val lowerQuery = query.lowercase(Locale.getDefault())

    val results = apps.filter { app ->
      app.appName.lowercase(Locale.getDefault()).contains(lowerQuery) ||
        app.packageName.lowercase(Locale.getDefault()).contains(lowerQuery) ||
        app.userAliases.any { it.lowercase(Locale.getDefault()).contains(lowerQuery) }
    }

    // If no results found in cached apps, search ALL apps including system apps
    if (results.isEmpty()) {
      return withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
          addCategory(Intent.CATEGORY_LAUNCHER)
        }

        try {
          pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { resolveInfo ->
              val appInfo = resolveInfo.activityInfo.applicationInfo
              val packageName = appInfo.packageName
              if (packageName == context.packageName) return@mapNotNull null

              val appName = pm.getApplicationLabel(appInfo).toString()
              val appNameLower = appName.lowercase(Locale.getDefault())
              val packageNameLower = packageName.lowercase(Locale.getDefault())

              if (appNameLower.contains(lowerQuery) || packageNameLower.contains(lowerQuery)) {
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val versionName = try {
                  pm.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) {
                  null
                }

                InstalledApp(
                  packageName = packageName,
                  appName = appName,
                  versionName = versionName,
                  isSystemApp = isSystemApp,
                  categoryId = "other",
                  userAliases = emptyList(),
                )
              } else {
                null
              }
            }
        } catch (e: Exception) {
          emptyList()
        }
      }
    }

    return results
  }

  override suspend fun findAppByName(name: String): InstalledApp? {
    val apps = getLaunchableApps()
    val lowerName = name.lowercase(Locale.getDefault()).trim()

    // First try exact match in cached apps
    apps.find { app ->
      app.appName.lowercase(Locale.getDefault()) == lowerName ||
        app.userAliases.any { it.lowercase(Locale.getDefault()) == lowerName }
    }?.let { return it }

    // Try fuzzy match in cached apps
    val scored = apps.mapNotNull { app ->
      val score = calculateMatchScore(lowerName, app)
      if (score > 0.6f) app to score else null
    }.sortedByDescending { it.second }

    scored.firstOrNull()?.first?.let { return it }

    // If not found in cached apps, search ALL installed apps including system apps
    return withContext(Dispatchers.IO) {
      val pm = context.packageManager
      val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
      }

      try {
        pm.queryIntentActivities(mainIntent, 0)
          .mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val packageName = appInfo.packageName
            if (packageName == context.packageName) return@mapNotNull null

            val appName = pm.getApplicationLabel(appInfo).toString()
            val appNameLower = appName.lowercase(Locale.getDefault())

            val isMatch = appNameLower == lowerName ||
              appNameLower.contains(lowerName) ||
              lowerName.contains(appNameLower)

            if (isMatch) {
              val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
              val versionName = try {
                pm.getPackageInfo(packageName, 0).versionName
              } catch (e: Exception) {
                null
              }

              InstalledApp(
                packageName = packageName,
                appName = appName,
                versionName = versionName,
                isSystemApp = isSystemApp,
                categoryId = "other",
                userAliases = emptyList(),
              )
            } else {
              null
            }
          }
          .firstOrNull()
      } catch (e: Exception) {
        null
      }
    }
  }

  override suspend fun getAppByPackage(packageName: String): InstalledApp? {
    return getLaunchableApps().find { it.packageName == packageName }
  }

  override fun observeAppChanges(): Flow<List<InstalledApp>> = _appsFlow.asStateFlow()

  override suspend fun refresh() {
    mutex.withLock { cachedApps = null }
    getLaunchableApps()
  }

  override suspend fun getAIContext(): String? {
    val settings = settingsRepository.getSettings()
    if (!settings.enabled) return null

    val apps = getLaunchableApps()
    if (apps.isEmpty()) return null

    val limitedApps = apps.take(settings.maxAppsInContext)

    return buildString {
      appendLine(context.getString(R.string.ai_context_installed_apps, limitedApps.size))
      appendLine()

      val grouped = limitedApps.groupBy { it.categoryId ?: "other" }
      grouped.forEach { (categoryId, categoryApps) ->
        if (categoryApps.isNotEmpty()) {
          val category = settings.categories.find { it.id == categoryId }
          val categoryName = category?.let { cat ->
            if (cat.isUserDefined) {
              cat.customName ?: categoryId
            } else {
              DefaultAppCategory.fromId(cat.id)?.let { defaultCat ->
                context.getString(defaultCat.nameRes)
              } ?: categoryId
            }
          } ?: categoryId
          appendLine("### $categoryName")
          categoryApps.forEach { app ->
            appendLine(app.toAIFormat())
          }
          appendLine()
        }
      }
    }
  }

  private fun findCategoryForPackage(packageName: String, settings: AppSettings): String? {
    for (category in settings.categories) {
      if (category.packagePatterns.any { packageName.startsWith(it) || packageName == it }) {
        return category.id
      }
    }
    return "other"
  }

  private fun calculateMatchScore(query: String, app: InstalledApp): Float {
    val names = app.getSearchableNames().map { it.lowercase(Locale.getDefault()) }
    var maxScore = 0f

    for (name in names) {
      if (name.contains(query)) maxScore = maxOf(maxScore, 0.8f)
      if (query.contains(name)) maxScore = maxOf(maxScore, 0.7f)

      val distance = levenshteinDistance(query, name)
      val maxLen = maxOf(query.length, name.length)
      val similarity = 1f - (distance.toFloat() / maxLen)
      maxScore = maxOf(maxScore, similarity)
    }

    return maxScore
  }

  private fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j

    for (i in 1..s1.length) {
      for (j in 1..s2.length) {
        val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
        dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
      }
    }
    return dp[s1.length][s2.length]
  }

  override suspend fun launchApp(packageName: String): Boolean {
    return withContext(Dispatchers.Main) {
      try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
          launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(launchIntent)
          true
        } else {
          false
        }
      } catch (e: Exception) {
        false
      }
    }
  }

  override suspend fun detectAppLaunchIntent(taskDescription: String): InstalledApp? {
    val settings = settingsRepository.getSettings()
    if (!settings.enabled) return null

    val lowerTask = taskDescription.lowercase(Locale.getDefault())
    val apps = getLaunchableApps()

    val launchPatterns = listOf(
      "打开", "启动", "开启", "运行", "进入",
      "open", "launch", "start", "run", "go to",
    )
    val usePatterns = listOf("用", "use")

    val hasLaunchIntent = launchPatterns.any { lowerTask.contains(it) }
    val hasUseIntent = usePatterns.any { pattern ->
      val idx = lowerTask.indexOf(pattern)
      if (idx >= 0) {
        val afterPattern = lowerTask.substring(idx + pattern.length).trim()
        apps.any { app ->
          app.getSearchableNames().any { name ->
            afterPattern.startsWith(name.lowercase(Locale.getDefault()))
          }
        }
      } else false
    }

    if (!hasLaunchIntent && !hasUseIntent) return null

    for (app in apps) {
      val appNames = app.getSearchableNames().map { it.lowercase(Locale.getDefault()) }
      for (appName in appNames) {
        if (lowerTask.contains(appName)) {
          return app
        }
      }
    }

    for (pattern in launchPatterns + usePatterns) {
      val idx = lowerTask.indexOf(pattern)
      if (idx >= 0) {
        val afterPattern = lowerTask.substring(idx + pattern.length).trim()
        val potentialAppName = afterPattern.split(" ", "，", ",", "。", ".", "和").firstOrNull()?.trim()
        if (!potentialAppName.isNullOrBlank()) {
          findAppByName(potentialAppName)?.let { return it }
        }
      }
    }

    return null
  }
}
