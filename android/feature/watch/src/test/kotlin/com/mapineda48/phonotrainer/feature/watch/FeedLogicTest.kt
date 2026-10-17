package com.mapineda48.phonotrainer.feature.watch

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.StudySet
import com.mapineda48.phonotrainer.core.domain.model.StudySetItem
import com.mapineda48.phonotrainer.core.domain.model.StudySetKind
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.time.LocalDate
import org.junit.Test

/** The feed's pure rules (spec F-06, F-07, F-08; architecture 9.3, 10, 11.3). */
class FeedLogicTest {
  private val reference = SampleReference.reference
  private val clip = AnalysisFixtures.clipData()
  private val job = AnalysisFixtures.JOB

  private fun short(segment: Int = 0, last: Int = 1, spotlight: Spotlight? = null) = ShortInfo(
    id = ShortId(job, segment),
    ordinal = 1,
    ofTotal = 1,
    lastSegment = last,
    playStartMs = 0,
    playEndMs = 3_400,
    spotlight = spotlight,
    markedWords = 3,
  )

  @Test
  fun speedCyclesOneThreeQuartersHalfAndTappingTwiceFromOneGivesHalf() {
    // F-07-AC3: "Speed" tapped twice from 1× plays at 0.5×.
    assertThat(FeedLogic.nextSpeed(FeedLogic.nextSpeed(1f))).isEqualTo(0.5f)
    assertThat(FeedLogic.nextSpeed(0.5f)).isEqualTo(1f)
    assertThat(FeedLogic.nextSpeed(0.6f)).isEqualTo(1f)
    assertThat(speedText(0.75f)).isEqualTo("0.75")
    assertThat(speedText(0.5f)).isEqualTo("0.5")
    assertThat(speedText(1f)).isEqualTo("1")
  }

  @Test
  fun loopCyclesOffPhraseShort() {
    assertThat(FeedLogic.nextLoop(LoopMode.OFF)).isEqualTo(LoopMode.PHRASE)
    assertThat(FeedLogic.nextLoop(LoopMode.PHRASE)).isEqualTo(LoopMode.SHORT)
    assertThat(FeedLogic.nextLoop(LoopMode.SHORT)).isEqualTo(LoopMode.OFF)
  }

  @Test
  fun aWordIsFilledFromFiftyMsBeforeItsStartToThreeHundredFiftyAfterItsEnd() {
    val phrase = WatchPreviewData.phrases().first()
    val water = phrase.words.indexOfFirst { it.text == "water" }
    assertThat(FeedLogic.playingWord(phrase, 44_749)).isNull()
    assertThat(FeedLogic.playingWord(phrase, 44_750)).isEqualTo(water)
    assertThat(FeedLogic.playingWord(phrase, 45_750)).isEqualTo(water)
    assertThat(FeedLogic.playingWord(phrase, 45_751)).isNull()
    // Overlapping windows: the word that started last is the one being said.
    assertThat(FeedLogic.playingWord(phrase, 42_660)).isEqualTo(2)
  }

  @Test
  fun theCueIsTheFirstClauseOfHowToHearIt() {
    assertThat(FeedLogic.shortCue("A t or d between vowels becomes one very quick tap of the tongue: “water” sounds like “wadder”."))
      .isEqualTo("A t or d between vowels becomes one very quick tap of the tongue.")
    assertThat(FeedLogic.shortCue("Unstressed syllables get short. Listen for the small words."))
      .isEqualTo("Unstressed syllables get short.")
    assertThat(FeedLogic.shortCue("No stop at all")).isEqualTo("No stop at all")
    assertThat(FeedLogic.shortCue("  ")).isNull()
    assertThat(FeedLogic.shortCue(null)).isNull()
  }

  @Test
  fun theCardShowsThePhrasesOfTheShortWithTheirMarksAndAdvice() {
    val content = FeedLogic.content(short(), null, clip, reference, "A clip") { null }
    assertThat(content.phrases.map { it.segment }).containsExactly(0, 1).inOrder()
    val does = content.phrases[0].words[0]
    assertThat(does.text).isEqualTo("does")
    assertThat(does.family).isEqualTo(Family.REDUCTION)
    assertThat(does.changes).containsExactly("vowel reduction")
    assertThat(does.advice).isEqualTo(Advice.PRODUCE)
    assertThat(does.linksToNext).isTrue()
    val work = content.phrases[0].words[2]
    assertThat(work.family).isNull()
    assertThat(work.stressed).isTrue()
    val go = content.phrases[1].words[1]
    assertThat(go.lowConfidence).isTrue()
    // The trace has a tick at the second phrase and follows the clip's pitch.
    assertThat(content.ticks).hasSize(1)
    assertThat(content.trace).isNotEmpty()
    assertThat(content.pitch.all { it.measurable }).isTrue()
  }

