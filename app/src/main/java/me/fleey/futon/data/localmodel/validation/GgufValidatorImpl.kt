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
package me.fleey.futon.data.localmodel.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GgufValidator implementation.
 * Validates GGUF magic number, version, and extracts metadata.
 */
@Single(binds = [GgufValidator::class])
class GgufValidatorImpl : GgufValidator {

  override suspend fun validate(filePath: String): GgufValidationResult =
    withContext(Dispatchers.IO) {
      val file = File(filePath)

      if (!file.exists()) {
        return@withContext GgufValidationResult.Invalid(
          GgufValidationError.FileNotFound(filePath),
        )
      }

      if (!file.canRead()) {
        return@withContext GgufValidationResult.Invalid(
          GgufValidationError.FileNotReadable(filePath, "Permission denied"),
        )
      }

      // Check minimum file size (magic + version + tensor count + kv count = 24 bytes)
      val fileSize = file.length()
      if (fileSize < MINIMUM_HEADER_SIZE) {
        return@withContext GgufValidationResult.Invalid(
          GgufValidationError.FileTooSmall(fileSize, MINIMUM_HEADER_SIZE),
        )
      }

      try {
        RandomAccessFile(file, "r").use { raf ->
          val headerBuffer = ByteArray(MINIMUM_HEADER_SIZE.toInt())
          raf.readFully(headerBuffer)

          val buffer = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)

          // Validate magic number
          val magic = buffer.int
          if (magic != GgufValidator.GGUF_MAGIC_NUMBER) {
            return@withContext GgufValidationResult.Invalid(
              GgufValidationError.InvalidMagicNumber(magic, GgufValidator.GGUF_MAGIC_NUMBER),
            )
          }

          val version = buffer.int
          if (version < GgufValidator.MIN_GGUF_VERSION || version > GgufValidator.MAX_GGUF_VERSION) {
            return@withContext GgufValidationResult.Invalid(
              GgufValidationError.UnsupportedVersion(
                version,
                GgufValidator.MIN_GGUF_VERSION,
                GgufValidator.MAX_GGUF_VERSION,
              ),
            )
          }

          // Read tensor count and metadata KV count
          val tensorCount = buffer.long
          val metadataKvCount = buffer.long

          // Try to extract additional metadata
          val (architecture, modelName, isVision, isMmproj) = extractMetadata(raf, metadataKvCount)

          val metadata = GgufMetadata(
            version = version,
            tensorCount = tensorCount,
            metadataKvCount = metadataKvCount,
            architecture = architecture,
            modelName = modelName,
            isVisionModel = isVision,
            isMmproj = isMmproj,
            fileSize = fileSize,
          )

          GgufValidationResult.Valid(metadata)
        }
      } catch (e: Exception) {
        GgufValidationResult.Invalid(
          GgufValidationError.IoError(e.message ?: "Unknown error"),
        )
      }
    }

  override suspend fun hasValidMagicNumber(filePath: String): Boolean =
    withContext(Dispatchers.IO) {
      val file = File(filePath)
      if (!file.exists() || !file.canRead() || file.length() < 4) {
        return@withContext false
      }

      try {
        RandomAccessFile(file, "r").use { raf ->
          val magicBytes = ByteArray(4)
          raf.readFully(magicBytes)
          val magic = ByteBuffer.wrap(magicBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
          magic == GgufValidator.GGUF_MAGIC_NUMBER
        }
      } catch (e: Exception) {
        false
      }
    }

  override suspend fun isVisionLanguageModel(filePath: String): Boolean =
    withContext(Dispatchers.IO) {
      when (val result = validate(filePath)) {
        is GgufValidationResult.Valid -> result.metadata.isVisionModel
        is GgufValidationResult.Invalid -> false
      }
    }

  /**
   * Extracts metadata from GGUF key-value pairs.
   *
   * Returns a tuple of (architecture, modelName, isVisionModel, isMmproj)
   */
  private fun extractMetadata(
    raf: RandomAccessFile,
    kvCount: Long,
  ): MetadataExtraction {
    var architecture: String? = null
    var modelName: String? = null
    var isVision = false
    var isMmproj = false

    try {
      // Limit how many KV pairs we read to avoid excessive processing
      val maxKvToRead = minOf(kvCount, MAX_KV_PAIRS_TO_READ)

      for (i in 0 until maxKvToRead) {
        val key = readGgufString(raf) ?: break
        val valueType = readUInt32(raf)

        when (key) {
          "general.architecture" -> {
            if (valueType == GGUF_TYPE_STRING) {
              architecture = readGgufString(raf)
              architecture?.lowercase()?.let { arch ->
                if (GgufMetadata.VLM_ARCHITECTURES.any { arch.contains(it) }) {
                  isVision = true
                }
                if (GgufMetadata.MMPROJ_ARCHITECTURES.any { arch.contains(it) }) {
                  isMmproj = true
                }
              }
            } else {
              skipGgufValue(raf, valueType)
            }
          }

          "general.name" -> {
            if (valueType == GGUF_TYPE_STRING) {
              modelName = readGgufString(raf)
            } else {
              skipGgufValue(raf, valueType)
            }
          }

          else -> {
            // Check key patterns that indicate vision models
            if (key.contains("vision") || key.contains("clip") || key.contains("mmproj")) {
              isVision = true
            }
            skipGgufValue(raf, valueType)
          }
        }
      }
    } catch (e: Exception) {
      // Metadata extraction is best-effort, don't fail validation
    }

    return MetadataExtraction(architecture, modelName, isVision, isMmproj)
  }

  private fun readGgufString(raf: RandomAccessFile): String? {
    return try {
      val length = readUInt64(raf)
      if (length !in 0..MAX_STRING_LENGTH) {
        return null
      }
      val bytes = ByteArray(length.toInt())
      raf.readFully(bytes)
      String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Reads a uint32 in little-endian format.
   */
  private fun readUInt32(raf: RandomAccessFile): Int {
    val bytes = ByteArray(4)
    raf.readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
  }

  /**
   * Reads a uint64 in little-endian format.
   */
  private fun readUInt64(raf: RandomAccessFile): Long {
    val bytes = ByteArray(8)
    raf.readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
  }

  /**
   * Skips a GGUF value based on its type.
   */
  private fun skipGgufValue(raf: RandomAccessFile, valueType: Int) {
    when (valueType) {
      GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> raf.skipBytes(1)
      GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> raf.skipBytes(2)
      GGUF_TYPE_UINT32, GGUF_TYPE_INT32, GGUF_TYPE_FLOAT32 -> raf.skipBytes(4)
      GGUF_TYPE_UINT64, GGUF_TYPE_INT64, GGUF_TYPE_FLOAT64 -> raf.skipBytes(8)
      GGUF_TYPE_STRING -> {
        val length = readUInt64(raf)
        if (length in 1..MAX_STRING_LENGTH) {
          raf.skipBytes(length.toInt())
        }
      }

      GGUF_TYPE_ARRAY -> {
        val arrayType = readUInt32(raf)
        val arrayLength = readUInt64(raf)
        // Skip array elements (simplified - may not handle all cases)
        for (j in 0 until minOf(arrayLength, MAX_ARRAY_LENGTH)) {
          skipGgufValue(raf, arrayType)
        }
      }
    }
  }

  private data class MetadataExtraction(
    val architecture: String?,
    val modelName: String?,
    val isVision: Boolean,
    val isMmproj: Boolean,
  )

  companion object {
    /** Minimum header size: magic(4) + version(4) + tensor_count(8) + kv_count(8) */
    private const val MINIMUM_HEADER_SIZE = 24L

    /** Maximum KV pairs to read for metadata extraction */
    private const val MAX_KV_PAIRS_TO_READ = 100L

    /** Maximum string length to read */
    private const val MAX_STRING_LENGTH = 1024L

    /** Maximum array length to process */
    private const val MAX_ARRAY_LENGTH = 1000L

    private const val GGUF_TYPE_UINT8 = 0
    private const val GGUF_TYPE_INT8 = 1
    private const val GGUF_TYPE_UINT16 = 2
    private const val GGUF_TYPE_INT16 = 3
    private const val GGUF_TYPE_UINT32 = 4
    private const val GGUF_TYPE_INT32 = 5
    private const val GGUF_TYPE_FLOAT32 = 6
    private const val GGUF_TYPE_BOOL = 7
    private const val GGUF_TYPE_STRING = 8
    private const val GGUF_TYPE_ARRAY = 9
    private const val GGUF_TYPE_UINT64 = 10
    private const val GGUF_TYPE_INT64 = 11
    private const val GGUF_TYPE_FLOAT64 = 12
  }
}
