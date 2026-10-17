package com.mapineda48.phonotrainer.feature.watch

/**
 * The time a short was actually played in one visit (spec F-06 item 5, architecture 10): the position advancing while
 * playing, at any speed, counts; a drag of the progress bar, a seek, a replay that jumps back and the wrap of a loop do
 * not. The feed samples the player a few times a second and reports [playedMs] once a visit: when it reaches 80 % of
 * [lengthMs] ([seen]) or when the learner leaves the short.
 */
internal class SeenTracker(private val lengthMs: Long) {
  var playedMs: Long = 0L
    private set

  private var last: Long? = null

  /** A sample of the playhead: [positionMs] on the clip's timeline and whether the player was playing. */
  fun sample(positionMs: Long, playing: Boolean) {
    val previous = last
    last = positionMs
    if (!playing || previous == null) return
    val step = positionMs - previous
    if (step in 1..MAX_STEP_MS) playedMs += step
  }

  /** The learner moved the playhead (a drag or a seek): the jump never counts. */
  fun seeked(positionMs: Long) {
    last = positionMs
  }

  /** Seen: 80 % of the short's length actually played in this visit (F-06-AC11). */
  val seen: Boolean
    get() = lengthMs > 0 && playedMs * 10 >= lengthMs * 8

  companion object {
    /** The sampling period of the feed. */
    const val PERIOD_MS: Long = 200L

    /** A step longer than this between two samples is a jump, not playback. */
    const val MAX_STEP_MS: Long = 1_000L
  }
}
