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
package me.fleey.futon.data.ai.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Strategy for selecting inference sources.
 *
 * Each strategy defines how the router selects and orders sources
 * when processing inference requests.
 */
@Serializable
sealed class RoutingStrategy {
  abstract val id: String

  abstract val displayName: String

  abstract val description: String

  /**
   * Follow user-defined priority order.
   * Sources are tried in the exact order specified by the user.
   */
  @Serializable
  @SerialName("priority_order")
  data object PriorityOrder : RoutingStrategy() {
    override val id: String = "priority_order"
    override val displayName: String = "Priority Order"
    override val description: String = "Follow user-defined priority order"
  }

  /**
   * Optimize for cost.
   * Prefers local model first, then cheapest cloud providers.
   */
  @Serializable
  @SerialName("cost_optimized")
  data object CostOptimized : RoutingStrategy() {
    override val id: String = "cost_optimized"
    override val displayName: String = "Cost Optimized"
    override val description: String = "Prefer local model, then cheapest cloud providers"
  }

  /**
   * Optimize for latency.
   * Selects source with lowest rolling average latency.
   */
  @Serializable
  @SerialName("latency_optimized")
  data object LatencyOptimized : RoutingStrategy() {
    override val id: String = "latency_optimized"
    override val displayName: String = "Latency Optimized"
    override val description: String = "Select source with lowest average latency"
  }

  /**
   * Optimize for reliability.
   * Selects source with highest success rate (last 20 requests).
   */
  @Serializable
  @SerialName("reliability_optimized")
  data object ReliabilityOptimized : RoutingStrategy() {
    override val id: String = "reliability_optimized"
    override val displayName: String = "Reliability Optimized"
    override val description: String = "Select source with highest success rate"
  }

  companion object {
    val DEFAULT: RoutingStrategy by lazy { PriorityOrder }

    val ALL_STRATEGIES: List<RoutingStrategy> by lazy {
      listOf(
        PriorityOrder,
        CostOptimized,
        LatencyOptimized,
        ReliabilityOptimized,
      )
    }

    fun fromId(id: String): RoutingStrategy? = ALL_STRATEGIES.find { it.id == id }
  }
}
