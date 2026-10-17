package com.mapineda48.phonotrainer.feature.watch

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AnswerResult
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Contour
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.UnitType
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.io.File

/**
 * Sample feed data for the previews of every Watch state (definition of done 2.5 item 7) and for the screen tests: a
 * short of "Two friends at the lake" whose spotlight is the flapping in “water”, in every media case.
 */
internal object WatchPreviewData {
  val job = JobId("20261004-133519-a77c")
  val audioJob = JobId("20261005-101010-b001")
  private val flapping = ChangeLabel("flapping")
  private val palatalization = ChangeLabel("palatalization")
  const val TITLE = "Two friends at the lake"

  val spotlight = Spotlight(WordRef(job, 4, 4), flapping, Advice.PRODUCE, crossesBoundary = false)

  fun info(ordinal: Int = 4, of: Int = 15, segment: Int = 4, jobId: JobId = job, spot: Spotlight? = spotlight) = ShortInfo(
    id = ShortId(jobId, segment),
    ordinal = ordinal,
    ofTotal = of,
    lastSegment = segment + 1,
    playStartMs = 42_000,
    playEndMs = 56_000,
    spotlight = spot?.copy(word = spot.word.copy(jobId = jobId, segment = segment)),
    markedWords = 4,
  )

  fun media(
    jobId: JobId = job,
    video: VideoAvailability = VideoAvailability.OnThisPhone,
    width: Int = 1280,
    height: Int = 720,
    dialogue: Boolean = true,
  ) = ClipMedia(
    jobId = jobId,
    audioMix = File("audio_mix.wav"),
    audioDialogue = if (dialogue) File("audio_dialogue.wav") else null,
    videoFile = if (video == VideoAvailability.OnThisPhone) File("original.mp4") else null,
    videoCodec = "h264",
    videoWidth = width,
    videoHeight = height,
    videoRotation = 0,
    videoOffsetMs = 0,
    poster = null,
    videoAvailability = video,
  )

  private fun word(
    segment: Int,
    index: Int,
    text: String,
    startMs: Long,
    endMs: Long,
    family: Family? = null,
    changes: List<String> = emptyList(),
    advice: Advice? = null,
    spotlit: Boolean = false,
    lowConfidence: Boolean = false,
    stressed: Boolean = false,
    links: Boolean = false,
    dict: String? = null,
    said: String? = null,
    contraction: String? = null,
    jobId: JobId = job,
  ) = CaptionWord(
    ref = WordRef(jobId, segment, index),
    text = text,
    startMs = startMs,
    endMs = endMs,
    family = family,
    changes = changes,
    advice = advice,
    lowConfidence = lowConfidence,
    stressed = stressed,
    linksToNext = links,
    dictIpa = dict,
    realizedIpa = said,
    contraction = contraction,
    spotlit = spotlit,
  )

  /** The short's two phrases: "Did you get the water?" (spotlight "water") and "I'm gonna jump in." */
  fun phrases(jobId: JobId = job, segment: Int = 4): List<CaptionPhrase> = listOf(
    CaptionPhrase(
      segment = segment,
      startMs = 42_250,
      endMs = 46_800,
      words = listOf(
        word(segment, 0, "Did", 42_250, 42_500, Family.BOUNDARY, listOf("palatalization"), Advice.PRODUCE, links = true, dict = "dɪd", said = "dɪdʒ", jobId = jobId),
        word(segment, 1, "you", 42_500, 42_700, Family.REDUCTION, listOf("vowel reduction"), Advice.PRODUCE, dict = "ju", said = "jə", jobId = jobId),
        word(segment, 2, "get", 42_700, 43_000, Family.TD, listOf("flapping"), Advice.PRODUCE, dict = "ɡɛt", said = "ɡɛɾ", jobId = jobId),
        word(segment, 3, "the", 43_000, 43_200, dict = "ðə", said = "ðə", lowConfidence = true, jobId = jobId),
        word(
          segment, 4, "water", 44_800, 45_400, Family.TD, listOf("flapping"), Advice.PRODUCE,
          spotlit = true, stressed = true, dict = "ˈwɔtɚ", said = "ˈwɔɾɚ", jobId = jobId,
        ),
      ),
      badge = IntonationBadge(UnitType.YES_NO_QUESTION, Contour.RISING, expected = null, uptalk = false),
    ),
    CaptionPhrase(
      segment = segment + 1,
      startMs = 47_200,
      endMs = 55_600,
      words = listOf(
        word(segment + 1, 0, "I'm", 47_200, 47_500, dict = "aɪm", said = "əm", jobId = jobId),
        word(
          segment + 1, 1, "gonna", 47_500, 48_000, Family.LEXICAL, listOf("lexical contraction"), Advice.PRODUCE,
          dict = "ɡoʊɪŋtu", said = "ɡʌnə", contraction = "gonna ← going to", jobId = jobId,
        ),
        word(segment + 1, 2, "jump", 48_000, 48_500, dict = "dʒʌmp", said = "dʒʌmp", jobId = jobId),
        word(segment + 1, 3, "in", 48_500, 48_900, dict = "ɪn", said = "ɪn", jobId = jobId),
      ),
      badge = IntonationBadge(UnitType.STATEMENT, Contour.RISING, expected = Contour.FALLING, uptalk = true),
    ),
  )

