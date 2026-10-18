package com.mapineda48.phonotrainer.feature.home

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeAccuracy
import com.mapineda48.phonotrainer.core.domain.model.ChangeCount
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import java.time.Instant
import org.junit.Test

/** The pure rules of Home (spec F-03, F-08, D-45, D-48). */
class HomeRulesTest {
  private val reference = SampleReference.reference

  @Test
  fun thePopulatedHomeShowsEveryBlockInOrder() {
    // F-25 list item 3: every block of F-03 in that order (nine with the masthead in v1-core, ten with Insights).
    assertThat(HomeRules.blocks(HomeSamples.populated)).containsExactly(
      HomeBlock.STATUS,
      HomeBlock.TODAY,
      HomeBlock.KEEP_WATCHING,
      HomeBlock.ADD,
      HomeBlock.NEW,
      HomeBlock.SHELF,
      HomeBlock.CHANGES,
      HomeBlock.PRACTICE,
    ).inOrder()
    assertThat(HomeRules.blocks(HomeSamples.populated.copy(insights = "7 in 10")).last()).isEqualTo(HomeBlock.INSIGHTS)
  }

  @Test
  fun theStatesAddTheirBlocksInPlace() {
    assertThat(HomeRules.blocks(HomeSamples.firstRun)).containsExactly(
      HomeBlock.STATUS,
      HomeBlock.TEACHING,
      HomeBlock.TODAY,
      HomeBlock.ADD,
      HomeBlock.SHELF,
      HomeBlock.CHANGES,
      HomeBlock.PRACTICE,
    ).inOrder()
    assertThat(HomeRules.blocks(HomeSamples.empty)).contains(HomeBlock.EMPTY)
    assertThat(HomeRules.blocks(HomeSamples.empty)).doesNotContain(HomeBlock.TEACHING)
    assertThat(HomeRules.blocks(HomeSamples.error)).contains(HomeBlock.ERROR)
    assertThat(HomeRules.blocks(HomeSamples.error)).doesNotContain(HomeBlock.SHELF)
    // The status card is always there (F-03 item 2), whatever the catalogue.
    listOf(HomeSamples.populated, HomeSamples.firstRun, HomeSamples.empty, HomeSamples.error, HomeSamples.loading, HomeSamples.unreachable)
      .forEach { assertThat(HomeRules.blocks(it).first()).isEqualTo(HomeBlock.STATUS) }
  }

  @Test
  fun theSetsSentenceNamesEachSpotlightChangeOnceInSetOrderAtMostThree() {
    // F-03-AC10.
    val summary = HomeRules.summary(HomeFixtures.acTenSet(), reference)
    assertThat(summary).isEqualTo(
      TodaySummary(shorts = 8, names = listOf("flapping", "linking", "h-dropping"), moreChanges = 2, saved = 2, questions = 5),
    )
  }

  @Test
  fun droppedShortsLeaveTheSetAndItsCount() {
    val set = HomeFixtures.acTenSet().let { set ->
      set.copy(items = set.items.mapIndexed { index, item -> if (index == 2) HomeFixtures.shortItem(2, "h_dropping", saved = true, dropped = true) else item })
    }
    val summary = HomeRules.summary(set, reference)
    assertThat(summary.shorts).isEqualTo(7)
    assertThat(summary.saved).isEqualTo(1)
    assertThat(summary.names).containsExactly("flapping", "linking", "t/d deletion").inOrder()
    assertThat(summary.moreChanges).isEqualTo(1)
    val card = HomeRules.todayCard(set, HomeCatalogue.Ready(1, 1), preparing = false, reference) as TodayCard.Ready
    assertThat(card.stations.filterIsInstance<Station.Short>()).hasSize(7)
  }

