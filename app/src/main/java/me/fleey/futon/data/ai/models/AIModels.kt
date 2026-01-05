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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class Message(
  val role: String,
  val content: List<ContentPart>,
)

/**
 * Content part for OpenAI-style messages.
 * Uses "type" as the discriminator field to match OpenAI API format.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ContentPart {
  @Serializable
  @SerialName("text")
  data class Text(
    val text: String,
  ) : ContentPart

  @Serializable
  @SerialName("image_url")
  data class ImageUrl(
    @SerialName("image_url")
    val imageUrl: ImageUrlData,
  ) : ContentPart
}

@Serializable
data class ImageUrlData(
  val url: String,
  val detail: String = "high",
)

@Serializable
data class AIResponse(
  val action: ActionType,
  val parameters: ActionParameters? = null,
  val reasoning: String? = null,
  @SerialName("taskComplete")
  val taskComplete: Boolean = false,
  /** SOM element ID - when present, coordinates should be resolved from SomAnnotation */
  @SerialName("element_id")
  val elementId: Int? = null,
)

@Serializable
enum class ActionType {
  @SerialName("tap")
  TAP,

  // Alias for tap - some AI models return this variant
  @SerialName("tap_coordinate")
  TAP_COORDINATE,

  @SerialName("long_press")
  LONG_PRESS,

  @SerialName("double_tap")
  DOUBLE_TAP,

  @SerialName("swipe")
  SWIPE,

  @SerialName("scroll")
  SCROLL,

  @SerialName("pinch")
  PINCH,

  @SerialName("input")
  INPUT,

  @SerialName("wait")
  WAIT,

  @SerialName("launch_app")
  LAUNCH_APP,

  @SerialName("launch_activity")
  LAUNCH_ACTIVITY,

  @SerialName("back")
  BACK,

  @SerialName("home")
  HOME,

  @SerialName("recents")
  RECENTS,

  @SerialName("notifications")
  NOTIFICATIONS,

  @SerialName("quick_settings")
  QUICK_SETTINGS,

  @SerialName("screenshot")
  SCREENSHOT,

  @SerialName("intervene")
  INTERVENE,

  @SerialName("call")
  CALL,

  @SerialName("complete")
  COMPLETE,

  @SerialName("error")
  ERROR
}

@Serializable
data class ActionParameters(
  // Coordinates
  val x: Int? = null,
  val y: Int? = null,
  val x1: Int? = null,
  val y1: Int? = null,
  val x2: Int? = null,
  val y2: Int? = null,

  // Text/Message
  val text: String? = null,
  val message: String? = null,

  // Duration/Timing
  val duration: Int? = null,

  // Scroll parameters
  val direction: String? = null,
  val distance: Int? = null,

  // Pinch parameters
  @SerialName("start_distance")
  val startDistance: Int? = null,
  @SerialName("end_distance")
  val endDistance: Int? = null,

  // Launch activity parameters
  @SerialName("package")
  val packageName: String? = null,
  val activity: String? = null,

  // Screenshot parameters
  val path: String? = null,

  // Intervention parameters
  val reason: String? = null,
  val hint: String? = null,

  // Call command parameters
  val command: String? = null,
  val args: Map<String, String>? = null,
)
