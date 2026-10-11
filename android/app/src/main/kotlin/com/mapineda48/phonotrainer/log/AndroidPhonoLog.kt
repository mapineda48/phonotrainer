package com.mapineda48.phonotrainer.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.mapineda48.phonotrainer.core.common.log.LeveledPhonoLog
import com.mapineda48.phonotrainer.core.common.log.LogLevel
import com.mapineda48.phonotrainer.core.common.log.LogSink
import com.mapineda48.phonotrainer.core.common.log.PhonoLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The Android sink of the [PhonoLog] facade (architecture 20.4): the only code of the app that uses `android.util.Log`
 * (the hygiene check enforces it). What reaches it is already filtered by [LeveledPhonoLog].
 */
object AndroidLogSink : LogSink {
  override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
    when (level) {
      LogLevel.DEBUG -> Log.d(tag, message, error)
      LogLevel.INFO -> Log.i(tag, message, error)
      LogLevel.WARN -> Log.w(tag, message, error)
      LogLevel.ERROR -> Log.e(tag, message, error)
    }
  }
}

/** One [PhonoLog] for the whole app: everything in debuggable builds, event codes only otherwise. */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
  @Provides
  @Singleton
  fun phonoLog(@ApplicationContext context: Context): PhonoLog = LeveledPhonoLog(
    sink = AndroidLogSink,
    verbose = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
  )
}
