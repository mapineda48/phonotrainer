package com.mapineda48.phonotrainer.feature.lesson

import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.CompareRow
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.phrase
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.word
import java.io.File

/**
 * A small clip for the lesson's JVM tests, built with WP-12's domain twins (no parsing): phrase 1 is “Did you get the
 * water?” (palatalization across “did” + “you”, flapping on “water”), phrase 2 a long phrase (over 6 s) with a lexical
 * contraction and a word the recognizer was unsure about, phrase 3 has no words, phrase 4 one plain word.
 */
internal object LessonFixtures {
  val JOB: JobId = AnalysisFixtures.JOB

  val DID = WordRef(JOB, 0, 0)
  val YOU = WordRef(JOB, 0, 1)
  val GET = WordRef(JOB, 0, 2)
  val THE = WordRef(JOB, 0, 3)
  val WATER = WordRef(JOB, 0, 4)
  val IM = WordRef(JOB, 1, 0)
  val GONNA = WordRef(JOB, 1, 1)
  val GO = WordRef(JOB, 1, 2)
  val OK = WordRef(JOB, 3, 0)

  val clip: ClipData = AnalysisFixtures.clip(
    listOf(
      phrase(
        0, 0.0, 1.1,
        words = listOf(
          word(0, "Did", 0.0, "d ɪ d", "d ɪ dʒ", changes = listOf("palatalization"), boundaryLinkNext = true, boundaryLinkType = "glide_j"),
          word(1, "you", 0.2, "j uː", "ə", changes = listOf("vowel_reduction"), diffCost = 0.4),
          word(2, "get", 0.4, "ɡ ɛ t", "ɡ ɛ t"),
          word(3, "the", 0.6, "ð ə", "ð ə"),
          word(4, "water?", 0.75, "w ɔ t ɚ", "w ɔ ɾ ɚ", changes = listOf("flapping"), diffCost = 0.1),
        ),
        prominence = listOf(0.3, 0.1, 0.6, 0.0, 1.0),
        wordClasses = listOf("content", "function", "content", "function", "content"),
      ),
      phrase(
        1, 2.5, 9.5,
        words = listOf(
          word(0, "I'm", 2.5, "aɪ m", "aɪ m"),
          word(1, "gonna", 2.7, "ɡ ʌ n ə", "ɡ ʌ n ə", changes = listOf("contraction_lex"), lexicalForm = "gonna"),
          word(2, "go", 3.0, "ɡ oʊ", "ɡ ə", changes = listOf("vowel_reduction"), lowConfidence = true),
        ),
      ),
      phrase(2, 9.6, 9.9),
      phrase(3, 10.0, 10.5, words = listOf(word(0, "ok", 10.0, "oʊ k eɪ", "oʊ k eɪ"))),
    ),
  )

  /** The rows `:core:analysis` makes for “did” + “you” (the next word's rows flagged). */
  val didRows: List<CompareRow> = listOf(
    row("d", "d", CompareStatus.SAME, 0.0),
    row("ɪ", "ɪ", CompareStatus.SAME, 0.06),
    row("d", "dʒ", CompareStatus.CHANGED, 0.12),
    row("j", null, CompareStatus.NOT_HEARD, 0.2, next = true),
    row("uː", "ə", CompareStatus.CHANGED, 0.26, next = true),
  )

  val waterRows: List<CompareRow> = listOf(
    row("w", "w", CompareStatus.SAME, 0.75),
    row("ɔ", "ɔ", CompareStatus.SAME, 0.81),
    row("t", "ɾ", CompareStatus.CHANGED, 0.87),
    row("ɚ", "ɚ", CompareStatus.SAME, 0.93),
  )

  private fun row(dictionary: String?, said: String?, status: CompareStatus, at: Double, next: Boolean = false) = CompareRow(
    dictionary = dictionary,
    said = said,
    status = status,
    change = null,
    dictionaryPhone = dictionary?.let { TimedPhone(it, at, at + 0.05) },
    saidPhone = said?.let { TimedPhone(it, at, at + 0.05) },
    nextWord = next,
  )

  /** The clip's local audio: the mix, and the dialogue track when [dialogue]. */
  fun media(dialogue: Boolean = false): ClipMedia = ClipMedia(
    jobId = JOB,
    audioMix = File("mix.wav"),
    audioDialogue = if (dialogue) File("dialogue.wav") else null,
    videoFile = null,
    videoCodec = null,
    videoWidth = null,
    videoHeight = null,
    videoRotation = 0,
    videoOffsetMs = 0,
    poster = null,
    videoAvailability = VideoAvailability.AudioOnlyClip,
  )
}

/** Library rows for the tests that need a clip's title. */
internal object LessonTestClips {
  fun detail(job: JobId, title: String): com.mapineda48.phonotrainer.core.domain.model.ClipDetail {
    val summary = com.mapineda48.phonotrainer.core.domain.model.ClipSummary(
      id = job,
      title = title,
      durationS = 10.5,
      words = 9,
      changeCount = 5,
      source = com.mapineda48.phonotrainer.core.domain.model.ClipSource(
        com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind.UPLOAD, null, null, "clip.mp3",
      ),
      availability = com.mapineda48.phonotrainer.core.domain.model.ClipAvailability.OnThisPhone,
      video = VideoAvailability.AudioOnlyClip,
      kept = false,
      origin = com.mapineda48.phonotrainer.core.domain.model.ClipOrigin.ACTIVE,
      olderRules = false,
      shortsSeen = 0,
      shortsTotal = 2,
      addedAt = java.time.Instant.EPOCH,
    )
    return com.mapineda48.phonotrainer.core.domain.model.ClipDetail(
      summary = summary, durationS = 10.5, segments = 4, changeCounts = emptyMap(), rulesVersion = 3,
      phoneEngine = "timit61", dialogueSeparation = true, hasDialogueTrack = true, studyPackBytes = null,
      videoBytes = null, lastWatched = null, resume = null, computerError = null,
    )
  }
}
