package com.mapineda48.phonotrainer.core.common.log

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

/**
 * Architecture 20.4: lambda messages, release builds never build debug or info text, and warnings and errors keep only
 * the event code and the error's class.
 */
class PhonoLogTest {
  private data class Line(val level: LogLevel, val tag: String, val message: String, val error: Throwable?)

  private val lines = mutableListOf<Line>()
  private val sink = LogSink { level, tag, message, error -> lines += Line(level, tag, message, error) }

  private val neverCalled: () -> String = { throw AssertionError("a release build must not build this message") }

  @Test
  fun aReleaseBuildNeverCallsDebugOrInfoLambdas() {
    val log = LeveledPhonoLog(sink, verbose = false)

    log.d(message = neverCalled)
    log.d("Sync", neverCalled)
    log.i(message = neverCalled)

    assertThat(lines).isEmpty()
  }

  @Test
  fun aReleaseBuildWritesOnlyTheEventCodeAndTheErrorClass() {
    val log = LeveledPhonoLog(sink, verbose = false)
    val error = IOException("connect to 192.0.2.10:8000 failed for /data/user/0/clips/x.wav")

    log.w("sync.download_failed", error, neverCalled)
    log.e("pairing.store_failed", detail = neverCalled)

    assertThat(lines).containsExactly(
      Line(LogLevel.WARN, PhonoLog.TAG, "sync.download_failed (java.io.IOException)", null),
      Line(LogLevel.ERROR, PhonoLog.TAG, "pairing.store_failed", null),
    ).inOrder()
    assertThat(lines.joinToString { it.message }).doesNotContain("192.0.2.10")
  }

  @Test
  fun anEventThatIsNotACodeIsNeverWrittenAsGiven() {
    listOf(false, true).forEach { verbose ->
      lines.clear()
      val log = LeveledPhonoLog(sink, verbose)

      log.w("failed for http://192.0.2.10:8000/api/phone/jobs")
      log.e("Sync.Failed")
      log.e("x".repeat(65))

      assertThat(lines.map { it.message })
        .containsExactly(LeveledPhonoLog.INVALID_EVENT, LeveledPhonoLog.INVALID_EVENT, LeveledPhonoLog.INVALID_EVENT)
    }
  }

  @Test
  fun aDebuggableBuildWritesEverything() {
    val log = LeveledPhonoLog(sink, verbose = true)
    val error = IllegalStateException("boom")

    log.d { "probe answered in 12 ms" }
    log.i("Sync") { "pass started" }
    log.w("sync.download_failed", error) { "artifact audio_mix" }
    log.e("sync.pass_failed")

    assertThat(lines).containsExactly(
      Line(LogLevel.DEBUG, PhonoLog.TAG, "probe answered in 12 ms", null),
      Line(LogLevel.INFO, "Sync", "pass started", null),
      Line(LogLevel.WARN, PhonoLog.TAG, "sync.download_failed: artifact audio_mix", error),
      Line(LogLevel.ERROR, PhonoLog.TAG, "sync.pass_failed", null),
    ).inOrder()
  }

  @Test
  fun noneLogsNothing() {
    PhonoLog.NONE.d(message = neverCalled)
    PhonoLog.NONE.w("sync.download_failed", IOException(), neverCalled)
    assertThat(lines).isEmpty()
  }
}
