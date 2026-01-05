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

import androidx.annotation.StringRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.fleey.futon.R

/**
 * API protocol types representing different API formats.
 * This is a fixed enum - providers use one of these protocols.
 */
@Serializable
enum class ApiProtocol(
  @param:StringRes val displayNameRes: Int,
  val defaultEndpointSuffix: String,
) {
  @SerialName("openai_compatible")
  OPENAI_COMPATIBLE(
    displayNameRes = R.string.protocol_openai_compatible,
    defaultEndpointSuffix = "/chat/completions",
  ),

  @SerialName("gemini")
  GEMINI(
    displayNameRes = R.string.protocol_gemini,
    defaultEndpointSuffix = "/models/{model}:generateContent",
  ),

  @SerialName("anthropic")
  ANTHROPIC(
    displayNameRes = R.string.protocol_anthropic,
    defaultEndpointSuffix = "/messages",
  ),

  @SerialName("ollama")
  OLLAMA(
    displayNameRes = R.string.protocol_ollama,
    defaultEndpointSuffix = "/api/chat",
  ),
}
