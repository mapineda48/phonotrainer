package com.mapineda48.phonotrainer.feature.practice

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.SavedItem
import com.mapineda48.phonotrainer.core.domain.model.SavedKind
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.core.testing.TestClock
import com.mapineda48.phonotrainer.core.testing.fake.app.FakeSettingsRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.player.FakeSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.player.SpanPlay
import com.mapineda48.phonotrainer.core.testing.fake.study.FakeSavedRepository
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Saved's ViewModel (spec F-09 item 3, F-09-AC1, the Partial state) over the settable fakes of work-plan 4.6: each item
 * read against its clip's analysis, most recently saved first, when it was last watched, "Play" and unsave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedViewModelTest {
  @get:Rule
  val main = MainDispatcherRule()

  private val job = AnalysisFixtures.JOB
  private val gone = JobId("20261001-090000-0000")
  private val now = Instant.parse("2026-10-10T09:00:00Z")
  private val saved = FakeSavedRepository()
  private val content = FakeClipContentRepository().apply { data[job] = AnalysisFixtures.clipData(job) }
  private val player = FakeSpanPlayer()
  private val settings = FakeSettingsRepository(UserSettings(track = Track.MIX))

  private fun item(
    id: Long,
    kind: SavedKind,
    word: WordRef?,
    change: String?,
    savedAt: Instant,
    lastWatched: Instant? = null,
    available: Boolean = true,
    jobId: JobId = job,
  ) = SavedItem(
    id = id,
    kind = kind,
    word = word,
    short = ShortId(jobId, word?.segment ?: 0),
    change = change?.let(::ChangeLabel),
    savedAt = savedAt,
    lastWatchedAt = lastWatched,
    clipTitle = "Does that work",
    available = available,
  )

  private fun TestScope.viewModel(): SavedViewModel {
    val vm = SavedViewModel(saved, content, FakeReferenceRepository(), player, settings, TestClock(now, ZoneId.of("UTC")))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    return vm
  }

  @Test
  fun eachItemIsReadAgainstItsClipNewestFirst() = runTest {
    saved.items.value = listOf(
      item(1, SavedKind.WORD, WordRef(job, 0, 1), "t_deletion", savedAt = now.minus(Duration.ofDays(2))),
      item(2, SavedKind.SHORT, WordRef(job, 1, 0), null, savedAt = now.minus(Duration.ofHours(1)), lastWatched = now.minus(Duration.ofDays(3))),
    )
    val rows = viewModel().state.value.rows
    // F-09-AC1: the item saved last is listed first, with its word.
    assertThat(rows.map { it.id }).containsExactly(2L, 1L).inOrder()
    val short = rows[0]
    assertThat(short.wordText).isEqualTo("wanna")
    // No change kept with it: the word's own first change.
    assertThat(short.change).isEqualTo(ChangeRef(ChangeLabel("contraction_lex"), "lexical contraction", Family.LEXICAL))
    assertThat(short.lastWatched).isEqualTo(LastWatched.DaysAgo(3))
    val word = rows[1]
    assertThat(word.wordText).isEqualTo("that")
    assertThat(word.change?.name).isEqualTo("t/d deletion")
    assertThat(word.occurrence?.realizedIpa).isEqualTo("ðæ")
    assertThat(word.occurrence?.clipTitle).isEqualTo("Does that work")
    assertThat(word.span).isEqualTo(ClipSpan(job, 400, checkNotNull(word.occurrence).endMs, WordRef(job, 0, 1)))
    assertThat(word.lastWatched).isEqualTo(LastWatched.Never)
  }

  @Test
  fun aChangeThatRunsIntoTheNextWordPlaysBothWords() = runTest {
    saved.items.value = listOf(item(1, SavedKind.WORD, WordRef(job, 0, 0), "vowel_reduction", savedAt = now))
    val row = viewModel().state.value.rows.single()
    val clip = AnalysisFixtures.clipData(job)
    val next = checkNotNull(clip.word(WordRef(job, 0, 1)))
    assertThat(row.occurrence?.crossesBoundary).isTrue()
    assertThat(row.occurrence?.nextWordText).isEqualTo("that")
    assertThat(row.span?.endMs).isEqualTo((next.endS * 1000).toLong())
  }

  @Test
  fun anItemWhoseClipIsNotOnThisPhoneStaysListed() = runTest {
    saved.items.value = listOf(item(1, SavedKind.SHORT, WordRef(gone, 3, 2), "flapping", savedAt = now, available = false, jobId = gone))
    val row = viewModel().state.value.rows.single()
    assertThat(row.available).isFalse()
    assertThat(row.occurrence).isNull()
    assertThat(row.span).isNull()
    assertThat(row.change?.name).isEqualTo("flapping")
  }

  @Test
  fun playPlaysTheWordAndAgainStopsIt() = runTest {
    saved.items.value = listOf(item(1, SavedKind.WORD, WordRef(job, 0, 1), "t_deletion", savedAt = now))
    val vm = viewModel()
    val row = vm.state.value.rows.single()
    vm.play(row)
    assertThat(player.calls).containsExactly(SpanPlay(checkNotNull(row.span), 1f, Track.MIX, false))
    val playing = vm.state.value.rows.single()
    assertThat(playing.playing).isTrue()
    vm.play(playing)
    assertThat(player.stops).isEqualTo(1)
    assertThat(vm.state.value.rows.single().playing).isFalse()
  }

  @Test
  fun unsaveRemovesTheItem() = runTest {
    saved.items.value = listOf(
      item(1, SavedKind.WORD, WordRef(job, 0, 1), "t_deletion", savedAt = now),
      item(2, SavedKind.SHORT, WordRef(job, 1, 0), null, savedAt = now.minusSeconds(60)),
    )
    val vm = viewModel()
    vm.unsave(1)
    assertThat(vm.state.value.rows.map { it.id }).containsExactly(2L)
    vm.unsave(2)
    assertThat(vm.state.value.loading).isFalse()
    assertThat(vm.state.value.rows).isEmpty()
  }

  @Test
  fun lastWatchedReadsInLocalCalendarDays() {
    val zone = ZoneId.of("America/Bogota")
    val noon = Instant.parse("2026-10-10T17:00:00Z")
    assertThat(SavedLogic.lastWatched(null, noon, zone)).isEqualTo(LastWatched.Never)
    assertThat(SavedLogic.lastWatched(noon.minus(Duration.ofHours(6)), noon, zone)).isEqualTo(LastWatched.Today)
    assertThat(SavedLogic.lastWatched(noon.minus(Duration.ofHours(20)), noon, zone)).isEqualTo(LastWatched.Yesterday)
    assertThat(SavedLogic.lastWatched(noon.minus(Duration.ofDays(29)), noon, zone)).isEqualTo(LastWatched.DaysAgo(29))
    assertThat(SavedLogic.lastWatched(noon.minus(Duration.ofDays(40)), noon, zone))
      .isEqualTo(LastWatched.On(LocalDate.of(2026, 8, 31), sameYear = true))
    assertThat(SavedLogic.lastWatched(Instant.parse("2025-12-01T12:00:00Z"), noon, zone))
      .isEqualTo(LastWatched.On(LocalDate.of(2025, 12, 1), sameYear = false))
  }
}
