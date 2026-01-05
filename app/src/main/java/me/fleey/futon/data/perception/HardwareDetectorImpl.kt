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

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import me.fleey.futon.data.perception.models.DelegateType
import me.fleey.futon.data.perception.models.HardwareCapabilities
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [HardwareDetector::class])
class HardwareDetectorImpl(
) : HardwareDetector {

  @Volatile
  private var cachedCapabilities: HardwareCapabilities? = null

  private val socModel: String by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching { Build.SOC_MODEL }.getOrDefault("")
    } else ""
  }

  private val socInfo: SocInfo by lazy { detectSocInfo() }

  override fun detectCapabilities(): HardwareCapabilities {
    cachedCapabilities?.let { return it }

    val gpuInfo = detectGpuInfo()
    val hexagonInfo = if (socInfo.isQualcomm) detectHexagonInfo() else HexagonInfo.UNAVAILABLE

    val capabilities = HardwareCapabilities(
      hasGpu = gpuInfo.available,
      gpuVendor = gpuInfo.vendor,
      gpuRenderer = gpuInfo.renderer,
      hasNnapi = true,
      nnapiVersion = Build.VERSION.SDK_INT,
      hasHexagonDsp = hexagonInfo.available,
      hexagonVersion = hexagonInfo.version,
      hasNpu = socInfo.hasNpu,
      recommendedDelegate = determineRecommendedDelegate(gpuInfo, hexagonInfo),
      isSnapdragon865Plus = socInfo.isSnapdragon865Plus,
    )

    cachedCapabilities = capabilities
    Log.i(TAG, "Hardware: $capabilities")
    return capabilities
  }

  fun clearCache() {
    cachedCapabilities = null
  }

  private fun detectSocInfo(): SocInfo {
    val soc = socModel.lowercase()
    val hw = Build.HARDWARE.lowercase()
    val board = Build.BOARD.lowercase()

    val qualcommIndicators = listOf("qcom", "msm", "kona", "lahaina", "taro", "kalama", "pineapple")
    val isQualcomm = qualcommIndicators.any { hw.contains(it) || board.contains(it) } ||
      soc.startsWith("sm") || soc.startsWith("sdm")

    val snapdragon865PlusSocs = setOf(
      "sm8250", "sm8350", "sm8450", "sm8475", "sm8550", "sm8650",
      "sm7450", "sm7475", "sm7550", "sm7675",
    )
    val snapdragon865PlusCodenames = setOf("kona", "lahaina", "taro", "kalama", "pineapple")

    val isSnapdragon865Plus = snapdragon865PlusSocs.any { soc.contains(it) } ||
      snapdragon865PlusCodenames.any { hw.contains(it) }

    val hasNpu = isQualcomm && (soc.startsWith("sm8") || soc.startsWith("sm7")) ||
      soc.contains("dimensity") ||
      (soc.startsWith("mt") && soc.any { it in '8'..'9' }) ||
      board.contains("exynos") || soc.contains("exynos")

    return SocInfo(isQualcomm, isSnapdragon865Plus, hasNpu)
  }

  private fun detectGpuInfo(): GpuInfo {
    queryOpenGLInfo()?.let { (vendor, renderer) ->
      return GpuInfo(true, vendor, renderer)
    }

    val soc = socModel.lowercase()
    val hw = Build.HARDWARE.lowercase()

    val (vendor, renderer) = when {
      socInfo.isQualcomm -> "Qualcomm" to detectAdrenoRenderer(soc, hw)
      hw.contains("mali") || Build.BOARD.lowercase().contains("exynos") -> "ARM" to "Mali"
      hw.contains("powervr") -> "PowerVR" to "PowerVR"
      hw.contains("tegra") -> "NVIDIA" to "Tegra"
      soc.contains("dimensity") || soc.startsWith("mt") -> "ARM" to "Mali (MediaTek)"
      else -> return GpuInfo(true, null, null)
    }

    return GpuInfo(true, vendor, renderer)
  }

  private fun detectAdrenoRenderer(soc: String, hw: String): String = when {
    soc.contains("sm8650") || hw.contains("pineapple") -> "Adreno 750"
    soc.contains("sm8550") || hw.contains("kalama") -> "Adreno 740"
    soc.contains("sm8450") || hw.contains("taro") -> "Adreno 730"
    soc.contains("sm8350") || hw.contains("lahaina") -> "Adreno 660"
    soc.contains("sm8250") || hw.contains("kona") -> "Adreno 650"
    else -> "Adreno"
  }

  private fun queryOpenGLInfo(): Pair<String, String>? {
    var display: EGLDisplay? = null
    var eglContext: EGLContext? = null
    var surface: EGLSurface? = null

    return try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (display == EGL14.EGL_NO_DISPLAY) return null

      val version = IntArray(2)
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

      val configAttribs = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE,
      )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
        return null
      }

      val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
      eglContext =
        EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
      if (eglContext == EGL14.EGL_NO_CONTEXT) return null

      val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
      surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
      if (surface == EGL14.EGL_NO_SURFACE) return null

      if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return null

      val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
      val renderer = GLES20.glGetString(GLES20.GL_RENDERER)

      if (vendor != null && renderer != null) vendor to renderer else null
    } catch (e: Exception) {
      Log.w(TAG, "OpenGL query failed", e)
      null
    } finally {
      display?.takeIf { it != EGL14.EGL_NO_DISPLAY }?.let { d ->
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        surface?.let { EGL14.eglDestroySurface(d, it) }
        eglContext?.let { EGL14.eglDestroyContext(d, it) }
        EGL14.eglTerminate(d)
      }
    }
  }

  private fun detectHexagonInfo(): HexagonInfo {
    val hasLibs = HEXAGON_LIB_PATHS.any { path ->
      HEXAGON_LIBS.any { lib -> File("$path/$lib").exists() }
    }

    if (!hasLibs && !socInfo.isSnapdragon865Plus) {
      return HexagonInfo.UNAVAILABLE
    }

    val version = detectHexagonVersion()
    return HexagonInfo(true, version)
  }

  private fun detectHexagonVersion(): String? {
    val soc = socModel.lowercase()
    val hw = Build.HARDWARE.lowercase()

    return HEXAGON_VERSIONS.entries.firstOrNull { (key, _) ->
      soc.contains(key) || hw.contains(key)
    }?.value ?: if (soc.startsWith("sm") || soc.startsWith("sdm")) "Hexagon" else null
  }

  private fun determineRecommendedDelegate(gpu: GpuInfo, hexagon: HexagonInfo): DelegateType =
    when {
      hexagon.available && socInfo.isSnapdragon865Plus -> DelegateType.HEXAGON_DSP
      gpu.available -> DelegateType.GPU
      else -> DelegateType.XNNPACK
    }

  private data class SocInfo(
    val isQualcomm: Boolean,
    val isSnapdragon865Plus: Boolean,
    val hasNpu: Boolean,
  )

  private data class GpuInfo(
    val available: Boolean,
    val vendor: String?,
    val renderer: String?,
  )

  private data class HexagonInfo(
    val available: Boolean,
    val version: String?,
  ) {
    companion object {
      val UNAVAILABLE = HexagonInfo(false, null)
    }
  }

  companion object {
    private const val TAG = "HardwareDetector"

    private val HEXAGON_LIB_PATHS = listOf(
      "/vendor/lib64", "/vendor/lib",
      "/system/vendor/lib64", "/system/vendor/lib",
    )

    private val HEXAGON_LIBS = listOf(
      "libhexagon_nn_skel.so",
      "libhexagon_nn_skel_v66.so", "libhexagon_nn_skel_v68.so",
      "libhexagon_nn_skel_v69.so", "libhexagon_nn_skel_v73.so",
      "libQnnHtp.so", "libQnnHtpV69Stub.so", "libQnnHtpV73Stub.so",
      "libcdsprpc.so",
    )

    private val HEXAGON_VERSIONS = mapOf(
      "sm8650" to "Hexagon 8 Gen 3", "pineapple" to "Hexagon 8 Gen 3",
      "sm8550" to "Hexagon 8 Gen 2", "kalama" to "Hexagon 8 Gen 2",
      "sm8475" to "Hexagon 790",
      "sm8450" to "Hexagon 790", "taro" to "Hexagon 790",
      "sm8350" to "Hexagon 780", "lahaina" to "Hexagon 780",
      "sm8250" to "Hexagon 698", "kona" to "Hexagon 698",
      "sm8150" to "Hexagon 690",
      "sm7550" to "Hexagon 698", "sm7675" to "Hexagon 698",
      "sm7450" to "Hexagon 695", "sm7475" to "Hexagon 695",
      "sm7350" to "Hexagon 692", "sm7325" to "Hexagon 692",
      "sm7250" to "Hexagon 690",
    )
  }
}