  @Test
  fun theCardFollowsTheSet() {
    val ready = HomeCatalogue.Ready(total = 3, onPhone = 2)
    val fresh = HomeRules.todayCard(HomeFixtures.acTenSet(), ready, preparing = false, reference) as TodayCard.Ready
    assertThat(fresh.phase).isEqualTo(TodayPhase.FRESH)
    assertThat(fresh.minutes).isEqualTo(5)
    assertThat(fresh.stations).hasSize(13)
    assertThat(fresh.stations.first()).isEqualTo(Station.Short(Family.TD, saved = true, done = false))
    assertThat(fresh.stations.last()).isEqualTo(Station.Question(answered = false))
    assertThat(fresh.met.first()).isEqualTo("flapping" to 3)
    assertThat(fresh.met.map { it.first }).containsExactly("flapping", "linking", "h-dropping", "t/d deletion", "vowel reduction").inOrder()

    val mid = HomeRules.todayCard(HomeFixtures.acTenSet(done = 3), ready, preparing = false, reference) as TodayCard.Ready
    assertThat(mid.phase).isEqualTo(TodayPhase.IN_PROGRESS)
    assertThat(mid.done).isEqualTo(3)

    val done = HomeRules.todayCard(HomeFixtures.acTenSet(completedAt = Instant.EPOCH, done = 8), ready, preparing = false, reference)
    assertThat((done as TodayCard.Ready).phase).isEqualTo(TodayPhase.DONE)
  }

