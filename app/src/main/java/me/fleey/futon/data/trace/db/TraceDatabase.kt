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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    TraceEntity::class,
    TraceStepEntity::class,
    HotPathCacheEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class TraceDatabase : RoomDatabase() {
  abstract fun traceDao(): TraceDao
  abstract fun hotPathCacheDao(): HotPathCacheDao

  companion object {
    private const val DATABASE_NAME = "futon_traces.db"

    @Volatile
    private var INSTANCE: TraceDatabase? = null

    fun getInstance(context: Context): TraceDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
      }
    }

    private fun buildDatabase(context: Context): TraceDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        TraceDatabase::class.java,
        DATABASE_NAME,
      )
        .fallbackToDestructiveMigration(false)
        .build()
    }
  }
}
