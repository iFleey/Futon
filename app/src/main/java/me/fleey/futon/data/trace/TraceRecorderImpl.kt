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
package me.fleey.futon.data.trace

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.fleey.futon.data.routing.models.Action
import me.fleey.futon.data.trace.db.HotPathCacheDao
import me.fleey.futon.data.trace.db.HotPathCacheEntity
import me.fleey.futon.data.trace.db.TraceDao
import me.fleey.futon.data.trace.db.TraceEntity
import me.fleey.futon.data.trace.db.TraceStepEntity
import me.fleey.futon.data.trace.models.ActionData
import me.fleey.futon.data.trace.models.CachedAction
import me.fleey.futon.data.trace.models.Trace
import me.fleey.futon.data.trace.models.TraceId
import me.fleey.futon.data.trace.models.TraceStats
import me.fleey.futon.data.trace.models.TraceStep
import org.koin.core.annotation.Single
import java.util.UUID

@Single(binds = [TraceRecorder::class])
class TraceRecorderImpl(
  private val context: Context,
  private val traceDao: TraceDao,
  private val hotPathCacheDao: HotPathCacheDao,
  private val json: Json,
) : TraceRecorder {

  private val mutex = Mutex()
  private var currentRecording: RecordingState? = null

  private data class RecordingState(
    val traceId: TraceId,
    val taskDescription: String,
    val steps: MutableList<PendingStep>,
    val startTime: Long,
  )

  private data class PendingStep(
    val uiHash: String,
    val action: Action,
    val success: Boolean,
    val timestamp: Long,
  )

  override fun startRecording(taskDescription: String): TraceId {
    val traceId = TraceId(UUID.randomUUID().toString())
    currentRecording = RecordingState(
      traceId = traceId,
      taskDescription = taskDescription,
      steps = mutableListOf(),
      startTime = System.currentTimeMillis(),
    )
    return traceId
  }

  override fun recordStep(uiHash: String, action: Action, success: Boolean) {
    currentRecording?.steps?.add(
      PendingStep(
        uiHash = uiHash,
        action = action,
        success = success,
        timestamp = System.currentTimeMillis(),
      ),
    )
  }

  override suspend fun stopRecording(success: Boolean): Result<TraceId> = mutex.withLock {
    val recording = currentRecording ?: return Result.failure(
      IllegalStateException("No recording in progress"),
    )

    return try {
      val traceEntity = TraceEntity(
        id = recording.traceId.id,
        taskDescription = recording.taskDescription,
        success = success,
        createdAt = recording.startTime,
      )

      val stepEntities = recording.steps.mapIndexed { index, step ->
        val actionData = ActionData.fromAction(step.action)
        TraceStepEntity(
          traceId = recording.traceId.id,
          stepOrder = index,
          uiHash = step.uiHash,
          actionType = actionData.type.name,
          actionData = json.encodeToString(actionData),
          latencyMs = if (index > 0) {
            step.timestamp - recording.steps[index - 1].timestamp
          } else {
            step.timestamp - recording.startTime
          },
          success = step.success,
        )
      }

      traceDao.insertTraceWithSteps(traceEntity, stepEntities)

      if (success) {
        updateHotPathCacheFromSteps(recording.steps)
      }

      currentRecording = null
      Result.success(recording.traceId)
    } catch (e: Exception) {
      currentRecording = null
      Result.failure(e)
    }
  }

  override fun isRecording(): Boolean = currentRecording != null

  override fun cancelRecording() {
    currentRecording = null
  }

  override suspend fun lookupAction(uiHash: String): CachedAction? {
    val entity = hotPathCacheDao.getByUiHash(uiHash) ?: return null
    hotPathCacheDao.updateLastUsed(uiHash, System.currentTimeMillis())
    return entity.toCachedAction(json)
  }

  override suspend fun recordSuccessfulAction(uiHash: String, action: Action) {
    val existing = hotPathCacheDao.getByUiHash(uiHash)
    val now = System.currentTimeMillis()

    if (existing != null) {
      hotPathCacheDao.incrementSuccessCount(uiHash, now)
    } else {
      val actionData = ActionData.fromAction(action)
      hotPathCacheDao.insertOrUpdate(
        HotPathCacheEntity(
          uiHash = uiHash,
          actionType = actionData.type.name,
          actionData = json.encodeToString(actionData),
          confidence = 0.5f,
          successCount = 1,
          lastUsed = now,
        ),
      )
    }
  }

  override suspend fun promoteToHotPath(traceId: TraceId): Result<Unit> {
    val trace = getTrace(traceId) ?: return Result.failure(
      IllegalArgumentException("Trace not found: ${traceId.id}"),
    )

    if (!trace.success) {
      return Result.failure(
        IllegalStateException("Cannot promote failed trace to hot path"),
      )
    }

    val now = System.currentTimeMillis()
    trace.steps.forEach { step ->
      val existing = hotPathCacheDao.getByUiHash(step.uiHash)
      val actionData = ActionData.fromAction(step.action)

      if (existing != null) {
        hotPathCacheDao.incrementSuccessCount(step.uiHash, now)
      } else {
        hotPathCacheDao.insertOrUpdate(
          HotPathCacheEntity(
            uiHash = step.uiHash,
            actionType = actionData.type.name,
            actionData = json.encodeToString(actionData),
            confidence = 0.75f,
            successCount = 3,
            lastUsed = now,
          ),
        )
      }
    }

    return Result.success(Unit)
  }

  override suspend fun getTrace(traceId: TraceId): Trace? {
    val entity = traceDao.getTrace(traceId.id) ?: return null
    val stepEntities = traceDao.getStepsForTrace(traceId.id)
    return entity.toTrace(stepEntities, json)
  }

  override suspend fun getRecentTraces(limit: Int): List<Trace> {
    return traceDao.getRecentTraces(limit).map { entity ->
      val steps = traceDao.getStepsForTrace(entity.id)
      entity.toTrace(steps, json)
    }
  }

  override suspend fun getStats(): TraceStats {
    val totalTraces = traceDao.getTraceCount()
    val successfulTraces = traceDao.getSuccessfulTraceCount()
    val hotPathEntries = hotPathCacheDao.getHighConfidenceCount()
    val storageSize = getDatabaseSize()

    return TraceStats(
      totalTraces = totalTraces,
      successfulTraces = successfulTraces,
      hotPathEntries = hotPathEntries,
      storageSizeBytes = storageSize,
    )
  }

  override fun observeHotPathEntries(): Flow<List<CachedAction>> {
    return hotPathCacheDao.observeHighConfidenceEntries().map { entities ->
      entities.map { it.toCachedAction(json) }
    }
  }

  override suspend fun pruneStorage(maxStorageBytes: Long) {
    val currentSize = getDatabaseSize()
    if (currentSize <= maxStorageBytes) return

    val targetSize = (maxStorageBytes * 0.8).toLong()
    var currentSizeAfterPrune = currentSize

    while (currentSizeAfterPrune > targetSize) {
      hotPathCacheDao.deleteOldestLowConfidence(count = 50)
      traceDao.deleteOldestFailedTraces(count = 20)
      currentSizeAfterPrune = getDatabaseSize()

      if (currentSizeAfterPrune == currentSize) break
    }
  }

  override suspend fun clearAll() {
    traceDao.deleteAllTraces()
    hotPathCacheDao.deleteAll()
  }

  private suspend fun updateHotPathCacheFromSteps(steps: List<PendingStep>) {
    val now = System.currentTimeMillis()
    steps.filter { it.success }.forEach { step ->
      val existing = hotPathCacheDao.getByUiHash(step.uiHash)
      val actionData = ActionData.fromAction(step.action)

      if (existing != null) {
        hotPathCacheDao.incrementSuccessCount(step.uiHash, now)
      } else {
        hotPathCacheDao.insertOrUpdate(
          HotPathCacheEntity(
            uiHash = step.uiHash,
            actionType = actionData.type.name,
            actionData = json.encodeToString(actionData),
            confidence = 0.5f,
            successCount = 1,
            lastUsed = now,
          ),
        )
      }
    }
  }

  private fun getDatabaseSize(): Long {
    val dbFile = context.getDatabasePath("futon_traces.db")
    return if (dbFile.exists()) dbFile.length() else 0L
  }

  private fun TraceEntity.toTrace(steps: List<TraceStepEntity>, json: Json): Trace {
    return Trace(
      id = TraceId(id),
      taskDescription = taskDescription,
      steps = steps.map { it.toTraceStep(json) },
      success = success,
      createdAt = createdAt,
    )
  }

  private fun TraceStepEntity.toTraceStep(json: Json): TraceStep {
    val actionData = json.decodeFromString<ActionData>(actionData)
    return TraceStep(
      uiHash = uiHash,
      action = actionData.toAction(),
      latencyMs = latencyMs,
      success = success,
      stepOrder = stepOrder,
    )
  }

  private fun HotPathCacheEntity.toCachedAction(json: Json): CachedAction {
    val actionData = json.decodeFromString<ActionData>(actionData)
    return CachedAction(
      uiHash = uiHash,
      action = actionData.toAction(),
      confidence = confidence,
      successCount = successCount,
      lastUsed = lastUsed,
    )
  }
}