  @Test
  fun withoutASetTheCardSaysWhatIsTrue() {
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Loading, preparing = false, reference)).isEqualTo(TodayCard.Preparing)
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Ready(0, 0), preparing = false, reference)).isEqualTo(TodayCard.NoClips)
    // Clips on the computer but none on this phone yet: the set still starts with the first clip here.
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Ready(4, 0), preparing = false, reference)).isEqualTo(TodayCard.NoClips)
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Failed, preparing = false, reference)).isEqualTo(TodayCard.NoClips)
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Ready(4, 2), preparing = true, reference)).isEqualTo(TodayCard.Preparing)
    assertThat(HomeRules.todayCard(null, HomeCatalogue.Ready(4, 2), preparing = false, reference)).isEqualTo(TodayCard.NothingNew)
  }

  @Test
  fun theEstimateCountsTheShortsAndAboutTwentySecondsPerQuestion() {
    val set = HomeFixtures.acTenSet()
    // 8 × 20 s + 5 × 20 s = 260 s → about 5 minutes.
    assertThat(HomeRules.minutes(set)).isEqualTo(5)
    assertThat(HomeRules.minutes(set.copy(items = listOf(HomeFixtures.shortItem(0, "linking"))))).isEqualTo(1)
  }

  @Test
  fun watchByChangeGroupsTheSixteenChangesByFamilyWithTheirShortCounts() {
    // D-48: the chip's number is ChangeCount.shorts (what the change mode plays); an absent key is 0.
    val counts = mapOf(ChangeLabel("flapping") to ChangeCount(shorts = 34, occurrences = 90), ChangeLabel("linking") to ChangeCount(2, 3))
    val groups = HomeRules.changeGroups(reference, counts)
    assertThat(groups.map { it.family })
      .containsExactly(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY, Family.LEXICAL, Family.OTHER).inOrder()
    assertThat(groups.sumOf { it.chips.size }).isEqualTo(16)
    val td = groups.single { it.family == Family.TD }
    assertThat(td.chips.map { it.label.value }).containsExactly("flapping", "t_deletion", "t_unreleased", "glottalization", "nt_reduction").inOrder()
    assertThat(td.chips.first()).isEqualTo(ChangeChipState(ChangeLabel("flapping"), "Flapping", Family.TD, 34))
    assertThat(td.chips[1].shorts).isEqualTo(0)
    assertThat(groups.single { it.family == Family.LEXICAL }.chips.single().label).isEqualTo(ChangeLabel("contraction_lex"))
    assertThat(groups.single { it.family == Family.OTHER }.chips.single().label).isEqualTo(ChangeLabel("word_elision"))
  }

  @Test
  fun worthReviewingIsTheWeakestAnsweredChange() {
    val accuracy = listOf(
      ChangeAccuracy(ChangeLabel("flapping"), seen = 10, correct = 9),
      ChangeAccuracy(ChangeLabel("t_deletion"), seen = 9, correct = 4),
      ChangeAccuracy(ChangeLabel("linking"), seen = 0, correct = 0),
      ChangeAccuracy(ChangeLabel("h_dropping"), seen = 18, correct = 8),
    )
    assertThat(HomeRules.weakSpot(accuracy, reference)).isEqualTo(WeakSpot("h-dropping", 8, 18))
    assertThat(HomeRules.weakSpot(accuracy.take(2), reference)).isEqualTo(WeakSpot("t/d deletion", 4, 9))
    assertThat(HomeRules.weakSpot(emptyList(), reference)).isNull()
    assertThat(HomeRules.weakSpot(listOf(ChangeAccuracy(ChangeLabel("linking"), 0, 0)), reference)).isNull()
  }

  @Test
  fun theShelfIsTheClipsMostRecentlyStudiedOrAdded() {
    val now = Instant.parse("2026-10-04T10:30:00Z")
    val clips = (1..14).map { HomeSamples.clip("job-$it", "Clip $it", addedAt = now.minusSeconds(it * 3_600L), shortsSeen = if (it == 14) 3 else 0) }
    val candidates = HomeRules.shelfCandidates(clips)
    // The ten newest, plus the oldest clip because it was studied.
    assertThat(candidates.map { it.id.value }).containsExactlyElementsIn((1..10).map { "job-$it" } + "job-14")
    val watched = mapOf("job-14" to now.minusSeconds(60))
    val shelf = HomeRules.shelf(candidates.map { it to HomeRules.lastActivity(it, watched[it.id.value]?.let { at -> at }) })
    assertThat(shelf).hasSize(HomeRules.SHELF_SIZE)
    assertThat(shelf.first().id).isEqualTo(JobId("job-14"))
    assertThat(shelf.map { it.id.value }).doesNotContain("job-10")
  }

  @Test
  fun onlyCompleteStudyPacksCountAsOnThisPhone() {
    val clips = listOf(
      HomeSamples.clip("a", "A"),
      HomeSamples.clip("b", "B", availability = ClipAvailability.Bringing(40)),
      HomeSamples.clip("c", "C", availability = ClipAvailability.NotOnThisPhone),
      HomeSamples.clip("d", "D", availability = ClipAvailability.GettingReadyOnComputer),
    )
    assertThat(HomeRules.onPhone(clips)).isEqualTo(1)
    assertThat(HomeRules.wanted(clips)).isEqualTo(2)
  }

  @Test
  fun theAttentionRequestIsTheOldestFailureOfThisPhone() {
    fun request(id: String, state: RequestState, at: Long, rerun: Boolean = false) = Request(
      id = RequestId(id), kind = RequestKind.LINK, state = state, title = id, site = null, link = null, fileName = null, fileBytes = null,
      createdAt = Instant.ofEpochSecond(at), progress = null, lastError = null, jobId = null, actions = setOf(RequestAction.TRY_AGAIN),
      rerunPending = rerun,
    )
    val requests = listOf(
      request("new-failure", RequestState.NEEDS_ATTENTION, 30),
      request("old-failure", RequestState.COPY_FAILED, 10),
      request("rerun", RequestState.NEEDS_ATTENTION, 1, rerun = true),
      request("analyzing", RequestState.ANALYZING, 5),
    )
    assertThat(HomeRules.attention(requests)?.id).isEqualTo(RequestId("old-failure"))
    assertThat(HomeRules.analyzing(requests)?.id).isEqualTo(RequestId("analyzing"))
    assertThat(HomeRules.analyzing(requests + request("second", RequestState.ANALYZING, 6))).isNull()
  }

  @Test
  fun inlineRequestActionsAreDoneHereOrWhereTheyCanBeConfirmed() {
    assertThat(HomeRules.route(RequestAction.TRY_AGAIN)).isEqualTo(RequestRoute.PERFORM)
    assertThat(HomeRules.route(RequestAction.TRY_AUDIO_ONLY)).isEqualTo(RequestRoute.PERFORM)
    assertThat(HomeRules.route(RequestAction.REMOVE)).isEqualTo(RequestRoute.DETAIL)
    assertThat(HomeRules.route(RequestAction.SHOW_DETAILS)).isEqualTo(RequestRoute.DETAIL)
    assertThat(HomeRules.route(RequestAction.CONNECT_AGAIN)).isEqualTo(RequestRoute.CONNECT_AGAIN)
  }
}
