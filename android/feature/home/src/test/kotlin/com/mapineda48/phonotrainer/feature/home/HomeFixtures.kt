package com.mapineda48.phonotrainer.feature.home

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.time.Instant
import java.time.LocalDate

/** Sets, shorts and a paired computer for Home's tests (made-up data). */
object HomeFixtures {
  val job = JobId("20261004-091519-a77c")

  /** A short of [job] starting at phrase [segment], [lengthMs] long, whose spotlight is [change] (none when null). */
  fun short(segment: Int, change: String?, lengthMs: Long = 20_000, ordinal: Int = segment + 1, ofTotal: Int = 15, id: JobId = job) = ShortInfo(
    id = ShortId(id, segment),
    ordinal = ordinal,
    ofTotal = ofTotal,
    lastSegment = segment,
    playStartMs = segment * 30_000L,
    playEndMs = segment * 30_000L + lengthMs,
    spotlight = change?.let { Spotlight(WordRef(id, segment, 0), ChangeLabel(it), Advice.PRODUCE, crossesBoundary = false) },
    markedWords = 1,
  )

  fun shortItem(segment: Int, change: String?, saved: Boolean = false, done: Boolean = false, dropped: Boolean = false) =
    StudySetItem.Short(short(segment, change), short(segment, change).spotlight, savedTag = saved, dropped = dropped, done = done)

  fun question(change: String = "flapping", choice: String? = null): StudySetItem.QuestionItem {
    val word = WordRef(job, 0, 0)
    val occurrence = Occurrence(
      word = word,
      shortFirstSegment = 0,
      wordText = "water",
      dictIpa = "ˈwɔtɚ",
      realizedIpa = "ˈwɔɾɚ",
      startMs = 0,
      endMs = 400,
      change = ChangeLabel(change),
      diffCost = 1.0,
      advice = Advice.PRODUCE,
      family = Family.TD,
      crossesBoundary = false,
      nextWordText = null,
      clipTitle = "Coffee shop small talk",
    )
    val question = Question(
      kind = QuestionKind.CHANGE,
      key = "q-$change",
      prompt = "Which change?",
      clip = ClipSpan(job, 0, 400, word),
      context = null,
      options = emptyList(),
      answer = change,
      change = ChangeLabel(change),
      occurrence = occurrence,
      phrase = null,
    )
    return StudySetItem.QuestionItem(question, choice, choice?.let { it == change })
  }

  /** F-03-AC10's set: 8 shorts (2 saved) with flapping, linking, h-dropping, flapping, t/d deletion, vowel reduction,
   *  linking and flapping, then 5 questions. */
  fun acTenSet(completedAt: Instant? = null, done: Int = 0): StudySet {
    val changes = listOf("flapping", "linking", "h_dropping", "flapping", "t_deletion", "vowel_reduction", "linking", "flapping")
    val shorts = changes.mapIndexed { index, change -> shortItem(index, change, saved = index == 0 || index == 2, done = index < done) }
    return StudySet(
      id = 7,
      kind = StudySetKind.TODAY,
      date = LocalDate.of(2026, 10, 4),
      items = shorts + List(5) { question() },
      cursor = done,
      answered = 0,
      right = 0,
      completedAt = completedAt,
    )
  }

  private fun word(index: Int, text: String) = Word(
    index = index,
    text = text,
    startS = index.toDouble(),
    endS = index + 0.5,
    dictIpa = null,
    canonicalIpa = null,
    realizedIpa = null,
    canonicalPhones = emptyList(),
    realizedPhones = emptyList(),
    changes = emptyList(),
    diffCost = 0.0,
    lowConfidence = false,
    boundaryLinkNext = false,
    lexicalForm = null,
    oov = false,
  )

  /** Clip data of [id] whose phrase k holds [phrases] k's words. */
  fun clipData(id: JobId, vararg phrases: List<String>) = ClipData(
    jobId = id,
    revision = "0123456789abcdef",
    meta = ClipMeta(60.0, "en", 3, "timit61", true, "0.1.0"),
    phrases = phrases.mapIndexed { index, words ->
      Phrase(
        index = index,
        startS = index * 5.0,
        endS = index * 5.0 + 4.0,
        text = words.joinToString(" "),
        words = words.mapIndexed(::word),
        f0 = null,
        finalContour = null,
        emphasisWord = null,
        prominence = null,
        wordClasses = null,
        intonationUnits = emptyList(),
      )
    },
    summary = ClipDataSummary(mapOf(ChangeLabel("flapping") to 1), emptyMap()),
  )

  fun paired() = PairedComputer(
    serverId = ServerId("server-1"),
    name = "Desk PC",
    deviceId = DeviceId("device-1"),
    deviceName = "Test phone",
    address = HostPort("192.0.2.10", 8765),
    libraryId = LibraryId("library-1"),
    pairedAt = Instant.EPOCH,
    lastSeen = null,
    lastSync = null,
    apiVersion = 6,
    capabilities = null,
  )
}
