package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeShortRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The corpus fakes of `:core:testing-jvm` (work-plan 4.6), which feature modules test against, keep the behaviours the
 * real repositories have and these tests prove: leases counted per clip, typed clip-data errors, and the sequences of
 * In order and change modes.
 */
class CorpusFakesTest {
  @Test
  fun theFakeCountsLeasesPerClipLikeTheRealOne() {
    val content = FakeClipContentRepository()
    val feed = content.open(A)
    val lesson = content.open(A)

    feed.close()
    feed.close()
    assertThat(content.openLeases).containsExactly(A)
    assertThat(content.leaseCount(A)).isEqualTo(1)

    lesson.close()
    assertThat(content.openLeases).isEmpty()
  }

  @Test
  fun theFakeThrowsTypedClipDataErrors() = runTest {
    val content = FakeClipContentRepository()
    content.failures[A] = AppError.Content.Corrupt

    val corrupt = runCatching { content.clipData(A) }.exceptionOrNull() as ClipDataException
    val missing = runCatching { content.clipData(JobId("b")) }.exceptionOrNull() as ClipDataException

    assertThat(corrupt.error).isEqualTo(AppError.Content.Corrupt)
    assertThat(missing.error).isEqualTo(AppError.Content.Missing)
  }

  @Test
  fun theFakesSequencesFollowItsShorts() = runTest {
    val b = JobId("b")
    val shorts = FakeShortRepository()
    shorts.shortsById.value = mapOf(
      A to listOf(short(A, 0, FLAPPING), short(A, 2, null), short(A, 4, LINKING)),
      b to listOf(short(b, 0, FLAPPING)),
    )

    assertThat(shorts.sequence(FeedMode.InOrder(A)).items.map { it.id.firstSegment }).containsExactly(0, 2, 4).inOrder()
    assertThat(shorts.sequence(FeedMode.Change(FLAPPING)).items.map { it.id })
      .containsExactly(ShortId(A, 0), ShortId(b, 0)).inOrder()
    assertThat(shorts.sequence(FeedMode.Change(FLAPPING, onlyClip = b)).items.map { it.id }).containsExactly(ShortId(b, 0))
    assertThat(shorts.sequence(FeedMode.Saved).items).isEmpty()
    assertThat(shorts.shortContaining(WordRef(A, 3, 0))?.id).isEqualTo(ShortId(A, 2))
  }

  private fun short(id: JobId, first: Int, change: ChangeLabel?) = ShortInfo(
    id = ShortId(id, first), ordinal = first / 2 + 1, ofTotal = 3, lastSegment = first + 1, playStartMs = 0,
    playEndMs = 1_000, spotlight = change?.let { Spotlight(WordRef(id, first, 0), it, Advice.PRODUCE, false) },
    markedWords = if (change == null) 0 else 1,
  )

  private companion object {
    val A = JobId("a")
    val FLAPPING = ChangeLabel("flapping")
    val LINKING = ChangeLabel("linking")
  }
}
