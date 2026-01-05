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
package me.fleey.futon.domain.som

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import me.fleey.futon.domain.som.models.SomAnnotation
import me.fleey.futon.domain.som.models.SomElement
import me.fleey.futon.domain.som.models.SomElementType
import org.koin.core.annotation.Single
import java.io.ByteArrayOutputStream

data class SomRenderConfig(
  val markerSize: Int = 24,
  val markerPadding: Int = 4,
  val strokeWidth: Float = 2f,
  val fontSize: Float = 14f,
  val showBoundingBox: Boolean = true,
  val showMarkerBackground: Boolean = true,
  val compressionQuality: Int = 85,
)

/**
 * Renders SpM annotations onto screenshots.
 */
interface SomImageRenderer {
  /**
   * Render SoM markers onto a bitmap.
   * @param bitmap Original screenshot bitmap
   * @param annotation SoM annotation with elements
   * @param config Rendering configuration
   * @return New bitmap with markers drawn
   */
  fun render(
    bitmap: Bitmap,
    annotation: SomAnnotation,
    config: SomRenderConfig = SomRenderConfig(),
  ): Bitmap

  /**
   * Render and encode to base64 JPEG.
   */
  fun renderToBase64(
    bitmap: Bitmap,
    annotation: SomAnnotation,
    config: SomRenderConfig = SomRenderConfig(),
  ): String
}

@Single(binds = [SomImageRenderer::class])
class SomImageRendererImpl : SomImageRenderer {

  private val colorPalette = listOf(
    Color.parseColor("#FF5722"),
    Color.parseColor("#2196F3"),
    Color.parseColor("#4CAF50"),
    Color.parseColor("#9C27B0"),
    Color.parseColor("#FF9800"),
    Color.parseColor("#00BCD4"),
    Color.parseColor("#E91E63"),
    Color.parseColor("#3F51B5"),
    Color.parseColor("#8BC34A"),
    Color.parseColor("#FFC107"),
  )

  override fun render(
    bitmap: Bitmap,
    annotation: SomAnnotation,
    config: SomRenderConfig,
  ): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)

    val boxPaint = Paint().apply {
      style = Paint.Style.STROKE
      strokeWidth = config.strokeWidth
      isAntiAlias = true
    }

    val markerBgPaint = Paint().apply {
      style = Paint.Style.FILL
      isAntiAlias = true
    }

    val textPaint = Paint().apply {
      color = Color.WHITE
      textSize = config.fontSize * bitmap.density / 160f
      typeface = Typeface.DEFAULT_BOLD
      isAntiAlias = true
      textAlign = Paint.Align.CENTER
    }

    annotation.elements.forEach { element ->
      val color = getColorForElement(element)
      boxPaint.color = color
      markerBgPaint.color = color

      // Draw bounding box
      if (config.showBoundingBox) {
        val rect = RectF(
          element.bounds.left.toFloat(),
          element.bounds.top.toFloat(),
          element.bounds.right.toFloat(),
          element.bounds.bottom.toFloat(),
        )
        canvas.drawRect(rect, boxPaint)
      }

      // Draw marker with ID
      drawMarker(
        canvas = canvas,
        element = element,
        bgPaint = markerBgPaint,
        textPaint = textPaint,
        config = config,
      )
    }

    return result
  }

  override fun renderToBase64(
    bitmap: Bitmap,
    annotation: SomAnnotation,
    config: SomRenderConfig,
  ): String {
    val rendered = render(bitmap, annotation, config)
    val outputStream = ByteArrayOutputStream()
    rendered.compress(Bitmap.CompressFormat.JPEG, config.compressionQuality, outputStream)
    val bytes = outputStream.toByteArray()

    if (rendered != bitmap) {
      rendered.recycle()
    }

    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  private fun drawMarker(
    canvas: Canvas,
    element: SomElement,
    bgPaint: Paint,
    textPaint: Paint,
    config: SomRenderConfig,
  ) {
    val markerX = element.bounds.left.toFloat()
    val markerY = element.bounds.top.toFloat()

    val idText = element.id.toString()
    val textWidth = textPaint.measureText(idText)
    val textHeight = textPaint.textSize

    val bgWidth = maxOf(textWidth + config.markerPadding * 2, config.markerSize.toFloat())
    val bgHeight = textHeight + config.markerPadding * 2

    // Position marker at top-left of element, but ensure it's visible
    val adjustedX = markerX.coerceIn(0f, canvas.width - bgWidth)
    val adjustedY = markerY.coerceIn(0f, canvas.height - bgHeight)

    if (config.showMarkerBackground) {
      val bgRect = RectF(
        adjustedX,
        adjustedY,
        adjustedX + bgWidth,
        adjustedY + bgHeight,
      )
      canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)
    }

    // Draw ID text
    val textX = adjustedX + bgWidth / 2
    val textY = adjustedY + bgHeight / 2 + textHeight / 3
    canvas.drawText(idText, textX, textY, textPaint)
  }

  private fun getColorForElement(element: SomElement): Int {
    // Use element type to determine color for consistency
    val typeIndex = when (element.type) {
      SomElementType.BUTTON -> 0
      SomElementType.TEXT -> 1
      SomElementType.ICON -> 2
      SomElementType.INPUT_FIELD -> 3
      SomElementType.CHECKBOX -> 4
      SomElementType.SWITCH -> 5
      SomElementType.IMAGE -> 6
      SomElementType.LIST_ITEM -> 7
      SomElementType.UNKNOWN -> 8
    }
    return colorPalette[typeIndex % colorPalette.size]
  }
}
