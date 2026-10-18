package com.mapineda48.phonotrainer.feature.home

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Home's words that depend on numbers and lists (F-03-AC10, F-08 States, design 9.16). */
@RunWith(RobolectricTestRunner::class)
class HomeCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

  @Test
  fun theSetsSentenceIsTheOneOfF03Ac10() {
    val summary = HomeRules.summary(HomeFixtures.acTenSet(), SampleReference.reference)
    assertThat(HomeCopy.todaySentence(summary, resources))
      .isEqualTo("Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; 2 saved shorts back; then 5 questions.")
  }

  @Test
  fun theSentenceListsOneTwoOrThreeChangesAndCountsTheRest() {
    fun sentence(names: List<String>, more: Int = 0, saved: Int = 0, questions: Int = 0, shorts: Int = 8) =
      HomeCopy.todaySentence(TodaySummary(shorts, names, more, saved, questions), resources)
    assertThat(sentence(listOf("flapping"), shorts = 1)).isEqualTo("Today's set: 1 short with flapping.")
    assertThat(sentence(listOf("flapping", "linking"))).isEqualTo("Today's set: 8 shorts with flapping and linking.")
    assertThat(sentence(listOf("flapping", "linking", "h-dropping")))
      .isEqualTo("Today's set: 8 shorts with flapping, linking and h-dropping.")
    assertThat(sentence(listOf("flapping", "linking", "h-dropping"), more = 1, saved = 1, questions = 1))
      .isEqualTo("Today's set: 8 shorts with flapping, linking, h-dropping and 1 more change; 1 saved short back; then 1 question.")
    assertThat(sentence(emptyList(), questions = 3)).isEqualTo("Today's set: 8 shorts; then 3 questions.")
  }

  @Test
  fun theCardsHeadlineAndCountsFollowItsPhase() {
    assertThat(HomeCopy.todayHeadline(HomeSamples.fresh, resources)).isEqualTo("Today · about 5 minutes")
    assertThat(HomeCopy.todayCounts(HomeSamples.fresh.summary, resources)).isEqualTo("8 shorts, 5 questions · 2 saved shorts back")
    assertThat(HomeCopy.todayHeadline(HomeSamples.midSet, resources)).isEqualTo("Today · 3 of 8 done")
    assertThat(HomeCopy.todayHeadline(HomeSamples.finished, resources)).isEqualTo("Done for today")
    assertThat(HomeCopy.met(HomeSamples.finished, resources))
      .isEqualTo("What you met: flapping ×3, linking ×2, h-dropping ×1, t/d deletion ×1, vowel reduction ×1")
    assertThat(HomeCopy.todayHeadline(HomeSamples.fresh.copy(minutes = 1), resources)).isEqualTo("Today · about 1 minute")
  }

  @Test
  fun aShortSetSaysSo() {
    assertThat(HomeCopy.shortSet(HomeSamples.shortSet.summary, resources)).isEqualTo("Only 4 shorts today: add a clip for more.")
    assertThat(HomeCopy.shortSet(HomeSamples.fresh.summary, resources)).isNull()
    assertThat(HomeCopy.todayCounts(HomeSamples.shortSet.summary, resources)).isEqualTo("4 shorts, 2 questions")
  }

  @Test
  fun theCardReadsAsOneSentenceWithTheSetsSentenceInIt() {
    assertThat(HomeCopy.todayCardSentence(HomeSamples.fresh, resources)).isEqualTo(
      "Today, about 5 minutes. Today's set: 8 shorts with flapping, linking, h-dropping and 2 more changes; " +
        "2 saved shorts back; then 5 questions.",
    )
    assertThat(HomeCopy.todayCardSentence(HomeSamples.shortSet, resources)).endsWith("Only 4 shorts today: add a clip for more.")
  }

  @Test
  fun theDatelineIsInEnglish() {
    assertThat(HomeCopy.dateline(Instant.parse("2026-10-04T10:30:00Z"), ZoneId.of("UTC"), resources)).isEqualTo("Sunday, 4 October · Home")
  }
}
