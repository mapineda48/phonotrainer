package com.mapineda48.phonotrainer.core.common.shadowing

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase.LISTEN
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase.YOUR_TURN
import org.junit.Test

/** The web client's "shadowing plan" case (`features/analysis/lib/lib.test.ts`) and spec F-12-AC1, F-12-AC2. */
class ShadowingPlanTest {
  private fun List<ShadowingStep>.shape() = map { "${if (it.phase == LISTEN) "listen" else "repeat"}@${it.rate}" }

  @Test
  fun listensSlowlyThreeTimesThenOnceAtFullSpeedEachFollowedByAGap() {
    // web: shadowingPlan({ start: 1, end: 2 }, 0.5)
    val plan = ShadowingPlan.plan(startMs = 1_000, endMs = 2_000, slowRate = 0.5f)
    assertThat(plan).hasSize(8)
    assertThat(plan.shape()).containsExactly(
      "listen@0.5", "repeat@0.5", "listen@0.5", "repeat@0.5", "listen@0.5", "repeat@0.5", "listen@1.0", "repeat@1.0",
    ).inOrder()
    assertThat(plan[0].ms).isEqualTo(2_000) // 1 s at half speed
    assertThat(plan[1].ms).isEqualTo(2_000 + ShadowingPlan.REPEAT_MARGIN_MS)
    assertThat(plan[6].ms).isEqualTo(1_000)
    // web describePhase(plan[1]) == "Round 1 of 4: your turn — say it now"
    assertThat(plan[1].phase).isEqualTo(YOUR_TURN)
    assertThat(plan[1].round to plan[1].rounds).isEqualTo(1 to 4)
    // web describePhase(plan[6]) == "Round 4 of 4: listen at full speed"
    assertThat(plan[6].round to plan[6].rounds).isEqualTo(4 to 4)
    assertThat(plan[6].fullSpeed).isTrue()
    assertThat(plan[0].fullSpeed).isFalse()
  }

  @Test
  fun aTwoSecondPhraseAtThreeQuartersSpeed() {
    // F-12-AC1: listens of about 2.67 s (rounds 1 to 3) and 2 s (round 4), each followed by a silence 400 ms longer
    val span = ClipSpan(JobId("job"), startMs = 4_000, endMs = 6_000, word = null)
    val plan = ShadowingPlan.plan(span, slowRate = 0.75f)
    assertThat(plan.map { it.phase }).containsExactly(
      LISTEN, YOUR_TURN, LISTEN, YOUR_TURN, LISTEN, YOUR_TURN, LISTEN, YOUR_TURN,
    ).inOrder()
    assertThat(plan.map { it.ms }).containsExactly(2_667L, 3_067L, 2_667L, 3_067L, 2_667L, 3_067L, 2_000L, 2_400L)
      .inOrder()
    assertThat(plan.map { it.round }).containsExactly(1, 1, 2, 2, 3, 3, 4, 4).inOrder()
    assertThat(ShadowingPlan.totalMs(plan)).isEqualTo(3 * (2_667L + 3_067L) + 2_000L + 2_400L)
  }

  @Test
  fun aSlowSpeedOfOneHasThreeRounds() {
    // F-12-AC2 (the function only: the screen offers 0.5x and 0.75x)
    val plan = ShadowingPlan.plan(startMs = 0, endMs = 1_500, slowRate = 1f)
    assertThat(plan).hasSize(6)
    assertThat(plan.all { it.rounds == 3 && it.rate == 1f }).isTrue()
    assertThat(plan.map { it.ms }).containsExactly(1_500L, 1_900L, 1_500L, 1_900L, 1_500L, 1_900L).inOrder()
  }

  @Test
  fun aVeryShortOrInvertedSpanCountsAsTwoHundredMilliseconds() {
    assertThat(ShadowingPlan.plan(startMs = 500, endMs = 520, slowRate = 0.5f).first().ms).isEqualTo(400)
    assertThat(ShadowingPlan.plan(startMs = 500, endMs = 100, slowRate = 1f).first().ms).isEqualTo(200)
  }

  @Test
  fun otherRoundCounts() {
    assertThat(ShadowingPlan.plan(0, 1_000, 0.5f, slowRounds = 1).shape())
      .containsExactly("listen@0.5", "repeat@0.5", "listen@1.0", "repeat@1.0").inOrder()
    assertThat(ShadowingPlan.plan(0, 1_000, 0.5f, slowRounds = 0).shape())
      .containsExactly("listen@1.0", "repeat@1.0").inOrder()
  }

  @Test(expected = IllegalArgumentException::class)
  fun aZeroSpeedIsRefused() {
    ShadowingPlan.plan(0, 1_000, 0f)
  }
}
