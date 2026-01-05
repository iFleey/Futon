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
package me.fleey.futon.data.perception.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manifest containing all model definitions for deployment.
 *
 * The manifest is loaded from app assets and defines which models
 * should be deployed to the daemon's model directory.
 */
@Serializable
data class ModelManifest(
  val version: Int,
  val models: List<ModelMetadata>,
) {
  companion object {
    const val ASSET_PATH = "models/manifest.json"

    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /**
     * Load the model manifest from app assets.
     *
     * @param context Android context for asset access
     * @return Result containing the parsed manifest or an error
     */
    fun loadFromAssets(context: Context): Result<ModelManifest> = runCatching {
      context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
        json.decodeFromString<ModelManifest>(reader.readText())
      }
    }
  }
}
