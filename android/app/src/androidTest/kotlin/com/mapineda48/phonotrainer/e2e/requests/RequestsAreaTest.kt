package com.mapineda48.phonotrainer.e2e.requests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.SettingsRepository
import com.mapineda48.phonotrainer.core.domain.api.SyncController
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.SyncReason
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.navigation.AddClipKey
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.ui.UiTags
import com.mapineda48.phonotrainer.e2e.PhonoRobot
import com.mapineda48.phonotrainer.e2e.support.TestSeeder
import com.mapineda48.phonotrainer.feature.requests.R
import com.mapineda48.phonotrainer.feature.requests.RequestsTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Requests area on the global test set with the repositories of this baseline (work-plan 2.4 and section 6 "GMD
 * area tests"): Requests, the request detail and "Add a clip" open, Back follows architecture 16.2, and the happy path
 * runs through the real device queue (`:core:requests`): a link pasted in "Add a clip" waits on this phone, shows in
 * Requests and in its detail, is found again as a duplicate, and is removed only after the confirmation. Those tests do
 * not pair the phone, so the queue waits ("Add to queue"); the cross-area flows are WP-19's, through [RequestsRobot].
 * One test pairs with the fake computer through [TestSeeder] to prove what a sent link carries back to the detail:
 * "Separate the dialogue" turned off, the skipped step, and the clip's length once the computer knows it.
 */
@HiltAndroidTest
class RequestsAreaTest {
  @get:Rule(order = 0)
  val hilt = HiltAndroidRule(this)

  @get:Rule(order = 1)
  val compose = createEmptyComposeRule()

  @Inject lateinit var settings: SettingsRepository

  @Inject lateinit var queue: RequestRepository

  @Inject lateinit var seeder: TestSeeder

  @Inject lateinit var sync: SyncController

  private val phono = PhonoRobot(compose)
  private lateinit var requests: RequestsRobot

  @Before
  fun setUp() {
    hilt.inject()
    requests = RequestsRobot(compose, phono, queue)
    runBlocking { settings.update { it.copy(welcomeDone = true) } }
    phono.launch()
  }

  @After
  fun tearDown() {
    if (phono.isLaunched()) phono.scenario.close()
  }

  @Test
  fun theDestinationsOpenTheirLinksLeadOnAndBackFollowsTheShell() {
    requests.openRequests()
    phono.waitForTag(RequestsTags.EMPTY)
    compose.onNode(hasText(requests.words(R.string.requests_empty_title))).assertIsDisplayed()

    // The empty state's "Add a clip", then Back.
    val add = hasTestTag("${UiTags.EMPTY_ACTION}.0") and hasAnyAncestor(hasTestTag(RequestsTags.EMPTY))
    requests.await(add)
    compose.onNode(add).performClick()
    phono.assertShown(AddClipKey())
    phono.back()
    phono.assertShown(RequestsKey)

    // The top bar's "Add a clip", then Back twice: Requests, then Home at its root.
    phono.tap(RequestsTags.LINK_ADD)
    phono.assertShown(AddClipKey())
    phono.back()
    phono.assertShown(RequestsKey)
    phono.back()
    phono.assertShown(HomeKey)
    phono.assertNotShown(RequestsKey)
  }

  @Test
  fun aRequestThisPhoneDoesNotHaveSaysSoAndLeadsToRequests() {
    val gone = RequestDetailKey(RequestId("e2e-requests-unknown"))
    phono.open(gone)
    phono.assertShown(gone)
    phono.waitForTag(RequestsTags.DETAIL_REMOVED)
    requests.tapText(requests.words(R.string.detail_see_requests))
    phono.assertShown(RequestsKey)
  }

  @Test
  fun aPastedLinkWaitsOnThisPhoneShowsInRequestsAndIsRemovedOnlyAfterConfirming() {
    requests.clipboard("Great scene https://youtu.be/e2eRequests1?si=xyz")
    requests.openAddClip()
    assertThat(requests.linkText()).isEmpty()

    // F-19-AC1: the clipboard is read on "Paste"; its first link fills the field and its site is named.
    requests.paste()
    assertThat(requests.linkText()).startsWith("https://youtu.be/e2eRequests1")
    requests.awaitTextIn(RequestsTags.ADD_SITE, requests.words(R.string.add_site_youtube))
    requests.submit()
    requests.notNowIfAsked()
    requests.await(hasTestTag(RequestsTags.ADD_DONE) and hasText(requests.words(R.string.add_done_added)))
    val stored = requests.awaitQueue { it.size == 1 }.single()
    assertThat(stored.state).isEqualTo(RequestState.WAITING)
    assertThat(stored.link).isEqualTo("https://youtu.be/e2eRequests1")

    // "See requests": the request waits on this phone, in its group, with "Remove".
    requests.tapText(requests.words(R.string.add_see_requests))
    phono.assertShown(RequestsKey)
    requests.await(hasTestTag(RequestsTags.group("WAITING")))
    requests.scrollToCard(stored.id)

    // Its detail: the state, then "Remove" asks first; "Keep it" keeps it.
    requests.openCard(stored.id)
    requests.awaitTextIn(RequestsTags.DETAIL_CARD, requests.words(com.mapineda48.phonotrainer.core.ui.R.string.request_state_waiting))
    requests.tapInDetail(RequestAction.REMOVE)
    requests.await(hasTestTag(RequestsTags.REMOVE_DIALOG))
    requests.tapText(requests.words(R.string.requests_remove_dismiss))
    assertThat(requests.queue()).hasSize(1)

    requests.tapInDetail(RequestAction.REMOVE)
    requests.await(hasTestTag(RequestsTags.REMOVE_DIALOG))
    compose.onNode(
      hasText(requests.words(R.string.requests_remove_confirm)) and hasClickAction() and hasAnyAncestor(hasTestTag(RequestsTags.REMOVE_DIALOG)),
    ).performClick()
    requests.awaitQueue { it.isEmpty() }
    phono.assertShown(RequestsKey)
    phono.waitForTag(RequestsTags.EMPTY)
  }

  @Test
  fun theSameLinkAgainOffersOpenItAndAnalyzeAgainAnyway() {
    requests.openAddClip()
    requests.type("https://youtu.be/e2eRequests2")
    requests.submit()
    requests.notNowIfAsked()
    requests.await(hasTestTag(RequestsTags.ADD_DONE))
    val first = requests.awaitQueue { it.size == 1 }.single()

    // Another form of the same video (spec 7.7): "You already added this link." with "Open it".
    requests.tapText(requests.words(R.string.add_another))
    requests.type("https://www.youtube.com/watch?v=e2eRequests2&t=42")
    requests.submit()
    requests.await(hasTestTag(RequestsTags.ADD_DUPLICATE))
    assertThat(requests.queue()).hasSize(1)
    requests.tapScrolled(RequestsTags.ADD_OPEN_IT)
    phono.assertShown(RequestDetailKey(first.id))

    // Back to "Add a clip": "Analyze again anyway" adds a second request.
    phono.back()
    phono.assertShown(AddClipKey())
    requests.tapScrolled(RequestsTags.ADD_AGAIN)
    requests.await(hasTestTag(RequestsTags.ADD_DONE))
    assertThat(requests.awaitQueue { it.size == 2 }.map { it.link }.toSet()).hasSize(2)
  }

  @Test
  fun aLinkSentWithoutDialogueSeparationShowsTheStepSkippedAndItsLengthOnceKnown() {
    // CR-WP22-1, CR-WP22-2 and CR-WP22-3 through the real queue, sync engine and HTTP client, on the fake computer.
    runBlocking { seeder.pair() }
    requests.openAddClip()
    requests.type("https://youtu.be/e2eRequests4")

    // Spec F-19: the switch is offered for a link too, on by default; the learner turns it off.
    phono.waitForTag(RequestsTags.ADD_SEPARATE)
    compose.onNodeWithTag(RequestsTags.ADD_SEPARATE).assertIsOn()
    requests.tapScrolled(RequestsTags.ADD_SEPARATE)
    compose.onNodeWithTag(RequestsTags.ADD_SEPARATE).assertIsOff()
    requests.submit()
    requests.notNowIfAsked()

    val sent = requests.awaitQueue(drive = { seeder.workDriver.runEnqueued() }) { it.singleOrNull()?.jobId != null }.single()
    assertThat(seeder.computer.jobsWithKey(sent.id).single().separateDialogue).isFalse()
    assertThat(sent.durationS).isNull()

    // The detail: "Separate the dialogue" is skipped, turned off, while the link waits on the computer.
    phono.open(RequestDetailKey(sent.id))
    phono.assertShown(RequestDetailKey(sent.id))
    val turnedOff = requests.words(com.mapineda48.phonotrainer.core.ui.R.string.request_stage_skipped_off)
    requests.awaitTextIn(RequestsTags.stage(Stage.SEPARATE), turnedOff)
    requests.awaitTextIn(RequestsTags.DETAIL_ESTIMATE, requests.words(R.string.detail_estimate_link))

    // The computer finishes it: the clip's length shows on the source line, and the step stays skipped.
    seeder.computer.finishJob(sent.jobId!!)
    val done = requests.awaitQueue(
      drive = {
        sync.syncNow(SyncReason.SYNC_NOW)
        seeder.workDriver.runEnqueued()
      },
    ) { it.single().durationS != null }.single()
    val seconds = done.durationS!!.toLong()
    requests.awaitTextIn(RequestsTags.DETAIL_SOURCE, String.format(Locale.US, " · %d:%02d", seconds / 60, seconds % 60))
    requests.awaitTextIn(RequestsTags.stage(Stage.SEPARATE), turnedOff)
  }

  @Test
  fun aRecreateKeepsTheTypedLinkAndItsSite() {
    requests.openAddClip()
    requests.type("https://x.com/someone/status/1234567")
    requests.awaitTextIn(RequestsTags.ADD_SITE, requests.words(R.string.add_site_x))

    phono.scenario.recreate()
    phono.assertShown(AddClipKey())
    requests.awaitTextIn(RequestsTags.ADD_SITE, requests.words(R.string.add_site_x))
    assertThat(requests.linkText()).isEqualTo("https://x.com/someone/status/1234567")
  }
}
