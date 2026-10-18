package com.mapineda48.phonotrainer.feature.practice

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.Clock
import com.mapineda48.phonotrainer.core.domain.api.ReferenceRepository
import com.mapineda48.phonotrainer.core.domain.api.SavedRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** When a saved item's short was last watched, in the words of the row ("Last watched 3 days ago"). */
@Immutable
internal sealed interface LastWatched {
  /** Not watched on this phone since it was saved (or never: an item saved before its short was seen). */
  data object Never : LastWatched

  data object Today : LastWatched

  data object Yesterday : LastWatched

  data class DaysAgo(val days: Int) : LastWatched

  /** A month or more ago: the date ("4 Oct", the year when it is not this one). */
  data class On(val date: LocalDate, val sameYear: Boolean) : LastWatched
}

/**
 * One row of Saved (spec F-09 item 3, design P-11): the word in its mark with its forms ([occurrence], from the
 * clip's analysis when it is on this phone), its [change], the clip, "Play", when it was last watched, and unsave.
 * An item whose clip is not on this phone ([available] false) stays listed with "Download the clip to watch it."
 */
@Immutable
internal data class SavedRowUi(
  val id: Long,
  val kind: SavedKind,
  val short: ShortId,
  val word: WordRef?,
  /** The word as transcribed, when the clip's analysis could be read. */
  val wordText: String?,
  val occurrence: Occurrence?,
  /** What "Play" plays: the word, with the next one when its change runs into it (web `crossesBoundary`). */
  val span: ClipSpan?,
  val change: ChangeRef?,
  val clipTitle: String,
  val lastWatched: LastWatched,
  val available: Boolean,
  val playing: Boolean = false,
)

@Immutable
internal data class SavedUiState(val loading: Boolean = true, val rows: List<SavedRowUi> = emptyList())

/**
 * Saved (spec F-09): the learner's saved shorts and words, most recently saved first ([SavedRepository], WP-14), each
 * read against its clip's analysis ([ClipContentRepository]) for the word, its forms and its times, and played with
 * the [SpanPlayer]. The list is local and always works offline; a clip that is not on this phone keeps its rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class SavedViewModel @Inject constructor(
  private val saved: SavedRepository,
  private val content: ClipContentRepository,
  private val references: ReferenceRepository,
  private val spanPlayer: SpanPlayer,
  private val settings: SettingsRepository,
  private val clock: Clock,
) : ViewModel() {
  /** The analyses read so far (only successful reads: a clip that comes back on the phone is read again). */
  private val analyses = mutableMapOf<JobId, ClipData>()

  private val rows = combine(saved.saved(), references.reference) { items, reference -> items to reference }
    .mapLatest { (items, reference) -> resolve(items, reference) }

  val state: StateFlow<SavedUiState> = combine(rows, spanPlayer.state) { list, playback ->
    SavedUiState(loading = false, rows = list.map { it.copy(playing = isPlaying(it, playback)) })
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SavedUiState())

  private fun isPlaying(row: SavedRowUi, playback: SpanPlaybackState): Boolean =
    playback.playing && row.span != null && playback.span == row.span

  private suspend fun resolve(items: List<SavedItem>, reference: Reference): List<SavedRowUi> {
    val now = clock.now()
    val zone = clock.zone()
    return items.sortedByDescending { it.savedAt }.map { item ->
      val clip = item.word?.let { analysis(it.jobId) }
      SavedLogic.row(item, clip, reference, lastWatched(item.lastWatchedAt, now, zone))
    }
  }

  private suspend fun analysis(id: JobId): ClipData? = analyses[id] ?: try {
    content.clipData(id).also { analyses[id] = it }
  } catch (_: ClipDataException) {
    null
  }

  /** "Play" on a row: plays its word, or stops it when it is the one playing. */
  fun play(row: SavedRowUi) {
    val span = row.span ?: return
    if (row.playing) {
      spanPlayer.stop()
      return
    }
    val track = settings.settings.value.track
    viewModelScope.launch { spanPlayer.play(span, 1f, track) }
  }

  /** Unsave from the row (spec F-09 item 3). */
  fun unsave(id: Long) {
    viewModelScope.launch { saved.unsave(id) }
  }

  fun stopPlayback() {
    spanPlayer.stop()
  }

  override fun onCleared() {
    spanPlayer.stop()
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L

    fun lastWatched(at: Instant?, now: Instant, zone: ZoneId): LastWatched = SavedLogic.lastWatched(at, now, zone)
  }
}

/** The pure rules of the Saved rows, tested on the JVM. */
internal object SavedLogic {
  /** "Last watched N days ago" up to this many days; the date after that. */
  private const val DAYS_SHOWN = 30L

  fun lastWatched(at: Instant?, now: Instant, zone: ZoneId): LastWatched {
    if (at == null) return LastWatched.Never
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
      days <= 0 -> LastWatched.Today
      days == 1L -> LastWatched.Yesterday
      days < DAYS_SHOWN -> LastWatched.DaysAgo(days.toInt())
      else -> LastWatched.On(day, sameYear = day.year == today.year)
    }
  }

  /**
   * The row of [item]: its word read from [clip] (null when the clip's analysis is not on this phone or the anchor no
   * longer points at a word), the change kept with it (else the word's first change), and what "Play" plays.
   */
  fun row(item: SavedItem, clip: ClipData?, reference: Reference, lastWatched: LastWatched): SavedRowUi {
    val ref = item.word
    val word = ref?.let { clip?.word(it) }
    val label = item.change ?: word?.changes?.firstOrNull()
    val change = label?.let { PracticeLogic.change(reference, it) }
    val next = if (word != null && word.crossesBoundary) clip?.nextWord(ref)?.let { clip.word(it) } else null
    val occurrence = if (word != null) {
      Occurrence(
        word = ref,
        shortFirstSegment = item.short.firstSegment,
        wordText = word.text,
        dictIpa = word.dictIpa,
        realizedIpa = word.realizedIpa,
        startMs = ms(word.startS),
        endMs = ms(word.endS),
        change = label ?: NO_CHANGE,
        diffCost = word.diffCost,
        advice = label?.let { reference.practice[it]?.advice } ?: Advice.UNDERSTAND,
        family = reference.familyFor(label ?: NO_CHANGE),
        crossesBoundary = word.crossesBoundary,
        nextWordText = next?.text,
        clipTitle = item.clipTitle,
        otherChanges = word.changes.filter { it != label },
      )
    } else {
      null
    }
    val span = if (word != null) ClipSpan(ref.jobId, ms(word.startS), ms((next ?: word).endS), ref) else null
    return SavedRowUi(
      id = item.id,
      kind = item.kind,
      short = item.short,
      word = ref,
      wordText = word?.text,
      occurrence = occurrence,
      span = span,
      change = change,
      clipTitle = item.clipTitle,
      lastWatched = lastWatched,
      available = item.available,
    )
  }

  private fun ms(seconds: Double): Long = (seconds * MS_PER_S).roundToLong()

  private const val MS_PER_S = 1000.0

  /** A word saved without a change: no family and no mark ([familyFor] answers OTHER). */
  private val NO_CHANGE = ChangeLabel("")
}
