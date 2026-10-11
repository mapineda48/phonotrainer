package com.mapineda48.phonotrainer.core.domain.model

import java.util.concurrent.atomic.AtomicLong

// Work-plan Appendix A.9: settings, messages and notifications (WP-02, WP-24). The navigation block of A.9 lives in
// :core:navigation.

/** Every F-22 setting with its default; stored by :core:datastore (architecture 4.5). */
data class UserSettings(
  val appearance: Appearance = Appearance.SYSTEM,
  val colors: ColorChoice = ColorChoice.STANDARD,
  val patterns: PatternChoice = PatternChoice.FOLLOW_COLORS,
  val motion: MotionChoice = MotionChoice.FOLLOW_SYSTEM,
  val defaultSpeed: Float = 1f,
  val playEachShort: Boolean = true,
  val endOfShort: EndOfShort = EndOfShort.REPLAY_ONCE,
  val captions: CaptionMode = CaptionMode.MARKS,
  val listenFirst: Boolean = false,
  val track: Track = Track.MIX,
  val excludedFromMix: Set<JobId> = emptySet(),
  val questionsAfterSet: Boolean = true,
  val lessonGuidance: LessonGuidance = LessonGuidance.FULL,
  val downloadVideos: Boolean = true,
  val storageBudget: StorageBudget = StorageBudget.Limit(2_000_000_000),
  val notifyReady: Boolean = true,
  val notifyAttention: Boolean = true,
  val welcomeDone: Boolean = false,
  val notificationsAsked: Boolean = false,
)

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class ColorChoice { STANDARD, COLOR_VISION_FRIENDLY }

enum class PatternChoice { FOLLOW_COLORS, ON, OFF }

enum class MotionChoice { FOLLOW_SYSTEM, REDUCE }

enum class EndOfShort { REPLAY_ONCE, STOP, NEXT }

enum class CaptionMode { MARKS, PLAIN, IPA, OFF }

enum class LessonGuidance { FULL, COMPACT }

/** A coach mark's id; the catalogue is WP-13's. */
@JvmInline value class CoachMarkId(val value: String)

/**
 * A one-shot message the app shell shows as a snackbar (architecture 3.1). [args]: TOO_LATE_TO_CANCEL and
 * CANCEL_UNDONE carry the request title ("Cancel undone: “{title}” won't be cancelled.", D-47); CLIPS_REMOVED the
 * count; the others none. [id] comes from [nextId], the one allocator (posters use `UserMessages.post(kind, args)`).
 */
data class UserMessage(val id: Long, val kind: UserMessageKind, val args: List<String> = emptyList()) {
  companion object {
    private val ids = AtomicLong()

    /** A message id no other message of this process has (decision I-16). */
    fun nextId(): Long = ids.incrementAndGet()
  }
}

/**
 * CLIP_GONE: a clip target (notification, "New on this phone") whose clip is gone, "That clip is no longer on this
 * phone."; WORD_CLIP_GONE: a word target inside the app whose clip is gone, "That clip is no longer on your computer
 * or this phone." (spec 5.4; added after B1 as an additive member, decision I-14).
 */
enum class UserMessageKind {
  CLIP_GONE,
  REQUEST_REMOVED,
  LIBRARY_CHANGED,
  CLIPS_REMOVED,
  TOO_LATE_TO_CANCEL,
  CANCEL_UNDONE,
  WORD_CLIP_GONE,
}

enum class TransferKind { UPLOAD, DOWNLOAD, SHARE_COPY }

data class TransferProgress(val title: String, val doneBytes: Long, val totalBytes: Long?, val items: Int)

/** NOT_NEEDED below Android 13. */
enum class NotificationPermission { NOT_ASKED, GRANTED, DENIED, NOT_NEEDED }