  @Test
  fun theSlipNamesTheWordItsFormsTheCueAndPlaysTheWord() {
    val spot = Spotlight(WordRef(job, 0, 1), ChangeLabel("t_deletion"), Advice.PRODUCE, crossesBoundary = false)
    val content = FeedLogic.content(short(spotlight = spot), null, clip, reference, null) { "The t is skipped: “next day”." }
    val slip = checkNotNull(content.spotlight)
    assertThat(slip.wordText).isEqualTo("that")
    assertThat(slip.nextWordText).isNull()
    assertThat(slip.changeName).isEqualTo("t/d deletion")
    assertThat(slip.dictIpa).isEqualTo("ðæt")
    assertThat(slip.realizedIpa).isEqualTo("ðæ")
    assertThat(slip.cue).isEqualTo("The t is skipped.")
    assertThat(slip.span).isEqualTo(ClipSpan(job, slip.startMs, slip.endMs, WordRef(job, 0, 1)))
    assertThat(content.pin).isNotNull()
    assertThat(content.phrases[0].words[1].spotlit).isTrue()
  }

  @Test
  fun aChangeThatCrossesTheBoundaryPlaysTheWordAndTheNextOne() {
    // F-07-AC2: "Play the word" plays the spotlight word and the next word together.
    val spot = Spotlight(WordRef(job, 0, 0), ChangeLabel("vowel_reduction"), Advice.PRODUCE, crossesBoundary = true)
    val content = FeedLogic.content(short(spotlight = spot), null, clip, reference, null) { null }
    val slip = checkNotNull(content.spotlight)
    val does = clip.phrases[0].words[0]
    val that = clip.phrases[0].words[1]
    assertThat(slip.nextWordText).isEqualTo("that")
    assertThat(slip.span.startMs).isEqualTo((does.startS * 1000).toLong())
    assertThat(slip.span.endMs).isEqualTo(Math.round(that.endS * 1000))
  }

  @Test
  fun aSpotlightTheModeForcesWinsOverTheDefaultOne() {
    // F-07 rule 1/2: the saved word (or the change mode's candidate) replaces the default spotlight.
    val default = Spotlight(WordRef(job, 0, 1), ChangeLabel("t_deletion"), Advice.PRODUCE, false)
    val forced = Spotlight(WordRef(job, 0, 0), ChangeLabel("vowel_reduction"), Advice.PRODUCE, false)
    val content = FeedLogic.content(short(spotlight = default), forced, clip, reference, null) { null }
    assertThat(content.spotlight?.wordText).isEqualTo("does")
  }

  @Test
  fun aContractionShowsItsReducedFormAndItsFullForm() {
    val wanna = AnalysisFixtures.word(0, "wanna", 0.0, "w ɑ n ə", "w ɑ n ə", changes = listOf("contraction_lex"), lexicalForm = "wanna")
      .copy(lexicalExpansion = "want to")
    assertThat(FeedLogic.contractionOf(wanna)).isEqualTo("wanna ← want to")
    val plain = AnalysisFixtures.word(0, "water", 0.0, "w ɔ t ɚ", "w ɔ ɾ ɚ", changes = listOf("flapping"))
    assertThat(FeedLogic.contractionOf(plain)).isNull()
  }

  @Test
  fun inOrderStartsWhereTheLearnerLeftTheClip() {
    val items = (0 until 10).map { i -> short(segment = i * 2, last = i * 2 + 1).copy(ordinal = i + 1) }
    // F-06-AC10: left at short 7 → resumes at short 7.
    assertThat(FeedLogic.inOrderStart(items, null, fromStart = false, resume = ShortId(job, 12))).isEqualTo(6)
    assertThat(FeedLogic.inOrderStart(items, null, fromStart = true, resume = ShortId(job, 12))).isEqualTo(0)
    assertThat(FeedLogic.inOrderStart(items, firstSegment = 9, fromStart = false, resume = null)).isEqualTo(4)
    // A resume anchored to a phrase of an older rule lands on the short that holds it (F-09-AC3).
    assertThat(FeedLogic.inOrderStart(items, null, fromStart = false, resume = ShortId(job, 13))).isEqualTo(6)
    assertThat(FeedLogic.inOrderStart(items, null, fromStart = false, resume = null)).isEqualTo(0)
    assertThat(FeedLogic.inOrderStart(emptyList(), null, false, null)).isEqualTo(0)
  }

