package com.mapineda48.phonotrainer.core.requests.rules

/**
 * The background size rule (architecture 8.5, Android review H4, spec D-41): a worker without a foreground service is
 * stopped after about ten minutes, and a v1-core upload cut short restarts from zero, so a file starts in the
 * background only when this phone's measured upload rate says it finishes within [WINDOW_S] seconds. Larger files wait
 * (never failed) for the app to be open, where the upload runs as a visible transfer. The repository's
 * `WaitingInfo.needsAppOpen` and the uploader use this one function. Resumable uploads (v1-complete) lift the rule.
 */
internal object BackgroundWindow {
  /** Seconds of one background run the rule plans for. */
  const val WINDOW_S: Long = 360

  /** What fits when no upload was measured yet: 200 MB. */
  const val UNMEASURED_FIT_BYTES: Long = 200_000_000L

  /** The largest file a background run starts, given the moving average of measured rates (bytes per second). */
  fun limit(throughputBytesPerS: Double?): Long =
    throughputBytesPerS?.takeIf { it > 0 && it.isFinite() }?.let { (it * WINDOW_S).toLong() } ?: UNMEASURED_FIT_BYTES

  fun fits(fileBytes: Long, throughputBytesPerS: Double?, resumable: Boolean = false): Boolean =
    resumable || fileBytes <= limit(throughputBytesPerS)
}
