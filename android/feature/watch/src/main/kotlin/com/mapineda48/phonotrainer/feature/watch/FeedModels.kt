package com.mapineda48.phonotrainer.feature.watch

import androidx.compose.runtime.Immutable
import com.mapineda48.phonotrainer.core.designsystem.trace.TraceSample
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.UnitType
import com.mapineda48.phonotrainer.core.domain.model.Contour
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef

// The Watch screen's state (architecture 3.1: one immutable UI state per screen, built by WatchViewModel). Everything
// the cards draw comes from here and from the player's FeedPlaybackState; the playhead itself is read every frame
// from FeedPlayer.positionMs() (architecture 11.3), never stored.

/** What the feed shows (spec 5.3), as the header and the picker name it. */
@Immutable
internal sealed interface ModeName {
  /** The Mix; [today] names today's set ("Mix · Today's set"), otherwise another set. */
  data class Mix(val today: Boolean) : ModeName

  data class InOrder(val title: String?) : ModeName

  data class Change(val label: ChangeLabel, val name: String, val family: Family) : ModeName

  data object Saved : ModeName
}

/** The kinds of end card (spec F-06 item 9, F-08 item 6; design 9.15). */
internal enum class EndKind {
  /** "Done for today" / "Set done" with the tally and "What you met". */
  SET_SUMMARY,

  /** "End of {clip}" + "You saw 15 shorts with 14 kinds of change." */
  CLIP_END,

  /** "That's every “flapping” in your clips." */
  CHANGE_END,

  /** "That's all your saved shorts." */
  SAVED_END,

  /** "You've seen every short. New ones appear when your computer finishes a clip." */
  NOTHING_LEFT,
}

/** One page of the vertical pager. [key] is stable across rebuilds so the pager keeps its place. */
@Immutable
internal sealed interface FeedPage {
  val key: String

  /** A short: [queueIndex] is its index in the player's queue (only shorts are queued). */
  data class Short(
    val queueIndex: Int,
    val item: FeedItem,
    /** Its index in the set's items (Mix only). */
    val setPosition: Int? = null,
    /** "Saved" tag of a saved short in a set (F-08 item 4). */
    val savedTag: Boolean = false,
  ) : FeedPage {
    override val key: String = "short:${item.short.id.jobId.value}:${item.short.id.firstSegment}"
    val id: ShortId get() = item.short.id
    val info: ShortInfo get() = item.short
    val media: ClipMedia get() = item.media
  }

  /** A set question (F-08 item 5); [number] of [of] questions. */
  data class QuestionPage(
    val setPosition: Int,
    val number: Int,
    val of: Int,
    val question: Question,
    val choice: String? = null,
    val result: AnswerResult? = null,
  ) : FeedPage {
    override val key: String = "question:$setPosition"
  }

  data class End(val kind: EndKind) : FeedPage {
    override val key: String = "end:$kind"
  }
}

/** The tally and chips of the set summary card (F-08 item 6). */
@Immutable
internal data class SetSummary(
  val today: Boolean,
  val shorts: Int,
  val questions: Int,
  val right: Int,
  /** Spotlight changes met in the set, most met first: name, family, count. */
  val met: List<MetChange>,
  /** "Not enough clips to build questions yet." (F-08 Partial). */
  val noQuestions: Boolean,
)

@Immutable
internal data class MetChange(val label: ChangeLabel, val name: String, val family: Family, val count: Int)

/** What the "End of {clip}" card says and offers. */
@Immutable
internal data class ClipEnd(val title: String?, val shorts: Int, val kinds: Int, val next: NextClip?)

@Immutable
internal data class NextClip(val jobId: JobId, val title: String)

/** The empty states of Watch (spec F-06 States; design 9.8, 9.21). */
@Immutable
internal sealed interface FeedEmpty {
  /** Never paired, nothing on the phone. */
  data object FirstRun : FeedEmpty

  /** Paired, clips on the computer, none on the phone yet; [percent] of the sync when known. */
  data class ClipsComing(val percent: Int?) : FeedEmpty

  /** A change with no shorts on this phone. */
  data class NoShortsWithChange(val name: String) : FeedEmpty

  /** Saved mode with nothing saved. */
  data object NothingSaved : FeedEmpty

  /** In order on a clip that has no playable short (gone or not on this phone). */
  data object ClipNotHere : FeedEmpty
}

/** The bead row of a set (design 9.2): done, current, to come. */
@Immutable
internal data class SetProgress(val done: Int, val current: Int, val total: Int, val finished: Boolean)

/** The header's position words (F-07 item 1). */
@Immutable
internal sealed interface HeaderPosition {
  /** "3 of 8" with the bead row (sets). */
  data class InSet(val number: Int, val of: Int, val beads: SetProgress) : HeaderPosition

  /** "4 / 15" (In order, change, Saved). */
  data class Ordinal(val number: Int, val of: Int) : HeaderPosition

  /** "Question 2 of 5". */
  data class Question(val number: Int, val of: Int) : HeaderPosition

  /** "Done" with all beads filled. */
  data class Done(val beads: SetProgress?) : HeaderPosition

  data object None : HeaderPosition
}

