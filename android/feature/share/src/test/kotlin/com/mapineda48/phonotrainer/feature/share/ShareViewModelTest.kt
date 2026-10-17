package com.mapineda48.phonotrainer.feature.share

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AddFilesResult
import com.mapineda48.phonotrainer.core.domain.model.AddResult
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Duplicate
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.IntakeProgress
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.testing.MainDispatcherRule
import com.mapineda48.phonotrainer.feature.share.ShareViewModel.Companion.AUTO_CLOSE_MS
import com.mapineda48.phonotrainer.feature.share.ShareViewModel.Companion.PROBE_WAIT_MS
import com.mapineda48.phonotrainer.feature.share.intake.SharedInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The share sheet's flow (spec 8.4, 8.4.1, 7.7; architecture 12) over the settable fakes of work-plan 4.6. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
  @get:Rule
  val main = MainDispatcherRule(StandardTestDispatcher())

  private val h = ShareHarness()
  private val connected = ComputerStatus.Connected("Studio PC", analyzing = 1, inLine = 1, lowOnSpace = false)

  private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

  private fun TestScope.startLink(text: String = "Great scene https://youtu.be/abc?si=xyz", subject: String? = null) {
    h.viewModel.start(SharedInput.Text(text, subject))
    runCurrent()
  }

  private fun TestScope.startFiles(vararg uris: String, overLimit: Int = 0) {
    h.viewModel.start(SharedInput.Files(uris.toList(), "video/*", overLimit))
    runCurrent()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The probe (spec 8.4 item 3)

  @Test
  fun theSheetWaitsAtMostTwoSecondsForItsProbeThenShowsTheLastKnownState() = test {
    h.status.fake.statusState.value = ComputerStatus.Offline
    startLink()

    assertThat(h.status.asked).isEqualTo(1)
    assertThat(h.state.checking).isTrue()
    assertThat(h.state.analyzeNow).isTrue()
    advanceTimeBy(PROBE_WAIT_MS - 1)
    assertThat(h.state.checking).isTrue()
    advanceTimeBy(2)
    assertThat(h.state.checking).isFalse()
    assertThat(h.state.status).isEqualTo(ComputerStatus.Offline)
    assertThat(h.state.analyzeNow).isFalse()

    // The answer that arrives later updates the line.
    h.status.fake.statusState.value = connected
    h.status.answer.complete(connected)
    runCurrent()
    assertThat(h.state.status).isEqualTo(connected)
    assertThat(h.state.analyzeNow).isTrue()
  }

  @Test
  fun aProbeThatAnswersEarlyEndsTheCheckAtOnce() = test {
    startLink()
    h.status.fake.statusState.value = connected
    h.status.answer.complete(connected)
    runCurrent()

    assertThat(h.state.checking).isFalse()
    assertThat(h.state.status).isEqualTo(connected)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Links

  @Test
  fun aSharedLinkShowsItsSiteAndTheLinkThatWillBeSent() = test {
    h.parser.rule = { _, _ -> com.mapineda48.phonotrainer.core.domain.model.ParsedLink(
      "https://youtu.be/abc?si=xyz", "https://youtu.be/abc", "yt:abc", Site.YOUTUBE,
    ) }
    startLink("Great scene https://youtu.be/abc?si=xyz", "Great scene")

    assertThat(h.parser.parsed).contains("Great scene https://youtu.be/abc?si=xyz" to "Great scene")
    assertThat(h.state.summary).isEqualTo(SharedSummary.Link(Site.YOUTUBE, "https://youtu.be/abc"))
    assertThat(h.state.phase).isEqualTo(SharePhase.Confirm)
    assertThat(h.state.audioOnlyOffered).isTrue()
  }

  @Test
  fun sharedTextWithoutALinkCreatesNothing() = test {
    startLink("Look at this!")
    h.viewModel.submit()
    runCurrent()

    assertThat(h.state.phase).isEqualTo(SharePhase.Problem(ShareProblem.NoLink))
    assertThat(h.requests.links).isEmpty()
  }

  @Test
  fun aLinkAlreadyAddedOffersToOpenItOrAnalyzeAgainWhenTheSheetOpens() = test {
    val duplicate = Duplicate(RequestId("r1"), JobId("j1"), "Street interview")
    h.requests.duplicate = duplicate
    startLink()

    assertThat(h.state.phase).isEqualTo(SharePhase.DuplicateFound(duplicate))
    assertThat(h.requests.links).isEmpty()

    h.viewModel.submit(force = true)
    runCurrent()
    assertThat(h.requests.links.single().force).isTrue()
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)
  }

  @Test
  fun openItOpensTheRequestThenTheClipThenRequests() {
    assertThat(targetOf(Duplicate(RequestId("r1"), JobId("j1"), null)))
      .isEqualTo(com.mapineda48.phonotrainer.core.navigation.NavTarget.RequestDetail(RequestId("r1")))
    assertThat(targetOf(Duplicate(null, JobId("j1"), null)))
      .isEqualTo(com.mapineda48.phonotrainer.core.navigation.NavTarget.ClipFeed(JobId("j1")))
    assertThat(targetOf(Duplicate(null, null, null)))
      .isEqualTo(com.mapineda48.phonotrainer.core.navigation.NavTarget.Requests)
  }

  @Test
  fun addToQueueStoresTheRequestWithItsOptionsAndSaysItWaits() = test {
    h.status.fake.statusState.value = ComputerStatus.Offline
    h.requests.addLinkResult = { AddResult.Added(RequestId("r1")) }
    startLink("Great scene https://youtu.be/abc", "Great scene")
    h.viewModel.setAudioOnly(true)
    h.viewModel.submit()
    runCurrent()

    val input = h.requests.links.single()
    assertThat(input.sharedText).isEqualTo("Great scene https://youtu.be/abc")
    assertThat(input.subject).isEqualTo("Great scene")
    assertThat(input.audioOnly).isTrue()
    assertThat(input.force).isFalse()
    assertThat(h.state.phase).isEqualTo(SharePhase.Done(listOf(RequestId("r1")), DoneMessage.ADDED))
  }

  @Test
  fun aLinkSentWhileConnectedReadsSendingThenSentWhenTheComputerConfirms() = test {
    h.status.fake.statusState.value = connected
    val id = RequestId("r1")
    h.requests.addLinkResult = { AddResult.Added(id) }
    h.put(h.request(id, RequestState.WAITING, RequestKind.LINK))
    startLink()
    h.viewModel.submit()
    runCurrent()
    assertThat((h.state.phase as SharePhase.Done).message).isEqualTo(DoneMessage.SENDING)

    h.put(h.request(id, RequestState.SENT, RequestKind.LINK))
    runCurrent()
    assertThat((h.state.phase as SharePhase.Done).message).isEqualTo(DoneMessage.SENT)

    h.put(h.request(id, RequestState.NEEDS_ATTENTION, RequestKind.LINK))
    runCurrent()
    assertThat((h.state.phase as SharePhase.Done).message).isEqualTo(DoneMessage.REFUSED)
  }

  @Test
  fun aRecordThatCannotBeWrittenSaysTheShareWasNotSaved() = test {
    h.requests.addLinkResult = { AddResult.OutOfSpace }
    startLink()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.state.phase).isEqualTo(SharePhase.Problem(ShareProblem.RecordOutOfSpace))
  }

  @Test
  fun aDuplicateFoundAtTheTapIsOfferedLikeOneFoundAtOpen() = test {
    val duplicate = Duplicate(null, JobId("j1"), "Street interview")
    h.requests.addLinkResult = { AddResult.DuplicateFound(duplicate) }
    startLink()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.state.phase).isEqualTo(SharePhase.DuplicateFound(duplicate))
  }

  @Test
  fun aSecondTapWhileStoringAddsNothingMore() = test {
    startLink()
    h.viewModel.submit()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.requests.links).hasSize(1)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Files (spec 8.3)

  @Test
  fun anUnsupportedFileIsExplainedWhenTheSheetOpensAndNothingIsCreated() = test {
    h.files.items = { uris -> uris.map { FakeSharedFiles.amr(it) } }
    var added = 0
    h.requests.addFilesResult = { added++; AddFilesResult(emptyList(), emptyList()) }
    startFiles("content://media.test/voice")

    assertThat(h.state.phase).isEqualTo(
      SharePhase.Problem(ShareProblem.Refused(1, listOf(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null)))),
    )
    assertThat(h.viewModel.state.value.phase).isNotInstanceOf(SharePhase.Confirm::class.java)
    assertThat(added).isEqualTo(0)
  }

  @Test
  fun aMultipleShareAddsTheSupportedFilesAndNamesTheOthers() = test {
    h.files.items = { uris -> listOf(FakeSharedFiles.video(uris[0]), FakeSharedFiles.video(uris[1]), FakeSharedFiles.amr(uris[2])) }
    val sources = mutableListOf<String>()
    h.requests.addFilesResult = { files ->
      sources += files.map { it.uri }
      AddFilesResult(files.mapIndexed { i, _ -> RequestId("r$i") }, emptyList())
    }
    listOf("r0", "r1").forEach { h.put(h.request(RequestId(it), RequestState.WAITING)) }
    startFiles("content://media.test/a", "content://media.test/b", "content://media.test/c")

    assertThat(h.state.summary).isEqualTo(SharedSummary.ManyFiles(3, videos = 2, audios = 1, bytes = 96_030_000))
    h.viewModel.submit()
    runCurrent()

    // The unsupported item never reaches the queue; the two others are one request each.
    assertThat(sources).containsExactly("content://media.test/a", "content://media.test/b").inOrder()
    val done = h.state.phase as SharePhase.Done
    assertThat(done.ids).containsExactly(RequestId("r0"), RequestId("r1"))
    assertThat(done.total).isEqualTo(3)
    assertThat(done.refusals).containsExactly(FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null))
  }

  @Test
  fun itemsBeyondTenAreNamedAsOverTheLimit() = test {
    startFiles("content://media.test/a", overLimit = 2)

    h.requests.addFilesResult = { AddFilesResult(listOf(RequestId("r0")), emptyList()) }
    h.put(h.request(RequestId("r0"), RequestState.WAITING))
    h.viewModel.submit()
    runCurrent()

    val done = h.state.phase as SharePhase.Done
    assertThat(done.total).isEqualTo(3)
    assertThat(done.refusals.map { it.reason }).containsExactly(FileRefusalReason.TOO_MANY, FileRefusalReason.TOO_MANY)
  }

  @Test
  fun aVideoAboveFiveHundredMegabytesPreselectsAudioOnly() = test {
    h.files.items = { uris -> listOf(FakeSharedFiles.video(uris[0], bytes = 600_000_000)) }
    startFiles("content://media.test/big")

    assertThat(h.state.largeFile).isTrue()
    assertThat(h.state.audioOnly).isTrue()
    h.viewModel.submit()
    runCurrent()
    assertThat(h.fileOptions.single().audioOnly).isTrue()
  }

  @Test
  fun anAudioFileOffersNoAudioOnly() = test {
    h.files.items = { uris -> listOf(FakeSharedFiles.audio(uris[0])) }
    startFiles("content://media.test/song")

    assertThat(h.state.audioOnlyOffered).isFalse()
    h.viewModel.setAudioOnly(true)
    assertThat(h.state.audioOnly).isFalse()
  }

  @Test
  fun theCopyIsFollowedToAddedAndTheSourcesAreHeldUntilTheSheetIsDestroyed() = test {
    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    val progress = h.intake(id)
    h.put(h.request(id, RequestState.PREPARING))
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    assertThat(h.requests.heldSources).isEqualTo(1)
    progress.value = IntakeProgress(id, 19_200_000, 48_000_000, done = false, failed = null)
    runCurrent()
    val preparing = h.state.phase as SharePhase.Preparing
    assertThat(preparing.fraction).isWithin(0.001f).of(0.4f)
    assertThat(h.state.bridge.phone).isEqualTo(com.mapineda48.phonotrainer.core.ui.component.BridgePhone.Preparing(0.4f))
    assertThat(h.state.mayCloseByItself).isFalse()

    progress.value = IntakeProgress(id, 48_000_000, 48_000_000, done = true, failed = null)
    h.put(h.request(id, RequestState.WAITING))
    runCurrent()
    assertThat(h.state.phase).isEqualTo(SharePhase.Done(listOf(id), DoneMessage.ADDED, total = 1))

    h.destroy()
    assertThat(h.requests.heldSources).isEqualTo(0)
  }

  @Test
  fun noSpaceDuringTheCopyOffersTryAudioOnlyOnTheSameRequest() = test {
    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    val progress = h.intake(id)
    h.put(h.request(id, RequestState.PREPARING))
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    val error = RequestError("copy_no_space", null, null, atSending = false, neededBytes = 120_000_000)
    progress.value = IntakeProgress(id, 0, 48_000_000, done = false, failed = error)
    h.put(h.request(id, RequestState.COPY_FAILED, actions = setOf(RequestAction.REMOVE, RequestAction.TRY_AUDIO_ONLY), error = error))
    runCurrent()
    val failed = h.state.phase as SharePhase.CopyFailed
    assertThat(failed.error).isEqualTo(error)
    assertThat(h.state.bridge.phone).isEqualTo(com.mapineda48.phonotrainer.core.ui.component.BridgePhone.Alert)
    assertThat(h.state.mayCloseByItself).isFalse()

    h.viewModel.tryAudioOnly()
    runCurrent()
    assertThat(h.requests.performed).containsExactly(id to RequestAction.TRY_AUDIO_ONLY)

    // The same request goes back to preparing for its audio track, then is added; no second request exists.
    progress.value = IntakeProgress(id, 100_000, null, done = false, failed = null)
    h.put(h.request(id, RequestState.PREPARING))
    runCurrent()
    assertThat(h.state.phase).isInstanceOf(SharePhase.Preparing::class.java)
    progress.value = IntakeProgress(id, 2_000_000, 2_000_000, done = true, failed = null)
    h.put(h.request(id, RequestState.WAITING))
    runCurrent()
    assertThat(h.state.phase).isEqualTo(SharePhase.Done(listOf(id), DoneMessage.ADDED, total = 1))
    assertThat(h.requests.all.value).hasSize(1)
  }

  @Test
  fun tryAudioOnlyIsNotPerformedWhenTheRequestNoLongerOffersIt() = test {
    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    h.intake(id)
    val error = RequestError("copy_no_space", null, null, atSending = false, neededBytes = 120_000_000)
    h.put(h.request(id, RequestState.COPY_FAILED, actions = setOf(RequestAction.REMOVE), error = error))
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    h.viewModel.tryAudioOnly()
    runCurrent()
    assertThat(h.requests.performed).isEmpty()

    h.viewModel.removeFailed()
    runCurrent()
    assertThat(h.requests.performed).containsExactly(id to RequestAction.REMOVE)
  }

  @Test
  fun aVideoRefusedForSpaceBeforeTheCopyCanBeAddedAsAudioOnly() = test {
    h.requests.addFilesResult = { AddFilesResult(emptyList(), listOf(FileRefusal("clip.mp4", FileRefusalReason.NO_SPACE, 900_000_000))) }
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    val problem = (h.state.phase as SharePhase.Problem).problem as ShareProblem.Refused
    assertThat(problem.tryAudioOnly).isTrue()
    assertThat(problem.refusals.single().neededBytes).isEqualTo(900_000_000)

    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    h.put(h.request(id, RequestState.PREPARING))
    h.intake(id)
    h.viewModel.tryAudioOnly()
    runCurrent()
    assertThat(h.state.phase).isInstanceOf(SharePhase.Preparing::class.java)
    // No request existed: the one created now is the video's audio track, nothing else.
    assertThat(h.fileOptions.map { it.audioOnly }).containsExactly(false, true).inOrder()
  }

  @Test
  fun anAudioFileRefusedForSpaceIsNotOfferedAudioOnly() = test {
    h.files.items = { uris -> listOf(FakeSharedFiles.audio(uris[0])) }
    h.requests.addFilesResult = { AddFilesResult(emptyList(), listOf(FileRefusal("song.m4a", FileRefusalReason.NO_SPACE, 5_000_000))) }
    startFiles("content://media.test/song")
    h.viewModel.submit()
    runCurrent()

    assertThat(((h.state.phase as SharePhase.Problem).problem as ShareProblem.Refused).tryAudioOnly).isFalse()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Back during a copy (SH-AC13)

  @Test
  fun backDuringACopyAsksFirstAndKeepAddingContinues() = test {
    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    h.intake(id)
    h.put(h.request(id, RequestState.PREPARING))
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    h.viewModel.back()
    assertThat(h.state.confirmStop).isTrue()
    assertThat(h.state.phase).isInstanceOf(SharePhase.Preparing::class.java)
    h.viewModel.keepAdding()
    assertThat(h.state.confirmStop).isFalse()
    assertThat(h.requests.performed).isEmpty()

    h.viewModel.back()
    h.viewModel.stopAdding()
    runCurrent()
    assertThat(h.requests.performed).containsExactly(id to RequestAction.CANCEL_PREPARING)
    assertThat(h.state.phase).isEqualTo(SharePhase.Closed)
  }

  @Test
  fun backBeforeTheTapClosesAtOnceAndCreatesNothing() = test {
    startLink()
    h.viewModel.back()

    assertThat(h.state.phase).isEqualTo(SharePhase.Closed)
    assertThat(h.requests.links).isEmpty()
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Closing by itself (spec 8.4 item 8, D-51, A-08, A-11)

  @Test
  fun theSheetClosesByItselfTwoSecondsAfterTheRequestIsStored() = test {
    h.viewModel.setTalkBack(false)
    startLink()
    h.viewModel.submit()
    runCurrent()

    advanceTimeBy(AUTO_CLOSE_MS - 1)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)
    advanceTimeBy(2)
    assertThat(h.state.phase).isEqualTo(SharePhase.Closed)
  }

  @Test
  fun withTalkBackOnTheSheetNeverClosesByItself() = test {
    h.viewModel.setTalkBack(true)
    startLink()
    h.viewModel.submit()
    runCurrent()

    advanceTimeBy(60_000)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)
  }

  @Test
  fun theNotificationQuestionHoldsTheCloseAndTheCountStartsAgainOnceAnswered() = test {
    h.asker.state.value = true
    h.viewModel.setTalkBack(false)
    startLink()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.state.question).isEqualTo(NotificationQuestion.ASKING)
    advanceTimeBy(10_000)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)

    h.viewModel.notNow()
    runCurrent()
    assertThat(h.asker.answers).containsExactly("not_now")
    advanceTimeBy(AUTO_CLOSE_MS - 1)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)
    advanceTimeBy(2)
    assertThat(h.state.phase).isEqualTo(SharePhase.Closed)
  }

  @Test
  fun theSystemDialogHoldsTheCloseUntilItAnswers() = test {
    h.asker.state.value = true
    h.viewModel.setTalkBack(false)
    startLink()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.viewModel.notifyMe()).isTrue()
    assertThat(h.state.question).isEqualTo(NotificationQuestion.SYSTEM_PROMPT)
    advanceTimeBy(10_000)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Done::class.java)

    h.viewModel.onSystemPromptResult(granted = true)
    assertThat(h.asker.promptResults).containsExactly(true)
    advanceTimeBy(AUTO_CLOSE_MS + 1)
    assertThat(h.state.phase).isEqualTo(SharePhase.Closed)
  }

  @Test
  fun theQuestionIsAskedOnlyWhenTheAskerSaysSo() = test {
    h.asker.state.value = false
    startLink()
    h.viewModel.submit()
    runCurrent()

    assertThat(h.state.question).isEqualTo(NotificationQuestion.NONE)
  }

  @Test
  fun aCopyInProgressNeverClosesByItself() = test {
    h.viewModel.setTalkBack(false)
    val id = RequestId("r1")
    h.requests.addFilesResult = { AddFilesResult(listOf(id), emptyList()) }
    h.intake(id)
    h.put(h.request(id, RequestState.PREPARING))
    startFiles("content://media.test/clip")
    h.viewModel.submit()
    runCurrent()

    advanceTimeBy(60_000)
    assertThat(h.state.phase).isInstanceOf(SharePhase.Preparing::class.java)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // A recreated sheet

  @Test
  fun aSheetRecreatedAfterStoringFollowsItsRequestInsteadOfAddingAgain() = test {
    h.saved["share.stored"] = arrayListOf("r1")
    h.put(h.request(RequestId("r1"), RequestState.WAITING, RequestKind.LINK))
    startLink()

    assertThat(h.requests.links).isEmpty()
    assertThat(h.state.phase).isEqualTo(SharePhase.Done(listOf(RequestId("r1")), DoneMessage.ADDED))
  }

  @Test
  fun startRunsOnce() = test {
    startLink()
    h.viewModel.start(SharedInput.Text("https://x.com/a/status/1", null))
    runCurrent()

    assertThat(h.parser.parsed).hasSize(1)
    assertThat(h.status.asked).isEqualTo(1)
  }
}
