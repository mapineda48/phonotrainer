package com.mapineda48.phonotrainer.log

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.common.log.LogLevel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Architecture 20.4: the sink maps each level to `android.util.Log`, and a debuggable build logs everything. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidPhonoLogTest {
  @Before
  fun clear() = ShadowLog.clear()

  @Test
  fun everyLevelReachesItsLogPriority() {
    val error = IllegalStateException("boom")
    AndroidLogSink.write(LogLevel.DEBUG, "Sync", "d", null)
    AndroidLogSink.write(LogLevel.INFO, "Sync", "i", null)
    AndroidLogSink.write(LogLevel.WARN, "Sync", "w", error)
    AndroidLogSink.write(LogLevel.ERROR, "Sync", "e", null)

    assertThat(ShadowLog.getLogsForTag("Sync").map { it.type to it.msg })
      .containsExactly(Log.DEBUG to "d", Log.INFO to "i", Log.WARN to "w", Log.ERROR to "e").inOrder()
    assertThat(ShadowLog.getLogsForTag("Sync")[2].throwable).isSameInstanceAs(error)
  }

  @Test
  fun theUnitTestBuildIsDebuggableSoEverythingIsLogged() {
    val context: Context = ApplicationProvider.getApplicationContext()
    val log = LoggingModule.phonoLog(context)

    log.d("Sync") { "pass started" }

    assertThat(ShadowLog.getLogsForTag("Sync").map { it.msg }).containsExactly("pass started")
  }
}
