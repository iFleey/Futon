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

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks latency metrics for the perception pipeline.
 *
 * Maintains rolling averages over the last [windowSize] samples
 * and calculates P95 latency.
 */
class LatencyTracker(
  private val windowSize: Int = 100,
) {
  private val captureLatencies = RollingWindow(windowSize)
  private val detectionLatencies = RollingWindow(windowSize)
  private val ocrLatencies = RollingWindow(windowSize)
  private val totalLatencies = RollingWindow(windowSize)

  private val loopCount = AtomicLong(0)

  /**
   * Record latencies from a perception loop.
   */
  fun record(
    captureMs: Long,
    detectionMs: Long,
    ocrMs: Long,
    totalMs: Long,
  ) {
    captureLatencies.add(captureMs)
    detectionLatencies.add(detectionMs)
    ocrLatencies.add(ocrMs)
    totalLatencies.add(totalMs)
    loopCount.incrementAndGet()
  }

  fun getAverageCapture(): Long = captureLatencies.average()
  fun getAverageDetection(): Long = detectionLatencies.average()
  fun getAverageOcr(): Long = ocrLatencies.average()
  fun getAverageTotal(): Long = totalLatencies.average()

  fun getP95Total(): Long = totalLatencies.percentile(95)
  fun getLoopCount(): Long = loopCount.get()

  fun reset() {
    captureLatencies.clear()
    detectionLatencies.clear()
    ocrLatencies.clear()
    totalLatencies.clear()
    loopCount.set(0)
  }

  /**
   * Rolling window for latency samples.
   */
  private class RollingWindow(private val maxSize: Int) {
    private val values = LongArray(maxSize)
    private var index = 0
    private var count = 0

    @Synchronized
    fun add(value: Long) {
      values[index] = value
      index = (index + 1) % maxSize
      if (count < maxSize) count++
    }

    @Synchronized
    fun average(): Long {
      if (count == 0) return 0
      var sum = 0L
      for (i in 0 until count) {
        sum += values[i]
      }
      return sum / count
    }

    @Synchronized
    fun percentile(p: Int): Long {
      if (count == 0) return 0
      val sorted = values.copyOf(count).sortedArray()
      val idx = ((p / 100.0) * (count - 1)).toInt().coerceIn(0, count - 1)
      return sorted[idx]
    }

    @Synchronized
    fun clear() {
      index = 0
      count = 0
    }
  }
}
