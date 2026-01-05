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
package me.fleey.futon.data.localmodel.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.fleey.futon.data.localmodel.models.QuantizationType
import org.koin.core.annotation.Single
import java.io.BufferedReader
import java.io.FileReader

/** DeviceCapabilityDetector implementation using Android system APIs. */
@Single(binds = [DeviceCapabilityDetector::class])
class DeviceCapabilityDetectorImpl(
  private val context: Context,
) : DeviceCapabilityDetector {

  private val activityManager: ActivityManager by lazy {
    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  }

  override suspend fun detectCapabilities(): DeviceCapabilities = withContext(Dispatchers.IO) {
    val totalRamMb = getTotalRamMb()
    val availableRamMb = getAvailableRamMb()
    val processorName = getProcessorName()
    val isSnapdragon = isSnapdragonProcessor()
    val snapdragonModel = getSnapdragonModel()
    val supportsNnapi = supportsNnapi()
    val recommendedQuantization = getRecommendedQuantization(totalRamMb)
    val warnings = buildWarnings(totalRamMb)

    DeviceCapabilities(
      totalRamMb = totalRamMb,
      availableRamMb = availableRamMb,
      processorName = processorName,
      isSnapdragon = isSnapdragon,
      snapdragonModel = snapdragonModel,
      supportsNnapi = supportsNnapi,
      recommendedQuantization = recommendedQuantization,
      warnings = warnings,
    )
  }

  override fun getTotalRamMb(): Int {
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    return (memInfo.totalMem / (1024 * 1024)).toInt()
  }

  override fun getAvailableRamMb(): Int {
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    return (memInfo.availMem / (1024 * 1024)).toInt()
  }

  override fun getProcessorName(): String {
    // Try multiple sources for processor information
    return getProcessorFromCpuInfo()
      ?: getProcessorFromBuildInfo()
      ?: UNKNOWN_PROCESSOR
  }

  override fun isSnapdragonProcessor(): Boolean {
    val processorName = getProcessorName().lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val board = Build.BOARD.lowercase()

    return SNAPDRAGON_IDENTIFIERS.any { identifier ->
      processorName.contains(identifier) ||
        hardware.contains(identifier) ||
        board.contains(identifier)
    }
  }

  override fun getSnapdragonModel(): Int? {
    if (!isSnapdragonProcessor()) return null

    val processorName = getProcessorName()
    val hardware = Build.HARDWARE
    val board = Build.BOARD

    // Try to extract model number from various sources
    return extractSnapdragonModel(processorName)
      ?: extractSnapdragonModel(hardware)
      ?: extractSnapdragonModel(board)
      ?: extractSnapdragonModelFromSoc()
  }

  override fun supportsNnapi(): Boolean {
    // NNAPI was introduced in Android 8.1 (API 27)
    // More robust support from Android 10 (API 29)
    return true
  }

  /** INT4 for RAM < 6GB, INT8 for RAM >= 6GB */
  private fun getRecommendedQuantization(totalRamMb: Int): QuantizationType {
    return if (totalRamMb >= DeviceCapabilities.INT8_THRESHOLD_MB) {
      QuantizationType.INT8
    } else {
      QuantizationType.INT4
    }
  }


  private fun buildWarnings(totalRamMb: Int): List<String> {
    val warnings = mutableListOf<String>()

    if (totalRamMb < DeviceCapabilities.MIN_RAM_MB) {
      warnings.add(WARNING_LOW_RAM)
    }

    if (!supportsNnapi()) {
      warnings.add(WARNING_NO_NNAPI)
    }

    return warnings
  }

  /**
   * Reads processor information from /proc/cpuinfo.
   */
  private fun getProcessorFromCpuInfo(): String? {
    return try {
      BufferedReader(FileReader(CPUINFO_PATH)).use { reader ->
        reader.lineSequence()
          .filter { line ->
            line.startsWith(CPUINFO_HARDWARE_KEY) ||
              line.startsWith(CPUINFO_MODEL_KEY) ||
              line.startsWith(CPUINFO_PROCESSOR_KEY)
          }
          .map { line -> line.substringAfter(":").trim() }
          .firstOrNull { it.isNotBlank() }
      }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Gets processor information from Build properties.
   */
  private fun getProcessorFromBuildInfo(): String? {
    val hardware = Build.HARDWARE
    val board = Build.BOARD
    val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Build.SOC_MANUFACTURER
    } else null
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Build.SOC_MODEL
    } else null

    // Prefer SOC info if available (Android 12+)
    if (!socManufacturer.isNullOrBlank() && !socModel.isNullOrBlank()) {
      return "$socManufacturer $socModel"
    }

    // Fall back to hardware/board info
    return when {
      hardware.isNotBlank() && hardware != UNKNOWN_PROCESSOR -> hardware
      board.isNotBlank() && board != UNKNOWN_PROCESSOR -> board
      else -> null
    }
  }

  /**
   * Extracts Snapdragon model number from a string.
   * Looks for patterns like "Snapdragon 835", "SDM845", "SM8150", etc.
   */
  private fun extractSnapdragonModel(text: String): Int? {
    // "Snapdragon XXX" or "SD XXX"
    SNAPDRAGON_NAME_PATTERN.find(text)?.let { match ->
      return match.groupValues[1].toIntOrNull()
    }

    //  "SDMXXX" (e.g., SDM845)
    SDM_PATTERN.find(text)?.let { match ->
      return match.groupValues[1].toIntOrNull()
    }

    // "SMXXXX" (e.g., SM8150 -> 855, SM8250 -> 865)
    SM_PATTERN.find(text)?.let { match ->
      val smModel = match.groupValues[1].toIntOrNull()
      return smModel?.let { mapSmModelToSnapdragon(it) }
    }

    return null
  }

  /**
   * Tries to extract Snapdragon model from SOC info (Android 12+).
   */
  private fun extractSnapdragonModelFromSoc(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

    val socModel = Build.SOC_MODEL
    return extractSnapdragonModel(socModel)
  }

  /**
   * Maps SM model numbers to Snapdragon marketing names.
   * SM8xxx series corresponds to Snapdragon 8xx series.
   */
  private fun mapSmModelToSnapdragon(smModel: Int): Int? {
    return SM_TO_SNAPDRAGON_MAP[smModel]
  }

  companion object {
    private const val CPUINFO_PATH = "/proc/cpuinfo"
    private const val CPUINFO_HARDWARE_KEY = "Hardware"
    private const val CPUINFO_MODEL_KEY = "model name"
    private const val CPUINFO_PROCESSOR_KEY = "Processor"
    private const val UNKNOWN_PROCESSOR = "unknown"

    private const val WARNING_LOW_RAM =
      "Device has less than 4GB RAM. Local models may not perform well and could cause app instability."
    private const val WARNING_NO_NNAPI =
      "Device does not support NNAPI. Hardware acceleration will not be available."

    // Identifiers for Snapdragon processors
    private val SNAPDRAGON_IDENTIFIERS = listOf(
      "snapdragon",
      "qualcomm",
      "qcom",
      "sdm",
      "sm8",
      "sm7",
      "sm6",
      "msm",
    )

    // Regex patterns for extracting Snapdragon model numbers
    private val SNAPDRAGON_NAME_PATTERN = Regex(
      """(?:snapdragon|sd)\s*(\d{3,4})""",
      RegexOption.IGNORE_CASE,
    )
    private val SDM_PATTERN = Regex(
      """sdm(\d{3})""",
      RegexOption.IGNORE_CASE,
    )
    private val SM_PATTERN = Regex(
      """sm(\d{4})""",
      RegexOption.IGNORE_CASE,
    )

    // Mapping from SM model numbers to Snapdragon marketing numbers
    private val SM_TO_SNAPDRAGON_MAP = mapOf(
      // Snapdragon 8 Gen series
      8750 to 8, // 8 Elite
      8650 to 8, // 8 Gen 3
      8550 to 8, // 8 Gen 2
      8475 to 8, // 8s Gen 3
      8450 to 8, // 8 Gen 1
      // Snapdragon 800 series
      8350 to 888,
      8250 to 865,
      8150 to 855,
      // SDM series (older naming)
      845 to 845,
      835 to 835,
      821 to 821,
      820 to 820,
      // Snapdragon 7 series
      7675 to 7, // 7+ Gen 3
      7550 to 7, // 7 Gen 3
      7475 to 7, // 7s Gen 2
      7450 to 7, // 7 Gen 1
      // Snapdragon 6 series
      6450 to 6, // 6 Gen 1
      6375 to 6, // 6s Gen 3
      6350 to 6,  // 6 Gen 3
    )
  }
}
