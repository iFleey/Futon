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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Request body for Gemini API generateContent endpoint.
 *
 * @property contents List of conversation contents
 * @property generationConfig Optional generation configuration
 * @property systemInstruction Optional system instruction (replaces system role messages)
 */
@Serializable
data class GeminiRequest(
  val contents: List<GeminiContent>,
  val generationConfig: GeminiGenerationConfig? = null,
  val systemInstruction: GeminiContent? = null,
)

/**
 * A single content item in a Gemini conversation.
 *
 * @property role The role of the content author ("user" or "model")
 * @property parts List of content parts (text, images, etc.)
 */
@Serializable
data class GeminiContent(
  val role: String,
  val parts: List<GeminiPart>,
)

/**
 * A part of content in a Gemini message.
 * Can be text or inline data (images).
 *
 * Uses custom serializer to match Gemini API format:
 * - Text: {"text": "..."}
 * - InlineData: {"inlineData": {"mimeType": "...", "data": "..."}}
 */
@Serializable(with = GeminiPartSerializer::class)
sealed interface GeminiPart {
  /**
   * Text content part.
   */
  data class Text(
    val text: String,
  ) : GeminiPart

  /**
   * Inline data content part (for images).
   *
   * @property mimeType MIME type of the data (e.g., "image/jpeg", "image/png")
   * @property data Base64-encoded data
   */
  data class InlineData(
    val mimeType: String,
    val data: String,
  ) : GeminiPart
}

/**
 * Custom serializer for GeminiPart to match Gemini API format.
 */
object GeminiPartSerializer : KSerializer<GeminiPart> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GeminiPart")

  override fun serialize(encoder: Encoder, value: GeminiPart) {
    val jsonEncoder = encoder as JsonEncoder
    val jsonObject = when (value) {
      is GeminiPart.Text -> buildJsonObject {
        put("text", value.text)
      }

      is GeminiPart.InlineData -> buildJsonObject {
        putJsonObject("inlineData") {
          put("mimeType", value.mimeType)
          put("data", value.data)
        }
      }
    }
    jsonEncoder.encodeJsonElement(jsonObject)
  }

  override fun deserialize(decoder: Decoder): GeminiPart {
    val jsonDecoder = decoder as JsonDecoder
    val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

    return when {
      "text" in jsonObject -> {
        GeminiPart.Text(jsonObject["text"]!!.jsonPrimitive.content)
      }

      "inlineData" in jsonObject -> {
        val inlineData = jsonObject["inlineData"]!!.jsonObject
        GeminiPart.InlineData(
          mimeType = inlineData["mimeType"]!!.jsonPrimitive.content,
          data = inlineData["data"]!!.jsonPrimitive.content,
        )
      }

      else -> throw IllegalArgumentException("Unknown GeminiPart type: $jsonObject")
    }
  }
}

/**
 * Generation configuration for Gemini API.
 *
 * @property maxOutputTokens Maximum number of tokens to generate
 * @property temperature Sampling temperature (0.0 to 1.0)
 * @property topP Top-p sampling parameter
 * @property topK Top-k sampling parameter
 */
@Serializable
data class GeminiGenerationConfig(
  val maxOutputTokens: Int? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
)

/**
 * Response from Gemini API generateContent endpoint.
 *
 * @property candidates List of generated candidates
 * @property promptFeedback Optional feedback about the prompt
 */
@Serializable
data class GeminiResponse(
  val candidates: List<GeminiCandidate>? = null,
  val promptFeedback: GeminiPromptFeedback? = null,
  val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
data class GeminiUsageMetadata(
  val promptTokenCount: Int = 0,
  val candidatesTokenCount: Int = 0,
  val totalTokenCount: Int = 0,
)

/**
 * A single candidate response from Gemini.
 *
 * @property content The generated content
 * @property finishReason Reason for finishing generation
 * @property safetyRatings Safety ratings for the response
 */
@Serializable
data class GeminiCandidate(
  val content: GeminiContent,
  val finishReason: String? = null,
  val safetyRatings: List<GeminiSafetyRating>? = null,
)

/**
 * Safety rating for Gemini content.
 *
 * @property category Safety category
 * @property probability Probability level
 */
@Serializable
data class GeminiSafetyRating(
  val category: String,
  val probability: String,
)

/**
 * Feedback about the prompt from Gemini.
 *
 * @property blockReason Reason if the prompt was blocked
 * @property safetyRatings Safety ratings for the prompt
 */
@Serializable
data class GeminiPromptFeedback(
  val blockReason: String? = null,
  val safetyRatings: List<GeminiSafetyRating>? = null,
)

/**
 * Error response from Gemini API.
 *
 * @property error Error details
 */
@Serializable
data class GeminiErrorResponse(
  val error: GeminiError,
)

/**
 * Error details from Gemini API.
 *
 * @property code Error code
 * @property message Error message
 * @property status Error status
 */
@Serializable
data class GeminiError(
  val code: Int,
  val message: String,
  val status: String,
)
