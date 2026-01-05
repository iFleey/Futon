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
package me.fleey.futon.data.localmodel.storage

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File

/**
 * Implementation of [StorageValidator] using Android's StatFs API.
 *
 * @param context Application context for accessing external files directory
 */
@Single(binds = [StorageValidator::class])
class StorageValidatorImpl(
  private val context: Context,
) : StorageValidator {

  companion object {
    private const val MODELS_DIRECTORY = "models"
  }

  /**
   * Checks if there is sufficient storage space for a download.
   *
   * @param requiredBytes The size of the download in bytes
   * @return [StorageCheckResult.Sufficient] if enough space, [StorageCheckResult.Insufficient] otherwise
   */
  override suspend fun checkStorageForDownload(requiredBytes: Long): StorageCheckResult {
    val availableBytes = getAvailableStorage()
    val totalRequired = requiredBytes + StorageCheckResult.BUFFER_SIZE_BYTES

    return if (availableBytes >= totalRequired) {
      StorageCheckResult.Sufficient
    } else {
      StorageCheckResult.Insufficient(
        requiredBytes = requiredBytes,
        availableBytes = availableBytes,
        bufferBytes = StorageCheckResult.BUFFER_SIZE_BYTES,
      )
    }
  }

  override suspend fun getAvailableStorage(): Long = withContext(Dispatchers.IO) {
    val storageDir = getModelStorageDirectory()

    // Ensure directory exists for accurate StatFs reading
    if (!storageDir.exists()) {
      storageDir.mkdirs()
    }

    val statFs = StatFs(storageDir.absolutePath)
    statFs.availableBytes
  }

  override fun getModelStorageDirectory(): File {
    val externalFilesDir = context.getExternalFilesDir(null)
      ?: context.filesDir

    val modelsDir = File(externalFilesDir, MODELS_DIRECTORY)

    if (!modelsDir.exists()) {
      modelsDir.mkdirs()
    }

    return modelsDir
  }
}
