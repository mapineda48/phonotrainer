package com.mapineda48.phonotrainer.core.domain.model

import java.io.File

/**
 * A clip's local media (`ClipContentRepository.media`, WP-10, from the `artifact` and `clip` rows). Unchanged from
 * work-plan Appendix A.3. The video's facts come from the listing's `media` (server-contract 6.3); rotation and
 * offset are 0 when unknown.
 */
data class ClipMedia(
  val jobId: JobId,
  val audioMix: File,
  val audioDialogue: File?,
  val videoFile: File?,
  val videoCodec: String?,
  val videoWidth: Int?,
  val videoHeight: Int?,
  val videoRotation: Int,
  val videoOffsetMs: Long,
  val poster: File?,
  val videoAvailability: VideoAvailability,
)

/**
 * A stretch of one clip's audio to play (lesson, practice, Learn examples, shadowing). Unchanged from work-plan
 * Appendix A.3. Bounds are ms on the analysed timeline, already padded by whoever builds the span (the quiz's
 * `padBefore`/`padAfter` are folded into [startMs] and [endMs]); the player stretches anything shorter than 0.25 s
 * around its middle so it can be heard (web `wordSpan`).
 */
data class ClipSpan(val jobId: JobId, val startMs: Long, val endMs: Long, val word: WordRef?)