  fun slip(jobId: JobId = job, segment: Int = 4, advice: Advice = Advice.PRODUCE) = SpotlightSlip(
    word = WordRef(jobId, segment, 4),
    wordText = "water",
    nextWordText = null,
    label = flapping,
    changeName = "flapping",
    family = Family.TD,
    advice = advice,
    register = Register.UNIVERSAL,
    dictIpa = "ˈwɔtɚ",
    realizedIpa = "ˈwɔɾɚ",
    cue = "A t or d between vowels becomes one very quick tap of the tongue.",
    contraction = null,
    startMs = 44_800,
    endMs = 45_400,
    span = ClipSpan(jobId, 44_800, 45_400, WordRef(jobId, segment, 4)),
  )

  /** The boundary-crossing spotlight "“did” + “you”" (F-07-AC2). */
  fun crossingSlip(jobId: JobId = job, segment: Int = 4) = SpotlightSlip(
    word = WordRef(jobId, segment, 0),
    wordText = "did",
    nextWordText = "you",
    label = palatalization,
    changeName = "palatalization",
    family = Family.BOUNDARY,
    advice = Advice.PRODUCE,
    register = Register.CASUAL,
    dictIpa = "dɪd ju",
    realizedIpa = "dɪdʒ ə",
    cue = "A final t, d, s or z melts into a following “you”.",
    contraction = null,
    startMs = 42_250,
    endMs = 42_700,
    span = ClipSpan(jobId, 42_250, 42_700, WordRef(jobId, segment, 0)),
  )

  private fun pitch(segment: Int, from: Long, to: Long, rising: Boolean) = PhrasePitch(
    segment = segment,
    timesMs = (from..to step 40).toList(),
    semitones = (from..to step 40).map { t ->
      val x = (t - from) / (to - from).toFloat()
      (if (rising) -3f + 8f * x else 3f - 6f * x) + kotlin.math.sin(x * 12f)
    },
  )

  fun content(
    jobId: JobId = job,
    segment: Int = 4,
    slip: SpotlightSlip? = slip(jobId, segment),
    title: String? = TITLE,
    measurablePitch: Boolean = true,
  ): ShortContent {
    val phrases = phrases(jobId, segment)
    val pitch = if (measurablePitch) {
      listOf(pitch(segment, 42_300, 46_700, rising = true), pitch(segment + 1, 47_250, 55_500, rising = false))
    } else {
      listOf(PhrasePitch(segment, emptyList(), emptyList()), PhrasePitch(segment + 1, emptyList(), emptyList()))
    }
    return ShortContent(
      id = ShortId(jobId, segment),
      clipTitle = title,
      ordinal = 4,
      ofTotal = 15,
      playStartMs = 42_000,
      playEndMs = 56_000,
      phrases = phrases,
      spotlight = slip,
      ticks = listOf((47_200 - 42_000) / 14_000f),
      pin = slip?.let { (it.startMs - 42_000) / 14_000f },
      trace = FeedLogic.traceOf(pitch, 42_000, 56_000),
      pitch = pitch,
    )
  }

