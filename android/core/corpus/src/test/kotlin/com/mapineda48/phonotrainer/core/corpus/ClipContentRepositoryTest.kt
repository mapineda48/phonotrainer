package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.CompareRows
import com.mapineda48.phonotrainer.core.corpus.TestClips.phrase
import com.mapineda48.phonotrainer.core.corpus.TestClips.plain
import com.mapineda48.phonotrainer.core.corpus.TestClips.word
import com.mapineda48.phonotrainer.core.database.library.entity.ArtifactEntity
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.CompareStatus
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A clip's content (architecture 3.4, 9.5, 11.1; spec F-10): the parsed analysis or a typed error, the lesson's
 * Compare rows exactly as `:core:analysis` computes them (F-10-AC1, F-10-AC2), and the local media from the verified
 * files only, never a path outside `filesDir/clips`.
 */
@RunWith(RobolectricTestRunner::class)
class ClipContentRepositoryTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var h: CorpusHarness

  @Before
  fun setUp() {
    h = CorpusHarness(tmp.newFolder("files"))
  }

  @After
  fun tearDown() = h.close()

  @Test
  fun compareRowsAreTheAnalysisCoresRowsForTheWord() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(LESSON))
    val water = WordRef(id, 0, 1)
    val last = WordRef(id, 0, 2)

    val waterRows = h.content.compareRows(water)
    val lastRows = h.content.compareRows(last)

    val clip = h.content.clipData(id)
    assertThat(waterRows).isEqualTo(CompareRows.of(clip, water))
    assertThat(lastRows).isEqualTo(CompareRows.of(clip, last))
    // F-10-AC1: the expected [t] and the heard [ɾ] are paired as changed.
    assertThat(waterRows.single { it.status == CompareStatus.CHANGED }.let { it.dictionary to it.said }).isEqualTo("t" to "ɾ")
    // F-10-AC2: the dropped sound is not heard.
    assertThat(lastRows.single { it.status == CompareStatus.NOT_HEARD }.dictionary).isEqualTo("t")
    assertThat(h.content.compareRows(WordRef(id, 5, 0))).isEmpty()
  }

  @Test
  fun compareRowsOfAClipWhoseAnalysisCannotBeReadAreEmpty() = runBlocking<Unit> {
    val missing = h.addClip("missing", analysis = null)
    val corrupt = h.addClip("corrupt", "[]".toByteArray())

    assertThat(h.content.compareRows(WordRef(missing, 0, 0))).isEmpty()
    assertThat(h.content.compareRows(WordRef(corrupt, 0, 0))).isEmpty()
  }

  @Test
  fun clipDataIsMissingWithoutAFileAndCorruptWhenItDoesNotParse() = runBlocking<Unit> {
    val missing = h.addClip("missing", analysis = null)
    val corrupt = h.addClip("corrupt", "{\"segments\": 3}".toByteArray())

    assertThat(errorOf { h.content.clipData(missing) }).isEqualTo(AppError.Content.Missing)
    assertThat(errorOf { h.content.clipData(corrupt) }).isEqualTo(AppError.Content.Corrupt)
    assertThat(errorOf { h.content.clipData(JobId("unknown")) }).isEqualTo(AppError.Content.Missing)
  }

  @Test
  fun theFeedTheLessonAndTheClipPageShareOneParse() = runBlocking<Unit> {
    val ids = listOf("a", "b", "c", "d").map { h.addClip(it, CorpusHarness.bytes(LESSON)) }

    repeat(3) { h.content.clipData(ids[0]) }
    assertThat(h.loader.parses).isEqualTo(1)

    ids.forEach { h.content.clipData(it) }
    assertThat(h.loader.cachedIds()).containsExactly(ids[1], ids[2], ids[3]).inOrder()
    assertThat(h.loader.parses).isEqualTo(4)
  }

  @Test
  fun mediaNamesTheVerifiedFilesAndTheListingsVideoFacts() = runBlocking<Unit> {
    val id = h.addClip(
      "a",
      CorpusHarness.bytes(LESSON),
      audio = mapOf(CorpusHarness.AUDIO_MIX to CorpusHarness.READY, CorpusHarness.AUDIO_DIALOGUE to CorpusHarness.READY),
    ) { it.copy(videoCodec = "vp9", videoWidth = 1280, videoHeight = 720, videoRotation = 90, videoOffsetMs = 120) }
    h.putArtifact("a", "original", MediaResolver.VIDEO, CorpusHarness.READY, "video".toByteArray(), "webm")

    val media = h.content.media(id)!!

    assertThat(media.jobId).isEqualTo(id)
    assertThat(media.audioMix.name).startsWith("audio_mix.")
    assertThat(media.audioDialogue!!.name).startsWith("audio_dialogue.")
    assertThat(media.videoFile!!.readText()).isEqualTo("video")
    assertThat(listOf(media.videoCodec, media.videoWidth, media.videoHeight)).containsExactly("vp9", 1280, 720).inOrder()
    assertThat(media.videoRotation).isEqualTo(90)
    assertThat(media.videoOffsetMs).isEqualTo(120)
    assertThat(media.poster).isNull()
    assertThat(media.videoAvailability).isEqualTo(VideoAvailability.OnThisPhone)
  }

  @Test
  fun withOnlyTheDialogueOnThePhoneItPlaysTheDialogue() = runBlocking<Unit> {
    val id = h.addClip(
      "a",
      CorpusHarness.bytes(LESSON),
      audio = mapOf(CorpusHarness.AUDIO_MIX to "DOWNLOADING", CorpusHarness.AUDIO_DIALOGUE to CorpusHarness.READY),
    )

    val media = h.content.media(id)!!

    assertThat(media.audioMix).isEqualTo(media.audioDialogue)
    assertThat(media.audioMix.name).startsWith("audio_dialogue.")
  }

  @Test
  fun noAudioOnThePhoneMeansNoMedia() = runBlocking<Unit> {
    val id = h.addClip("a", CorpusHarness.bytes(LESSON), audio = mapOf(CorpusHarness.AUDIO_MIX to "QUEUED"))

    assertThat(h.content.media(id)).isNull()
    assertThat(h.content.media(JobId("unknown"))).isNull()
  }

  @Test
  fun aFileOutsideTheClipsFolderIsNeverOpened() = runBlocking<Unit> {
    File(h.files.filesDir, "secret.wav").writeText("not a clip")
    val id = h.addClip(
      "a",
      CorpusHarness.bytes(LESSON),
      audio = emptyMap(),
      extra = listOf(
        ArtifactEntity(
          jobId = "a", name = "audio_mix", role = MediaResolver.AUDIO_MIX, remoteStatus = CorpusHarness.READY,
          localState = CorpusHarness.READY, localFile = "clips/a/../../secret.wav", localSha256 = "00", updatedAt = 1,
        ),
      ),
    )

    assertThat(h.content.media(id)).isNull()
    assertThat(h.files.resolve("clips/a/../../secret.wav")).isNull()
    assertThat(h.files.resolve("")).isNull()
  }

  private suspend fun errorOf(block: suspend () -> Unit): AppError.Content? = try {
    block()
    null
  } catch (failed: ClipDataException) {
    failed.error
  }

  private companion object {
    /** "the water last": flapping on "water" (t heard as ɾ), t/d deletion on "last" (its t not heard). */
    val LESSON = TestClips.analysis(
      phrase(
        0.0, 6.0,
        plain("the", 0.1),
        word("water", 0.5, "flapping"),
        word("last", 1.5, "t_deletion", canonical = "l æ s t", realized = "l æ s"),
      ),
    )
  }
}
