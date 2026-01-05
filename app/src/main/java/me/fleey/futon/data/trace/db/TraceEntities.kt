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
package me.fleey.futon.data.trace.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for traces table.
 */
@Entity(tableName = "traces")
data class TraceEntity(
  @PrimaryKey
  @ColumnInfo(name = "id")
  val id: String,

  @ColumnInfo(name = "task_description")
  val taskDescription: String,

  @ColumnInfo(name = "success")
  val success: Boolean,

  @ColumnInfo(name = "created_at")
  val createdAt: Long,
)

/**
 * Room entity for trace_steps table.
 */
@Entity(
  tableName = "trace_steps",
  foreignKeys = [
    ForeignKey(
      entity = TraceEntity::class,
      parentColumns = ["id"],
      childColumns = ["trace_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["trace_id"]),
    Index(value = ["ui_hash"]),
  ],
)
data class TraceStepEntity(
  @PrimaryKey(autoGenerate = true)
  @ColumnInfo(name = "id")
  val id: Long = 0,

  @ColumnInfo(name = "trace_id")
  val traceId: String,

  @ColumnInfo(name = "step_order")
  val stepOrder: Int,

  @ColumnInfo(name = "ui_hash")
  val uiHash: String,

  @ColumnInfo(name = "action_type")
  val actionType: String,

  @ColumnInfo(name = "action_data")
  val actionData: String,

  @ColumnInfo(name = "latency_ms")
  val latencyMs: Long,

  @ColumnInfo(name = "success")
  val success: Boolean,
)

/**
 * Room entity for hot_path_cache table.
 */
@Entity(
  tableName = "hot_path_cache",
  indices = [
    Index(value = ["confidence"], orders = [Index.Order.DESC]),
  ],
)
data class HotPathCacheEntity(
  @PrimaryKey
  @ColumnInfo(name = "ui_hash")
  val uiHash: String,

  @ColumnInfo(name = "action_type")
  val actionType: String,

  @ColumnInfo(name = "action_data")
  val actionData: String,

  @ColumnInfo(name = "confidence")
  val confidence: Float,

  @ColumnInfo(name = "success_count")
  val successCount: Int,

  @ColumnInfo(name = "last_used")
  val lastUsed: Long,
)
