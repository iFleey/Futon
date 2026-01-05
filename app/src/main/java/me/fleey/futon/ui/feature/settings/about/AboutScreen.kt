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
package me.fleey.futon.ui.feature.settings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.fleey.futon.BuildConfig
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.component.navigation.FutonTopBar
import me.fleey.futon.ui.designsystem.component.settings.SettingsGroup
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import me.fleey.futon.util.ChromeTabsHelper

@Composable
fun AboutScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val isDarkTheme = isSystemInDarkTheme()
  val toolbarColor = FutonTheme.colors.background

  fun openUrl(url: String) {
    ChromeTabsHelper.openUrl(context, url, toolbarColor, isDarkTheme)
  }

  Scaffold(
    topBar = {
      FutonTopBar(
        title = stringResource(R.string.about_title),
        onBackClick = onBack,
      )
    },
    containerColor = FutonTheme.colors.background,
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(modifier = Modifier.height(24.dp))

      Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.size(168.dp),
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = FutonTheme.colors.textNormal,
      )

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = MaterialTheme.typography.bodyMedium,
        color = FutonTheme.colors.textMuted,
      )

      Spacer(modifier = Modifier.height(24.dp))

      SettingsGroup(title = stringResource(R.string.about_project)) {
        item {
          AboutLinkItem(
            icon = FutonIcons.Link,
            title = stringResource(R.string.about_github),
            subtitle = "github.com/iFleey/Futon",
            onClick = { openUrl("https://github.com/iFleey/Futon") },
          )
        }
        item {
          AboutLinkItem(
            icon = FutonIcons.Info,
            title = stringResource(R.string.about_author),
            subtitle = "Fleey",
            onClick = { openUrl("https://fleey.me") },
          )
        }
        item {
          AboutItem(
            icon = FutonIcons.Shield,
            title = stringResource(R.string.about_license),
            subtitle = "GPLv3",
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.about_icon_design)) {
        item {
          AboutItem(
            icon = FutonIcons.Palette,
            title = stringResource(R.string.about_icon_designer),
            subtitle = "Fleey, Caniv",
          )
        }
        item {
          AboutItem(
            icon = FutonIcons.Edit,
            title = stringResource(R.string.about_icon_illustrator),
            subtitle = "Caniv",
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      SettingsGroup(title = stringResource(R.string.about_acknowledgements)) {
        OpenSourceLibraries.forEach { library ->
          item {
            AboutLinkItem(
              icon = FutonIcons.License,
              title = library.name,
              subtitle = library.license,
              onClick = { openUrl(library.url) },
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun AboutItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = FutonTheme.colors.textMuted,
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = FutonTheme.colors.textNormal,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = FutonTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun AboutLinkItem(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    color = Color.Transparent,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = FutonTheme.colors.textMuted,
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = FutonTheme.colors.textNormal,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textMuted,
        )
      }
      Icon(
        imageVector = FutonIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = FutonTheme.colors.textMuted,
      )
    }
  }
}

private data class OpenSourceLibrary(
  val name: String,
  val license: String,
  val url: String,
)

private val OpenSourceLibraries = listOf(
  OpenSourceLibrary(
    name = "Jetpack Compose",
    license = "Apache 2.0",
    url = "https://developer.android.com/jetpack/compose",
  ),
  OpenSourceLibrary(
    name = "Kotlin Coroutines",
    license = "Apache 2.0",
    url = "https://github.com/Kotlin/kotlinx.coroutines",
  ),
  OpenSourceLibrary(
    name = "Ktor",
    license = "Apache 2.0",
    url = "https://ktor.io",
  ),
  OpenSourceLibrary(
    name = "Koin",
    license = "Apache 2.0",
    url = "https://insert-koin.io",
  ),
  OpenSourceLibrary(
    name = "Coil",
    license = "Apache 2.0",
    url = "https://coil-kt.github.io/coil/",
  ),
  OpenSourceLibrary(
    name = "Room",
    license = "Apache 2.0",
    url = "https://developer.android.com/training/data-storage/room",
  ),
  OpenSourceLibrary(
    name = "DataStore",
    license = "Apache 2.0",
    url = "https://developer.android.com/topic/libraries/architecture/datastore",
  ),
  OpenSourceLibrary(
    name = "llama.cpp",
    license = "MIT",
    url = "https://github.com/ggml-org/llama.cpp",
  ),
  OpenSourceLibrary(
    name = "Circuit",
    license = "Apache 2.0",
    url = "https://slackhq.github.io/circuit/",
  ),
  OpenSourceLibrary(
    name = "libsu",
    license = "Apache 2.0",
    url = "https://github.com/topjohnwu/libsu",
  ),
  OpenSourceLibrary(
    name = "kotlinx.serialization",
    license = "Apache 2.0",
    url = "https://github.com/Kotlin/kotlinx.serialization",
  ),
  OpenSourceLibrary(
    name = "Squircle Shape",
    license = "MIT",
    url = "https://github.com/stoyan-vuchev/squircle-shape",
  ),
  OpenSourceLibrary(
    name = "PPOCRv5-Android",
    license = "Apache 2.0",
    url = "https://github.com/iFleey/PPOCRv5-Android/",
  ),
)
