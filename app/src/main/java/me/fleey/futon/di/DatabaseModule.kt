package me.fleey.futon.di

import android.content.Context
import me.fleey.futon.data.trace.db.HotPathCacheDao
import me.fleey.futon.data.trace.db.TraceDao
import me.fleey.futon.data.trace.db.TraceDatabase
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
class DatabaseModule {

  @Single
  fun provideTraceDatabase(context: Context): TraceDatabase {
    return TraceDatabase.getInstance(context)
  }

  @Single
  fun provideTraceDao(database: TraceDatabase): TraceDao {
    return database.traceDao()
  }

  @Single
  fun provideHotPathCacheDao(database: TraceDatabase): HotPathCacheDao {
    return database.hotPathCacheDao()
  }
}
