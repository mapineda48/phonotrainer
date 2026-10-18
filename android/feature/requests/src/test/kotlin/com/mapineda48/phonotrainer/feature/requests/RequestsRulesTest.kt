package com.mapineda48.phonotrainer.feature.requests

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.component.StepState
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.RequestTimeline
import com.mapineda48.phonotrainer.core.domain.model.remote.RemoteStatus
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples.now
import java.time.Duration
import org.junit.Test

/** The rules of Requests and of the request detail (spec F-20 items 1, 2 and 5). */
class RequestsRulesTest {
  @Test
  fun everyStateSitsInItsGroupInTheSpecsOrder() {
    val groups = RequestsRules.groups(RequestRowSamples.all.map { it.second }, now)

    assertThat(groups.map { it.kind }).containsExactly(
      RequestGroupKind.ATTENTION,
      RequestGroupKind.PREPARING,
      RequestGroupKind.WAITING,
      RequestGroupKind.SENDING,
      RequestGroupKind.ON_COMPUTER,
      RequestGroupKind.GETTING,
      RequestGroupKind.DONE,
    ).inOrder()
    val byGroup = groups.associate { group -> group.kind to group.requests.map { it.id.value } }
    assertThat(byGroup.getValue(RequestGroupKind.ATTENTION)).containsExactly("forbidden", "too-long", "copy-no-space", "cancelled")
    assertThat(byGroup.getValue(RequestGroupKind.WAITING)).containsExactly("waiting", "held", "large")
    // The automatic re-run reads "Starting again on your computer" among the requests on the computer.
    assertThat(byGroup.getValue(RequestGroupKind.ON_COMPUTER)).containsExactly("in-line", "analyzing", "cancel-waiting", "rerun")
    assertThat(byGroup.getValue(RequestGroupKind.GETTING)).containsExactly("getting")
    assertThat(byGroup.getValue(RequestGroupKind.DONE)).containsExactly("on-phone")
  }

  @Test
  fun doneRecentlyKeepsSevenDaysAndTheOrderIsStable() {
    val fresh = RequestRowSamples.onPhone.copy(timeline = RequestTimeline(onPhoneAt = now.minus(Duration.ofDays(7))))
    val old = RequestRowSamples.onPhone.copy(timeline = RequestTimeline(onPhoneAt = now.minus(Duration.ofDays(8))))
    assertThat(RequestsRules.groupOf(fresh, now)).isEqualTo(RequestGroupKind.DONE)
    assertThat(RequestsRules.groupOf(old, now)).isNull()

    val a = RequestRowSamples.request("b", RequestState.WAITING, createdAt = now)
    val b = RequestRowSamples.request("a", RequestState.WAITING, createdAt = now)
    val c = RequestRowSamples.request("c", RequestState.WAITING, createdAt = now.minusSeconds(1))
    repeat(5) {
      val order = RequestsRules.groups(listOf(a, b, c).shuffled(), now).single().requests.map { it.id.value }
      assertThat(order).containsExactly("c", "a", "b").inOrder()
    }
  }

  @Test
  fun theBrowsersJobsShowActiveAndFailedOnesNotDoneOrHiddenOnes() {
    val running = RequestsSamples.browserRunning
    val failed = RequestsSamples.browserFailed
    val queued = running.copy(id = JobId("q"), status = RemoteStatus.QUEUED, createdAt = now)
    val done = running.copy(id = JobId("d"), status = RemoteStatus.DONE)
    val hidden = failed.copy(id = JobId("h"), hidden = true)
    val cancelled = failed.copy(id = JobId("c"), status = RemoteStatus.CANCELLED)

    val shown = RequestsRules.browser(listOf(queued, done, hidden, failed, running, cancelled))

    assertThat(shown.map { it.id.value }).containsExactly(failed.id.value, running.id.value, "c", "q").inOrder()
    assertThat(shown.filter(RequestsRules::failed).map { it.id.value }).containsExactly(failed.id.value, "c")
  }

  @Test
  fun cancelAndRemoveAskFirstAndTheUisOwnActionsAreNotTheQueues() {
    val expected = mapOf(
      RequestAction.CANCEL to ActionRoute.CONFIRM_CANCEL,
      RequestAction.REMOVE to ActionRoute.CONFIRM_REMOVE,
      RequestAction.OPEN_LINK to ActionRoute.OPEN_LINK,
      RequestAction.SHOW_DETAILS to ActionRoute.DETAILS,
      RequestAction.CONNECT_AGAIN to ActionRoute.CONNECT_AGAIN,
      RequestAction.WHY to ActionRoute.WHY,
      RequestAction.CANCEL_PREPARING to ActionRoute.PERFORM,
      RequestAction.SEND_NOW to ActionRoute.PERFORM,
      RequestAction.STOP_SENDING to ActionRoute.PERFORM,
      RequestAction.UNDO_CANCEL to ActionRoute.PERFORM,
      RequestAction.TRY_AGAIN to ActionRoute.PERFORM,
      RequestAction.TRY_AUDIO_ONLY to ActionRoute.PERFORM,
    )
    RequestAction.entries.forEach { assertWithMessage("$it").that(RequestsRules.route(it)).isEqualTo(expected.getValue(it)) }
  }

