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
package me.fleey.futon.data.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.fleey.futon.data.capture.models.CaptureErrorCode
import me.fleey.futon.data.capture.models.CaptureMethod
import me.fleey.futon.data.capture.models.CaptureResult
import me.fleey.futon.data.privacy.models.CaptureDecision
import me.fleey.futon.platform.root.RootShell
import me.fleey.futon.platform.root.ShellResult
import me.fleey.futon.platform.root.getErrorMessage
import me.fleey.futon.platform.root.isSuccess
import java.io.ByteArrayOutputStream

/**
 * Screenshot capture implementation using root shell screencap command.
 *
 * This implementation executes the Android screencap command via root shell
 * and encodes the result as Base64 JPEG.
 */
class RootScreenCapture(
  private val rootShell: RootShell,
) : ScreenCapture {

  override val method: CaptureMethod = CaptureMethod.ROOT_SCREENCAP

  companion object {
    private const val TAG = "RootScreenCapture"
    private const val TEMP_FILE_PATH = "/data/local/tmp/futon_screenshot.png"
    private const val CAPTURE_TIMEOUT_MS = 8000L
    private const val SHELL_COMMAND_TIMEOUT_MS = 3000L
  }

  override suspend fun isAvailable(): Boolean {
    return rootShell.isRootAvailable()
  }

  override suspend fun capture(quality: Int): CaptureResult {
    val clampedQuality = quality.coerceIn(0, 100)

    if (!rootShell.isRootAvailable()) {
      Log.w(TAG, "Root access not available")
      return CaptureResult.Failure(
        code = CaptureErrorCode.ROOT_NOT_AVAILABLE,
        message = "Root access is required for screencap",
      )
    }

    return try {
      val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
        captureScreenshot(clampedQuality)
      }

      result ?: CaptureResult.Failure(
        code = CaptureErrorCode.TIMEOUT,
        message = "Screenshot capture timed out after ${CAPTURE_TIMEOUT_MS}ms",
      )
    } catch (e: Exception) {
      Log.e(TAG, "Screenshot capture failed", e)
      CaptureResult.Failure(
        code = CaptureErrorCode.UNKNOWN_ERROR,
        message = e.message ?: "Unknown error during screenshot capture",
      )
    }
  }

  override suspend fun captureWithPrivacyCheck(
    privacyManager: PrivacyManager,
    quality: Int,
  ): CaptureResult {
    // Check privacy settings first
    val decision = privacyManager.shouldAllowCapture()
    val windowStatus = privacyManager.checkSecureWindow()

    return when (decision) {
      is CaptureDecision.Blocked -> {
        privacyManager.logCaptureAttempt(
          packageName = windowStatus.packageName ?: "unknown",
          wasSecure = windowStatus.isSecure,
          wasAllowed = false,
          reason = "Blocked by STRICT privacy mode",
        )
        CaptureResult.PrivacyBlocked(
          packageName = windowStatus.packageName ?: "unknown",
          reason = "Screenshot blocked: secure window detected and privacy mode is STRICT",
        )
      }

      is CaptureDecision.NeedsConsent -> {
        privacyManager.logCaptureAttempt(
          packageName = decision.packageName,
          wasSecure = true,
          wasAllowed = false,
          reason = "Requires user consent",
        )
        CaptureResult.PrivacyBlocked(
          packageName = decision.packageName,
          reason = "Screenshot requires user consent for secure window",
        )
      }

      is CaptureDecision.Allowed -> {
        val result = capture(quality)

        // Log the capture attempt
        val wasAllowed = result is CaptureResult.Success
        privacyManager.logCaptureAttempt(
          packageName = windowStatus.packageName ?: "unknown",
          wasSecure = windowStatus.isSecure,
          wasAllowed = wasAllowed,
          reason = if (wasAllowed) "Capture allowed" else "Capture failed",
        )

        if (result is CaptureResult.Success && windowStatus.isSecure) {
          result.copy(wasSecureWindow = true)
        } else {
          result
        }
      }
    }
  }

  private suspend fun captureScreenshot(quality: Int): CaptureResult =
    withContext(Dispatchers.IO) {
      val captureResult = rootShell.execute(
        "screencap -p $TEMP_FILE_PATH",
        timeoutMs = SHELL_COMMAND_TIMEOUT_MS,
      )

      if (!captureResult.isSuccess()) {
        Log.e(TAG, "screencap command failed: ${captureResult.getErrorMessage()}")
        return@withContext handleShellError(captureResult)
      }

      val readResult = rootShell.execute(
        "cat $TEMP_FILE_PATH | base64 -w 0",
        timeoutMs = SHELL_COMMAND_TIMEOUT_MS,
      )

      rootShell.execute("rm -f $TEMP_FILE_PATH", timeoutMs = 1000L)

      if (!readResult.isSuccess()) {
        Log.e(TAG, "Failed to read screenshot: ${readResult.getErrorMessage()}")
        return@withContext handleShellError(readResult)
      }

      val base64Png = (readResult as ShellResult.Success).output.trim()

      if (base64Png.isEmpty()) {
        return@withContext CaptureResult.Failure(
          code = CaptureErrorCode.COMMAND_FAILED,
          message = "Screenshot capture returned empty data",
        )
      }

      try {
        val pngBytes = Base64.decode(base64Png, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
          ?: return@withContext CaptureResult.Failure(
            code = CaptureErrorCode.COMMAND_FAILED,
            message = "Failed to decode screenshot image",
          )

        val width = bitmap.width
        val height = bitmap.height

        // Convert to JPEG with specified quality
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        bitmap.recycle()

        val jpegBytes = outputStream.toByteArray()
        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        Log.d(TAG, "Screenshot captured: ${width}x${height}, quality=$quality")

        CaptureResult.Success(
          base64Image = base64Jpeg,
          format = "jpeg",
          width = width,
          height = height,
          wasSecureWindow = false,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to process screenshot", e)
        CaptureResult.Failure(
          code = CaptureErrorCode.COMMAND_FAILED,
          message = "Failed to process screenshot: ${e.message}",
        )
      }
    }

  private fun handleShellError(result: ShellResult): CaptureResult.Failure {
    return when (result) {
      is ShellResult.RootDenied -> CaptureResult.Failure(
        code = CaptureErrorCode.ROOT_NOT_AVAILABLE,
        message = result.reason,
      )

      is ShellResult.Timeout -> CaptureResult.Failure(
        code = CaptureErrorCode.TIMEOUT,
        message = "Command timed out after ${result.timeoutMs}ms",
      )

      is ShellResult.Error -> CaptureResult.Failure(
        code = CaptureErrorCode.COMMAND_FAILED,
        message = result.message,
      )

      is ShellResult.Success -> CaptureResult.Failure(
        code = CaptureErrorCode.COMMAND_FAILED,
        message = "Command failed with exit code ${result.exitCode}",
      )
    }
  }
}