  @Test
  fun theSetSummaryCountsShortsQuestionsRightAnswersAndChangesMet() {
    val flapping = Spotlight(WordRef(job, 0, 1), ChangeLabel("flapping"), Advice.PRODUCE, false)
    val linking = Spotlight(WordRef(job, 1, 0), ChangeLabel("linking"), Advice.PRODUCE, false)
    val items = listOf(
      StudySetItem.Short(short(0).copy(spotlight = flapping), null, savedTag = false, dropped = false),
      StudySetItem.Short(short(2).copy(spotlight = linking), null, savedTag = false, dropped = false),
      StudySetItem.Short(short(4).copy(spotlight = flapping), null, savedTag = false, dropped = false),
      StudySetItem.Short(short(6).copy(spotlight = flapping), null, savedTag = false, dropped = true),
      StudySetItem.QuestionItem(WatchPreviewData.question, choice = "a", correct = true),
      StudySetItem.QuestionItem(WatchPreviewData.question, choice = "b", correct = false),
    )
    val set = StudySet(7, StudySetKind.TODAY, LocalDate.of(2026, 10, 10), items, cursor = 6, answered = 2, right = 1, completedAt = null)
    val summary = FeedLogic.setSummary(set, reference, questionsOn = true)
    assertThat(summary.today).isTrue()
    assertThat(summary.shorts).isEqualTo(3)
    assertThat(summary.questions).isEqualTo(2)
    // F-08-AC5: "N of 5 right" matches the answers.
    assertThat(summary.right).isEqualTo(1)
    assertThat(summary.met.map { it.label.value to it.count }).containsExactly("flapping" to 2, "linking" to 1).inOrder()
    assertThat(summary.noQuestions).isFalse()
    val off = FeedLogic.setSummary(set.copy(items = items.take(3)), reference, questionsOn = true)
    assertThat(off.noQuestions).isTrue()
  }

  @Test
  fun theMediaLookFollowsTheVideoAndTheDecoder() {
    val landscape = WatchPreviewData.media()
    assertThat(mediaLook(landscape, decodeFailed = false)).isEqualTo(MediaLook.LANDSCAPE_VIDEO)
    assertThat(mediaLook(landscape, decodeFailed = true)).isEqualTo(MediaLook.CANNOT_PLAY)
    assertThat(mediaLook(WatchPreviewData.media(width = 720, height = 1280), false)).isEqualTo(MediaLook.PORTRAIT_VIDEO)
    assertThat(mediaLook(WatchPreviewData.media().copy(videoRotation = 90), false)).isEqualTo(MediaLook.PORTRAIT_VIDEO)
    assertThat(mediaLook(WatchPreviewData.media(video = VideoAvailability.AudioOnlyClip), false)).isEqualTo(MediaLook.AUDIO_ONLY)
    assertThat(mediaLook(WatchPreviewData.media(video = VideoAvailability.NotOnThisPhone), false)).isEqualTo(MediaLook.VIDEO_NOT_ON_PHONE)
    assertThat(mediaLook(WatchPreviewData.media(video = VideoAvailability.NotAvailableFromComputer), false))
      .isEqualTo(MediaLook.VIDEO_NOT_AVAILABLE)
    assertThat(mediaLook(WatchPreviewData.media(video = VideoAvailability.CannotPlayOnThisPhone), false)).isEqualTo(MediaLook.CANNOT_PLAY)
  }

  @Test
  fun aTwentySecondShortIsSeenAtSixteenSecondsPlayedAndADragNeverCounts() {
    // F-06-AC11: 10 s played then a drag to the end is not seen; 16 s played in the visit is.
    val tracker = SeenTracker(20_000)
    var position = 0L
    tracker.seeked(position)
    repeat(50) {
      position += SeenTracker.PERIOD_MS
      tracker.sample(position, playing = true)
    }
    assertThat(tracker.playedMs).isEqualTo(10_000)
    tracker.seeked(20_000)
    tracker.sample(20_000, playing = false)
    assertThat(tracker.seen).isFalse()
    position = 4_000
    tracker.seeked(position)
    repeat(30) {
      position += SeenTracker.PERIOD_MS
      tracker.sample(position, playing = true)
    }
    assertThat(tracker.playedMs).isEqualTo(16_000)
    assertThat(tracker.seen).isTrue()
  }

  @Test
  fun pausedTimeAndJumpsBackAreNotPlayback() {
    val tracker = SeenTracker(10_000)
    tracker.sample(0, playing = true)
    tracker.sample(200, playing = true)
    tracker.sample(5_000, playing = false)
    tracker.sample(5_200, playing = true)
    tracker.sample(100, playing = true) // a loop wrapped
    tracker.sample(4_000, playing = true) // a jump forward
    assertThat(tracker.playedMs).isEqualTo(400)
  }

  @Test
  fun theDrumSamplesBreakAtUnvoicedGaps() {
    val pitch = PhrasePitch(0, listOf(0L, 10L, 20L, 200L, 210L), listOf(0f, 1f, 2f, 3f, 4f))
    val samples = drumSamples(pitch, 0, 210)
    assertThat(samples.count { it.y == null }).isEqualTo(1)
    assertThat(FeedLogic.semitoneAt(pitch, 15)).isEqualTo(2f)
    assertThat(FeedLogic.semitoneAt(pitch, 300)).isNull()
  }

  @Test
  fun theReferenceGivesTheRegisterOfTheSpotlightChange() {
    val spot = Spotlight(WordRef(job, 0, 0), ChangeLabel("vowel_reduction"), Advice.PRODUCE, false)
    val content = FeedLogic.content(short(spotlight = spot), null, clip, reference, null) { null }
    assertThat(content.spotlight?.register).isEqualTo(reference.practice[ChangeLabel("vowel_reduction")]?.register ?: Register.UNIVERSAL)
  }
}