  @Test
  fun onlyAnHttpLinkIsHandedToTheSystem() {
    assertThat(RequestsRules.openable(" https://youtu.be/abc ")).isEqualTo("https://youtu.be/abc")
    assertThat(RequestsRules.openable("http://example.org/a")).isEqualTo("http://example.org/a")
    assertThat(RequestsRules.openable("intent://scan/#Intent;scheme=zxing;end")).isNull()
    assertThat(RequestsRules.openable("javascript:alert(1)")).isNull()
    assertThat(RequestsRules.openable("https://a.example/x y")).isNull()
    assertThat(RequestsRules.openable(null)).isNull()
  }

  @Test
  fun theRemoveQuestionSaysWhatGoesWithIt() {
    assertThat(RequestsRules.removeBody(RequestRowSamples.waitingHeld)).isEqualTo(R.string.requests_remove_body_link)
    assertThat(RequestsRules.removeBody(RequestRowSamples.waiting)).isEqualTo(R.string.requests_remove_body_file)
    assertThat(RequestsRules.removeBody(RequestRowSamples.copyNoSpace)).isEqualTo(R.string.requests_remove_body_local)
    assertThat(RequestsRules.removeBody(RequestRowSamples.downloadForbidden)).isEqualTo(R.string.requests_remove_body_job)
  }

  @Test
  fun onlyAStageChangeIsAnnouncedNeverTheFirstStageSeenOrAPercent() {
    val watch = StageWatch()
    fun analyzing(stage: Stage?, percent: Int, segment: Int? = null) = RequestRowSamples.analyzing.copy(
      state = if (stage == null) RequestState.IN_LINE else RequestState.ANALYZING,
      progress = RequestProgress(stage, percent, null, null, null, null, segment = segment, segments = 10),
    )

    assertThat(watch.changes(listOf(analyzing(Stage.EXTRACT, 10)))).isEmpty()
    assertThat(watch.changes(listOf(analyzing(Stage.EXTRACT, 20)))).isEmpty()
    assertThat(watch.changes(listOf(analyzing(Stage.TRANSCRIBE, 40)))).containsExactly(Stage.TRANSCRIBE)
    assertThat(watch.changes(listOf(analyzing(Stage.PHONES, 61, segment = 3)))).containsExactly(Stage.PHONES)
    assertThat(watch.changes(listOf(analyzing(Stage.PHONES, 64, segment = 4)))).isEmpty()
    assertThat(watch.changes(emptyList())).isEmpty()
    // Back after it left the list: seen for the first time again.
    assertThat(watch.changes(listOf(analyzing(Stage.SAVE, 90)))).isEmpty()
  }

  @Test
  fun theTimelineMarksWhatIsDoneWhatIsHappeningAndWhatIsNext() {
    fun states(rows: List<MilestoneRow>) = rows.map { it.state }

    assertThat(states(RequestDetailRules.timeline(RequestRowSamples.analyzing))).containsExactly(
      StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.NEXT, StepState.NEXT,
    ).inOrder()
    assertThat(states(RequestDetailRules.timeline(RequestRowSamples.waiting))).containsExactly(
      StepState.DONE, StepState.NEXT, StepState.NEXT, StepState.NEXT, StepState.NEXT, StepState.NEXT,
    ).inOrder()
    assertThat(states(RequestDetailRules.timeline(RequestRowSamples.preparing)).first()).isEqualTo(StepState.CURRENT)
    assertThat(states(RequestDetailRules.timeline(RequestRowSamples.getting))).containsExactly(
      StepState.DONE, StepState.DONE, StepState.DONE, StepState.DONE, StepState.DONE, StepState.CURRENT,
    ).inOrder()
    // A failure keeps what it reached, nothing current.
    assertThat(states(RequestDetailRules.timeline(RequestRowSamples.downloadForbidden))).containsExactly(
      StepState.DONE, StepState.DONE, StepState.DONE, StepState.NEXT, StepState.NEXT, StepState.NEXT,
    ).inOrder()
    assertThat(RequestDetailRules.timeline(RequestRowSamples.analyzing)[1].at).isEqualTo(RequestRowSamples.analyzing.timeline.sentAt)
  }

  @Test
  fun theStagesShowOnceTheComputerHasItAndTheEstimateWaitsForTheDuration() {
    assertThat(RequestDetailRules.showsStages(RequestRowSamples.analyzing, failure = false)).isTrue()
    assertThat(RequestDetailRules.showsStages(RequestRowSamples.rerun, failure = false)).isTrue()
    assertThat(RequestDetailRules.showsStages(RequestRowSamples.waiting, failure = false)).isFalse()
    assertThat(RequestDetailRules.showsStages(RequestRowSamples.downloadForbidden, failure = true)).isFalse()
    assertThat(RequestDetailRules.estimateMinutes(260.5)).isEqualTo(5)
    assertThat(RequestDetailRules.estimateMinutes(null)).isNull()
    assertThat(RequestDetailRules.linkEstimate(RequestRowSamples.analyzing, null)).isTrue()
    assertThat(RequestDetailRules.linkEstimate(RequestRowSamples.request("f", RequestState.ANALYZING, kind = RequestKind.FILE), null)).isFalse()
    assertThat(RequestDetailRules.time(now.minusSeconds(39 * 60), now, java.time.ZoneOffset.UTC)).isEqualTo("9:02")
    assertThat(RequestDetailRules.time(now.minus(Duration.ofDays(1)), now, java.time.ZoneOffset.UTC)).isEqualTo("Oct 9, 9:41")
  }
}