/** One caption word (design 9.4): its mark, its states and what TalkBack reads (A-04). */
@Immutable
internal data class CaptionWord(
  val ref: WordRef,
  val text: String,
  val startMs: Long,
  val endMs: Long,
  /** The family whose mark it wears; null: unmarked. */
  val family: Family?,
  /** The change names, in order ("flapping"). */
  val changes: List<String>,
  val advice: Advice?,
  val lowConfidence: Boolean,
  val stressed: Boolean,
  /** Draws the ‿ arc to the next word. */
  val linksToNext: Boolean,
  val dictIpa: String?,
  val realizedIpa: String?,
  /** "gonna ← going to" after a contraction. */
  val contraction: String?,
  /** The studied word (the tether caret above it). */
  val spotlit: Boolean,
)

/** One phrase of a short, with its intonation badge (design 9.4). */
@Immutable
internal data class CaptionPhrase(
  val segment: Int,
  val startMs: Long,
  val endMs: Long,
  val words: List<CaptionWord>,
  val badge: IntonationBadge?,
)

/** "Question · rises", with "usually falls" when the contour differs from the expected one, and the uptalk chip. */
@Immutable
internal data class IntonationBadge(
  val type: UnitType,
  val contour: Contour?,
  val expected: Contour?,
  val uptalk: Boolean,
)

/** The spotlight slip (F-07 item 3, design 9.3). */
@Immutable
internal data class SpotlightSlip(
  val word: WordRef,
  /** The studied word as written ("water"), or "did" when [nextWordText] follows it. */
  val wordText: String,
  /** The second word of a change that crosses the boundary ("you"). */
  val nextWordText: String?,
  val label: ChangeLabel,
  val changeName: String,
  val family: Family,
  val advice: Advice,
  /** How widely it is used (" · casual"); null when the reference gives none. */
  val register: Register?,
  val dictIpa: String?,
  val realizedIpa: String?,
  /** The one-line cue from Learn's "How to hear it", shortened for the card. */
  val cue: String?,
  /** "gonna ← going to" for a contraction spotlight. */
  val contraction: String?,
  val startMs: Long,
  val endMs: Long,
  /** What "Play the word" plays (both words when the change crosses the boundary, F-07-AC2). */
  val span: ClipSpan,
)

/** Everything a short card draws from its clip data, computed once per short (off the main thread). */
@Immutable
internal data class ShortContent(
  val id: ShortId,
  val clipTitle: String?,
  val ordinal: Int,
  val ofTotal: Int,
  val playStartMs: Long,
  val playEndMs: Long,
  val phrases: List<CaptionPhrase>,
  val spotlight: SpotlightSlip?,
  /** Phrase boundaries on the progress trace, 0 to 1 of the play bounds. */
  val ticks: List<Float>,
  /** The spotlight word's place on the trace. */
  val pin: Float?,
  /** The short's pitch contour drawn as the progress trace. */
  val trace: List<TraceSample>,
  /** For the audio-only drum (design 7.3): pitch per phrase, in semitones around the clip median. */
  val pitch: List<PhrasePitch>,
) {
  val lengthMs: Long get() = (playEndMs - playStartMs).coerceAtLeast(1L)

  /** The phrase whose time holds [positionMs], else the last one that started, else the first. */
  fun phraseAt(positionMs: Long): CaptionPhrase? =
    phrases.lastOrNull { it.startMs <= positionMs } ?: phrases.firstOrNull()
}

/** A phrase's pitch for the drum: times (ms) and semitones around the clip's median, gaps where unvoiced. */
@Immutable
internal data class PhrasePitch(val segment: Int, val timesMs: List<Long>, val semitones: List<Float>) {
  val measurable: Boolean get() = timesMs.isNotEmpty()
}

/** How a card's media area looks (spec F-06 item 7, design 9.5). */
internal enum class MediaLook {
  /** Landscape video shown whole (16:9 box, letterbox). */
  LANDSCAPE_VIDEO,

  /** Portrait video filling the card, the caption slip over it. */
  PORTRAIT_VIDEO,

  /** The generated drum. */
  AUDIO_ONLY,

  /** Poster (or fallback) + "Video not on this phone · playing the audio". */
  VIDEO_NOT_ON_PHONE,

  /** Poster fallback + "Video not available from your computer". */
  VIDEO_NOT_AVAILABLE,

  /** Poster + "This video can't play on this phone. Listening still works." */
  CANNOT_PLAY,
}

/** The short's media look from its [ClipMedia] and whether the player reported a video decode failure. */
internal fun mediaLook(media: ClipMedia, decodeFailed: Boolean): MediaLook = when {
  decodeFailed -> MediaLook.CANNOT_PLAY
  media.videoAvailability == VideoAvailability.AudioOnlyClip -> MediaLook.AUDIO_ONLY
  media.videoAvailability == VideoAvailability.CannotPlayOnThisPhone -> MediaLook.CANNOT_PLAY
  media.videoAvailability == VideoAvailability.NotAvailableFromComputer -> MediaLook.VIDEO_NOT_AVAILABLE
  media.videoFile == null -> MediaLook.VIDEO_NOT_ON_PHONE
  isPortrait(media) -> MediaLook.PORTRAIT_VIDEO
  else -> MediaLook.LANDSCAPE_VIDEO
}

/** A video taller than wide once its rotation is applied. Unknown sizes count as landscape. */
internal fun isPortrait(media: ClipMedia): Boolean {
  val w = media.videoWidth ?: return false
  val h = media.videoHeight ?: return false
  val turned = media.videoRotation % 180 != 0
  return if (turned) w > h else h > w
}

/** The mode a feed plays, for persistence and the header (null while it is still being resolved). */
internal data class ResolvedMode(val mode: FeedMode, val name: ModeName)
