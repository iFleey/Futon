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
package me.fleey.futon.data.perception

import android.graphics.Bitmap
import android.util.Log
import me.fleey.futon.domain.perception.models.UIBounds

/**
 * Utility for cropping bitmap regions for targeted OCR.
 *
 * Handles edge cases like out-of-bounds regions and invalid dimensions.
 */
object BitmapCropper {

  private const val TAG = "BitmapCropper"

  /**
   * Crop a bitmap to the specified region.
   *
   * @param bitmap Source bitmap to crop
   * @param region Region to extract (in pixel coordinates)
   * @return Cropped bitmap or null if cropping fails
   */
  fun crop(bitmap: Bitmap, region: UIBounds): Bitmap? {
    return try {
      val validatedRegion = validateAndClampRegion(bitmap, region)
      if (validatedRegion == null) {
        Log.w(TAG, "Invalid region after validation: $region")
        return null
      }

      Bitmap.createBitmap(
        bitmap,
        validatedRegion.left,
        validatedRegion.top,
        validatedRegion.width,
        validatedRegion.height,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to crop bitmap: ${e.message}", e)
      null
    }
  }

  /**
   * Crop multiple regions from a bitmap.
   *
   * @param bitmap Source bitmap
   * @param regions List of regions to extract
   * @return Map of region to cropped bitmap (only successful crops included)
   */
  fun cropMultiple(bitmap: Bitmap, regions: List<UIBounds>): Map<UIBounds, Bitmap> {
    return regions.mapNotNull { region ->
      crop(bitmap, region)?.let { cropped -> region to cropped }
    }.toMap()
  }

  /**
   * Crop a bitmap with padding around the region.
   *
   * @param bitmap Source bitmap
   * @param region Center region
   * @param padding Padding to add on all sides (in pixels)
   * @return Cropped bitmap with padding or null if cropping fails
   */
  fun cropWithPadding(bitmap: Bitmap, region: UIBounds, padding: Int): Bitmap? {
    val paddedRegion = UIBounds(
      left = (region.left - padding).coerceAtLeast(0),
      top = (region.top - padding).coerceAtLeast(0),
      right = (region.right + padding).coerceAtMost(bitmap.width),
      bottom = (region.bottom + padding).coerceAtMost(bitmap.height),
    )
    return crop(bitmap, paddedRegion)
  }

  /**
   * Validate and clamp a region to fit within bitmap bounds.
   *
   * @param bitmap Source bitmap
   * @param region Region to validate
   * @return Validated region or null if region is completely invalid
   */
  private fun validateAndClampRegion(bitmap: Bitmap, region: UIBounds): UIBounds? {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
      Log.w(TAG, "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
      return null
    }

    val left = region.left.coerceIn(0, bitmap.width - 1)
    val top = region.top.coerceIn(0, bitmap.height - 1)
    val right = region.right.coerceIn(left + 1, bitmap.width)
    val bottom = region.bottom.coerceIn(top + 1, bitmap.height)

    val width = right - left
    val height = bottom - top

    if (width <= 0 || height <= 0) {
      Log.w(TAG, "Region has zero or negative dimensions after clamping")
      return null
    }

    return UIBounds(left, top, right, bottom)
  }

  /**
   * Check if a region is valid for the given bitmap.
   *
   * @param bitmap Source bitmap
   * @param region Region to check
   * @return true if region can be cropped from bitmap
   */
  fun isValidRegion(bitmap: Bitmap, region: UIBounds): Boolean {
    return region.left >= 0 &&
      region.top >= 0 &&
      region.right <= bitmap.width &&
      region.bottom <= bitmap.height &&
      region.width > 0 &&
      region.height > 0
  }

  /**
   * Calculate the intersection of a region with bitmap bounds.
   *
   * @param bitmap Source bitmap
   * @param region Region to intersect
   * @return Intersection region or null if no intersection
   */
  fun intersectWithBitmap(bitmap: Bitmap, region: UIBounds): UIBounds? {
    val bitmapBounds = UIBounds(0, 0, bitmap.width, bitmap.height)

    if (!region.intersects(bitmapBounds)) {
      return null
    }

    return UIBounds(
      left = maxOf(region.left, 0),
      top = maxOf(region.top, 0),
      right = minOf(region.right, bitmap.width),
      bottom = minOf(region.bottom, bitmap.height),
    )
  }
}
