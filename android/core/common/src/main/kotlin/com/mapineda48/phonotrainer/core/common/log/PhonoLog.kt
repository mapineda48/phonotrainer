package com.mapineda48.phonotrainer.core.common.log

/**
 * The one logging facade (architecture 20.4), injected everywhere; the platform log is written only by its sink in
 * `:app`. Messages are lambdas, so a release build never even builds the text of a debug or info line. Warnings and
 * errors name an event code (`"sync.download_failed"`): a release build writes the code and the error's class only,
 * never an address, a link, a file name or a job title, and never the error's message or stack trace.
 *
 * ```
 * log.d { "probe ${address.host} answered in $ms ms" }
 * log.w("sync.download_failed", error) { "artifact $name of $jobId" }
 * ```
 */
interface PhonoLog {
  /** Development detail; dropped in release builds without calling [message]. */
  fun d(tag: String = TAG, message: () -> String)

  /** A notable step; dropped in release builds without calling [message]. */
  fun i(tag: String = TAG, message: () -> String)

  /** Something went wrong and was handled. Release builds write [event] and the class of [error] only. */
  fun w(event: String, error: Throwable? = null, detail: (() -> String)? = null)

  /** Something failed. Release builds write [event] and the class of [error] only. */
  fun e(event: String, error: Throwable? = null, detail: (() -> String)? = null)

  companion object {
    /** The tag of every line that names no other. */
    const val TAG: String = "PhonoTrainer"

    /** Logs nothing: for tests and previews that do not look at the log. */
    val NONE: PhonoLog = LeveledPhonoLog(LogSink { _, _, _, _ -> }, verbose = false)
  }
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Where lines go: the platform log in the app (architecture 20.4), a list in tests. */
fun interface LogSink {
  fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * The [PhonoLog] of every build. [verbose] (debuggable builds) writes everything; otherwise debug and info lines are
 * dropped before their lambda runs, and warnings and errors keep only the event code and the error's class name.
 * An event that is not a code (lower-case letters, digits, `.`, `_`, `-`, at most 64 characters) is never written as
 * given, since it may carry data: it becomes [INVALID_EVENT].
 */
class LeveledPhonoLog(private val sink: LogSink, private val verbose: Boolean) : PhonoLog {
  override fun d(tag: String, message: () -> String) {
    if (verbose) sink.write(LogLevel.DEBUG, tag, message(), null)
  }

  override fun i(tag: String, message: () -> String) {
    if (verbose) sink.write(LogLevel.INFO, tag, message(), null)
  }

  override fun w(event: String, error: Throwable?, detail: (() -> String)?) = event(LogLevel.WARN, event, error, detail)

  override fun e(event: String, error: Throwable?, detail: (() -> String)?) = event(LogLevel.ERROR, event, error, detail)

  private fun event(level: LogLevel, event: String, error: Throwable?, detail: (() -> String)?) {
    val code = if (EVENT_CODE.matches(event)) event else INVALID_EVENT
    if (verbose) {
      val text = detail?.invoke()?.takeIf { it.isNotEmpty() }?.let { "$code: $it" } ?: code
      sink.write(level, PhonoLog.TAG, text, error)
    } else {
      val text = error?.let { "$code (${it.javaClass.name})" } ?: code
      sink.write(level, PhonoLog.TAG, text, null)
    }
  }

  companion object {
    /** Written instead of an event that is not a code. */
    const val INVALID_EVENT: String = "invalid_event_code"

    private val EVENT_CODE = Regex("^[a-z][a-z0-9_.-]{0,63}$")
  }
}
