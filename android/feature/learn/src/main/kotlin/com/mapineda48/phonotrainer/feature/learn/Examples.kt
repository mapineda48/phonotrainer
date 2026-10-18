package com.mapineda48.phonotrainer.feature.learn

import androidx.navigation3.runtime.NavKey
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.navigation.ClipKey
import com.mapineda48.phonotrainer.core.navigation.LessonKey
import com.mapineda48.phonotrainer.core.navigation.LessonOrigin
import com.mapineda48.phonotrainer.core.navigation.LessonPresentation
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowPlayback
import com.mapineda48.phonotrainer.core.ui.component.OccurrenceRowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Plays the examples of one screen through the app's one [SpanPlayer] (architecture 11.5): one at a time, tapping the
 * playing one stops it, and it stops only what it started, never another screen's sound. A run owes a stop until it
 * ends on its own, so [stop] silences it even after its coroutine was cancelled with the screen.
 */
internal class ExamplePlayer(
  private val scope: CoroutineScope,
  private val player: SpanPlayer,
  private val track: () -> Track,
) {
  private val current = MutableStateFlow<WordRef?>(null)

  /** The word of the example playing now, or null. */
  val playing: StateFlow<WordRef?> = current.asStateFlow()

  private var job: Job? = null
  private var run: Any? = null
  private var owed = false

  /** Plays [occurrence] at normal speed on the learner's track; stops it when it is the one playing. */
  fun toggle(occurrence: Occurrence) {
    if (current.value == occurrence.word) {
      stop()
      return
    }
    stop()
    val span = OccurrenceRowPlayback.span(occurrence)
    val mine = Any()
    run = mine
    owed = true
    current.value = occurrence.word
    job = scope.launch {
      try {
        player.play(span, speed = 1f, track = track(), loop = false)
        if (run === mine) owed = false
      } finally {
        if (run === mine) current.value = null
      }
    }
  }

  fun stop() {
    if (owed) player.stop()
    owed = false
    run = null
    job?.cancel()
    job = null
    current.value = null
  }
}

/** The rows of [examples], the one playing marked. */
internal fun rows(examples: List<Occurrence>, playing: WordRef?): List<OccurrenceRowState> =
  examples.map { OccurrenceRowState(it, playing = it.word == playing) }

/**
 * Where "Open in its lesson" goes (spec F-10 item 1, 5.4): the word's lesson as a full page when its clip is on this
 * phone; the Clip page when the clip is still in the catalogue but no longer here; the Library with "That clip is no
 * longer on your computer or this phone." (`WORD_CLIP_GONE`, decision I-14) when it is gone. A damaged clip still opens
 * its lesson, which says so.
 */
internal class ExampleLessons(
  private val content: ClipContentRepository,
  private val clips: ClipRepository,
  private val messages: UserMessages,
) {
  suspend fun target(word: WordRef): NavKey {
    val here = try {
      content.clipData(word.jobId)
      true
    } catch (unreadable: ClipDataException) {
      unreadable.error != AppError.Content.Missing
    }
    return when {
      here -> LessonKey(word.jobId, word.segment, word.word, LessonOrigin.LEARN, LessonPresentation.FULL)
      clips.clip(word.jobId).first() != null -> ClipKey(word.jobId)
      else -> {
        messages.post(UserMessageKind.WORD_CLIP_GONE)
        LibraryKey
      }
    }
  }
}