  fun shortPage(
    queueIndex: Int = 0,
    jobId: JobId = job,
    segment: Int = 4,
    video: VideoAvailability = VideoAvailability.OnThisPhone,
    width: Int = 1280,
    height: Int = 720,
    setPosition: Int? = null,
    savedTag: Boolean = false,
    ordinal: Int = 4,
  ) = FeedPage.Short(
    queueIndex,
    FeedItem(info(ordinal = ordinal, segment = segment, jobId = jobId), media(jobId, video, width, height)),
    setPosition,
    savedTag,
  )

  private val occurrence = Occurrence(
    word = WordRef(job, 4, 4),
    shortFirstSegment = 4,
    wordText = "water",
    dictIpa = "ˈwɔtɚ",
    realizedIpa = "ˈwɔɾɚ",
    startMs = 44_800,
    endMs = 45_400,
    change = flapping,
    diffCost = 0.2,
    advice = Advice.PRODUCE,
    family = Family.TD,
    crossesBoundary = false,
    nextWordText = null,
    clipTitle = TITLE,
  )

  val question = Question(
    kind = QuestionKind.FORM,
    key = "form:water",
    prompt = "How was “water” really said?",
    clip = ClipSpan(job, 44_550, 45_650, WordRef(job, 4, 4)),
    context = ClipSpan(job, 42_250, 46_800, null),
    options = listOf(
      QuestionOption("a", "[ˈwɔɾɚ]", "ˈwɔɾɚ", null),
      QuestionOption("b", "[ˈwɔtɚ]", "ˈwɔtɚ", null),
    ),
    answer = "a",
    change = flapping,
    occurrence = occurrence,
    phrase = null,
  )

  val wrongAnswer = AnswerResult(correct = false, answer = "[ˈwɔɾɚ]", change = flapping, explanation = occurrence)

  /** A ready feed on one short of [page] (In order, 4 / 15). */
  fun ready(
    page: FeedPage.Short = shortPage(),
    content: ShortContent? = content(page.id.jobId, page.id.firstSegment),
    mode: ModeName = ModeName.InOrder(TITLE),
  ) = WatchUiState(
    loading = false,
    mode = mode,
    pages = listOf(page, FeedPage.End(EndKind.CLIP_END)),
    current = 0,
    contents = if (content != null) mapOf(page.id to content) else emptyMap(),
    playback = IdlePlayback.copy(firstFrameRendered = false),
    clipEnd = ClipEnd(TITLE, shorts = 15, kinds = 14, next = NextClip(audioJob, "At the station")),
  )

  /** Today's set: three shorts, two questions, the summary. */
  fun set(current: Int = 0): WatchUiState {
    val shorts = (0 until 3).map { i ->
      shortPage(queueIndex = i, segment = 4 + 2 * i, setPosition = i, savedTag = i == 1, ordinal = 4 + i)
    }
    val questions = listOf(
      FeedPage.QuestionPage(3, 1, 2, question),
      FeedPage.QuestionPage(4, 2, 2, question, choice = "b", result = wrongAnswer),
    )
    return WatchUiState(
      loading = false,
      mode = ModeName.Mix(today = true),
      pages = shorts + questions + FeedPage.End(EndKind.SET_SUMMARY),
      current = current,
      contents = shorts.associate { it.id to content(it.id.jobId, it.id.firstSegment) },
      summary = SetSummary(
        today = true,
        shorts = 8,
        questions = 5,
        right = 4,
        met = listOf(
          MetChange(flapping, "flapping", Family.TD, 3),
          MetChange(palatalization, "palatalization", Family.BOUNDARY, 2),
        ),
        noQuestions = false,
      ),
      shortSet = 3,
    )
  }

  val picker = PickerState(
    today = 3 to 8,
    savedCount = 2,
    families = listOf(
      PickerFamily(Family.TD, listOf(PickerChange(flapping, "flapping", 34), PickerChange(ChangeLabel("nt_reduction"), "nt → n", 0))),
      PickerFamily(Family.BOUNDARY, listOf(PickerChange(palatalization, "palatalization", 9), PickerChange(ChangeLabel("linking"), "linking", 21))),
    ),
    clips = listOf(PickerClip(job, TITLE, leftAt = 4), PickerClip(audioJob, "At the station", leftAt = null)),
  )
}
