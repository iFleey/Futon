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
package me.fleey.futon.data.ai.models

import androidx.annotation.DrawableRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.R
import java.util.UUID

/**
 * A provider instance that users can create, edit, and delete.
 */
@Serializable
data class Provider(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val protocol: ApiProtocol,
  val baseUrl: String,
  val apiKey: String = "",
  val enabled: Boolean = false,
  val sortOrder: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  @SerialName("icon_key")
  val iconKey: String? = null,
  @SerialName("selected_model_id")
  val selectedModelId: String? = null,
) {
  fun isConfigured(): Boolean = apiKey.isNotBlank() || protocol == ApiProtocol.OLLAMA

  fun canBeEnabled(): Boolean = isConfigured()

  @DrawableRes
  fun getIconRes(): Int = ProviderIcons.getIconRes(iconKey)
}

/**
 * Maps icon keys to drawable resources.
 */
object ProviderIcons {
  private val iconMap = mapOf(
    "openai" to R.drawable.ic_provider_openai,
    "anthropic" to R.drawable.ic_provider_anthropic,
    "gemini" to R.drawable.ic_provider_gemini,
    "ollama" to R.drawable.ic_provider_ollama,
    "deepseek" to R.drawable.ic_provider_deepseek,
    "qwen" to R.drawable.ic_provider_qwen,
    "zhipu" to R.drawable.ic_provider_zhipu,
    "newapi" to R.drawable.ic_provider_newapi,
  )

  val availableIcons: List<String> = iconMap.keys.toList()

  @DrawableRes
  fun getIconRes(key: String?): Int = iconMap[key] ?: R.drawable.ic_provider_custom
}
