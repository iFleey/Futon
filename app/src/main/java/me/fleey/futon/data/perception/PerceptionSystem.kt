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

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import me.fleey.futon.data.perception.models.HardwareCapabilities
import me.fleey.futon.data.perception.models.PerceptionConfig
import me.fleey.futon.data.perception.models.PerceptionMetrics
import me.fleey.futon.data.perception.models.PerceptionOperationResult
import me.fleey.futon.data.perception.models.PerceptionSystemState
import java.io.Closeable

/**
 * Main entry point for the perception system.
 */
interface PerceptionSystem : Closeable {

  val state: StateFlow<PerceptionSystemState>

  val config: PerceptionConfig?

  /**
   * Initialize the perception system with configuration.
   *
   * @param config Perception configuration
   * @return true if initialization succeeded
   */
  suspend fun initialize(config: PerceptionConfig = PerceptionConfig.DEFAULT): Boolean

  /**
   * Set the MediaProjection for screen capture.
   * Must be called after initialize() and before perceive().
   *
   * @param projection MediaProjection from user permission grant
   * @return true if capture started successfully
   */
  fun setMediaProjection(projection: MediaProjection): Boolean

  fun hasActiveCapture(): Boolean

  /**
   * Execute a single perception loop.
   *
   * Pipeline: capture -> detection -> OCR (on text regions)
   *
   * @return PerceptionOperationResult with detected elements or error
   */
  suspend fun perceive(): PerceptionOperationResult

  fun getHardwareCapabilities(): HardwareCapabilities

  fun observeMetrics(): Flow<PerceptionMetrics>

  fun getCurrentMetrics(): PerceptionMetrics

  fun pause()

  suspend fun resume(): Boolean

  fun isReady(): Boolean

  fun isPaused(): Boolean

  override fun close()
}
