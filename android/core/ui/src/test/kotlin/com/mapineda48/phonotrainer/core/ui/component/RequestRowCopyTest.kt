package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.Request
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestProgress
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.WaitingInfo
import com.mapineda48.phonotrainer.core.domain.model.remote.Stage
import com.mapineda48.phonotrainer.core.ui.component.RequestRowSamples.now
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The words of a request card (design 9.19; spec F-20 item 1, 7.7), without drawing it. */
@RunWith(RobolectricTestRunner::class)
class RequestRowCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

  private fun words(request: Request, status: ComputerStatus? = RequestRowSamples.connected) =
    RequestRowCopy.words(RequestRowState(request, status = status, capabilities = RequestRowSamples.capabilities, now = now), now, resources)

  @Test
  fun everyStateHasItsWords() {
    val expected = mapOf(
      "preparing" to "Preparing the file… 40 %",
      "waiting" to "Waiting on this phone",
      "held" to "Waiting on this phone",
      "large file" to "Waiting on this phone",
      "sending" to "Sending 40 % · 125 of 312 MB",
      "in line" to "Waiting in line · 2nd",
      "analyzing" to "Analyzing 61 % · Hear the phones · phrase 3 of 10",
      "cancel waiting" to "Cancel waiting for your computer",
      "starting again" to "Starting again on your computer",
      "getting it" to "Getting it on this phone 70 %",
      "on this phone" to "On this phone",
      "download failed" to "The download failed",
      "too long" to "This video is too long for your computer",
      "no space for the copy" to "Couldn't keep the file",
      "cancelled" to "Cancelled",
    )
    RequestRowSamples.all.forEach { (name, request) ->
      assertWithMessage(name).that(words(request).state).isEqualTo(expected.getValue(name))
    }
  }

  @Test
  fun aComputerOutOfReachGivesTheLastKnownState() {
    assertThat(words(RequestRowSamples.analyzing, RequestRowSamples.unreachable).state)
      .isEqualTo("Last known: Analyzing 61 % · Hear the phones · phrase 3 of 10 (10 min ago)")
    assertThat(words(RequestRowSamples.inLine, ComputerStatus.Offline).state).isEqualTo("Last known: Waiting in line · 2nd (10 min ago)")
    assertThat(words(RequestRowSamples.waiting, ComputerStatus.Offline).state).isEqualTo("Waiting on this phone")
  }

  @Test
  fun aWaitingRowGivesItsReasonTheHeldLineInsteadAndTheLargeFileLineToo() {
    assertThat(words(RequestRowSamples.waiting).reasons.map { it.text }).containsExactly("Will try again in 2 min")
    assertThat(words(RequestRowSamples.waiting, ComputerStatus.NotOnWifi).reasons.map { it.text }).containsExactly("Not on Wi-Fi")
    // F-20-AC11, D-46: the held line replaces the D-61 reason, whatever the connection.
    assertThat(words(RequestRowSamples.waitingHeld, ComputerStatus.Offline).reasons.map { it.text })
      .containsExactly("Stopped by you: it waits here until you tap Send now.")
    // F-20-AC10, D-41: the large-file line is added, whatever the connection.
    listOf(RequestRowSamples.connected, ComputerStatus.Offline, ComputerStatus.NotConnected).forEach { status ->
      val reasons = words(RequestRowSamples.waitingLarge, status).reasons
      assertWithMessage("$status").that(reasons.last())
        .isEqualTo(RequestReason(PhonoIcons.Info, "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open."))
    }
    val heldAndLarge = RequestRowSamples.waitingLarge.copy(waiting = WaitingInfo(held = true, attempts = 0, nextAttemptAt = null, needsAppOpen = true))
    assertThat(words(heldAndLarge).reasons.map { it.text }).containsExactly(
      "Stopped by you: it waits here until you tap Send now.",
      "Open PhonoTrainer on your home Wi-Fi to send this file: large files are sent while the app is open.",
    ).inOrder()
  }

  @Test
  fun theSourceLineNamesTheSiteAndLinkOrTheFilesKindAndSizeAndTheAge() {
    assertThat(words(RequestRowSamples.analyzing).source).isEqualTo("X · x.com/someone/status/189…041")
    assertThat(words(RequestRowSamples.downloadForbidden).source).isEqualTo("YouTube · youtube.com/watch?v=Ab3x…Qx7")
    assertThat(words(RequestRowSamples.waiting).source).isEqualTo("Video · 48 MB · added 3 min ago")
    assertThat(words(RequestRowSamples.waitingLarge).source).isEqualTo("Video · 600 MB · added 15 days ago")
    assertThat(words(RequestRowSamples.waiting.copy(title = "Bus stop chat")).source).isEqualTo("bus-stop-chat.mp4 · Video · 48 MB · added 3 min ago")
    assertThat(words(RequestRowSamples.waitingHeld.copy(createdAt = now.minus(Duration.ofHours(30)))).source)
      .isEqualTo("YouTube · youtube.com/watch?v=Ab3x…Qx7 · added yesterday")
  }

  @Test
  fun theActionsAreTheRequestsOwnInTheCardsOrder() {
    RequestRowSamples.all.forEach { (name, request) ->
      val actions = words(request).actions
      assertWithMessage(name).that(actions.toSet()).isEqualTo(request.actions)
    }
    assertThat(words(RequestRowSamples.waiting).actions).containsExactly(RequestAction.SEND_NOW, RequestAction.REMOVE).inOrder()
    assertThat(words(RequestRowSamples.downloadForbidden).actions)
      .containsExactly(RequestAction.TRY_AGAIN, RequestAction.TRY_AUDIO_ONLY, RequestAction.OPEN_LINK, RequestAction.REMOVE).inOrder()
    assertThat(words(RequestRowSamples.downloadForbidden).primary).isEqualTo(RequestAction.TRY_AGAIN)
  }

  @Test
  fun actionsThatNeedTheComputerReadWhenConnectedWhileItIsNot() {
    fun label(action: RequestAction, request: Request, status: ComputerStatus?) = resources.getString(RequestRowCopy.label(action, request, status))

    assertThat(label(RequestAction.CANCEL, RequestRowSamples.analyzing, RequestRowSamples.connected)).isEqualTo("Cancel")
    assertThat(label(RequestAction.CANCEL, RequestRowSamples.analyzing, ComputerStatus.Offline)).isEqualTo("Cancel when connected")
    assertThat(label(RequestAction.TRY_AGAIN, RequestRowSamples.downloadForbidden, ComputerStatus.Offline)).isEqualTo("Try again when connected")
    assertThat(label(RequestAction.TRY_AUDIO_ONLY, RequestRowSamples.downloadForbidden, RequestRowSamples.unreachable))
      .isEqualTo("Try audio only when connected")
    // A copy's "Try audio only" happens on this phone.
    assertThat(label(RequestAction.TRY_AUDIO_ONLY, RequestRowSamples.copyNoSpace, ComputerStatus.Offline)).isEqualTo("Try audio only")
    assertThat(label(RequestAction.REMOVE, RequestRowSamples.downloadForbidden, ComputerStatus.Offline)).isEqualTo("Remove")
    assertThat(label(RequestAction.OPEN_LINK, RequestRowSamples.downloadForbidden, null)).isEqualTo("Open the link")
  }

  @Test
  fun theVisualAndTheBarFollowTheState() {
    assertThat(words(RequestRowSamples.waiting).visual).isEqualTo(RequestVisual.ROUTE)
    assertThat(words(RequestRowSamples.sending).progress).isWithin(0.001f).of(0.4f)
    assertThat(words(RequestRowSamples.analyzing).visual).isEqualTo(RequestVisual.STEPPER)
    assertThat(words(RequestRowSamples.analyzing).progress).isNull()
    assertThat(words(RequestRowSamples.rerun).visual).isEqualTo(RequestVisual.NONE)
    assertThat(words(RequestRowSamples.downloadForbidden).visual).isEqualTo(RequestVisual.NONE)
    assertThat(words(RequestRowSamples.downloadForbidden).alert).isTrue()
    assertThat(words(RequestRowSamples.inLine).body).isEqualTo("Analyses run one at a time.")
  }

  @Test
  fun theStagesOfALinkAndOfAFile() {
    val stages = RequestRowStages.of(RequestRowSamples.analyzing, resources)
    assertThat(stages.map { it.stage }).containsExactly(Stage.DOWNLOAD, Stage.EXTRACT, Stage.SEPARATE, Stage.TRANSCRIBE, Stage.PHONES, Stage.SAVE).inOrder()
    assertThat(stages.map { it.state.name }).containsExactly("DONE", "DONE", "DONE", "DONE", "CURRENT", "NEXT").inOrder()
    assertThat(stages[4].detail).isEqualTo("phrase 3 of 10")
    assertThat(RequestRowStages.description(stages, resources)).isEqualTo("Step 5 of 6: Hear the phones, in progress.")
    assertThat(RequestRowStages.teachingLead(stages, resources)).isEqualTo("Step 5 of 6 · phrase 3 of 10.")

    val file = RequestRowSamples.request(
      "file",
      RequestState.ANALYZING,
      kind = RequestKind.FILE,
      progress = RequestProgress(Stage.EXTRACT, 5, null, null, null, null),
    )
    assertThat(RequestRowStages.of(file, resources).map { it.stage }).doesNotContain(Stage.DOWNLOAD)
    assertThat(RequestRowStages.description(RequestRowStages.of(RequestRowSamples.inLine, resources), resources))
      .isEqualTo("6 steps on your computer, not started yet.")
    assertThat(RequestRowStages.description(RequestRowStages.of(RequestRowSamples.getting, resources), resources)).isEqualTo("All 6 steps done.")
    assertThat(RequestRowStages.announcement(Stage.TRANSCRIBE, resources)).isEqualTo("Now: Transcribe the words.")
  }

  @Test
  fun showDetailsNeverShowsAnEscapeSequenceOrAControlCharacter() {
    assertThat(RequestRowCopy.details("\u001B[31mERROR:\u001B[0m the download\u0007 failed\nline two"))
      .isEqualTo("ERROR: the download failed\nline two")
    assertThat(RequestRowCopy.details("\u001B]0;title\u0007plain")).isEqualTo("plain")
    assertThat(RequestRowCopy.details("\u001B[0m")).isNull()
    assertThat(RequestRowCopy.details(null)).isNull()
    val copy = RequestFailureCopy.of(
      com.mapineda48.phonotrainer.core.domain.model.RequestError("processing_failed", "\u001B[1mSegment 3\u001B[0m failed", null, atSending = false),
      FailureContext(RequestKind.LINK, Site.YOUTUBE, setOf(RequestAction.TRY_AGAIN), null),
      resources,
    )
    assertThat(copy.body).isEqualTo("Segment 3 failed")
  }

  @Test
  fun numbersAndLinksAreWrittenOneWay() {
    assertThat(RequestRowCopy.ordinal(1)).isEqualTo("1st")
    assertThat(RequestRowCopy.ordinal(2)).isEqualTo("2nd")
    assertThat(RequestRowCopy.ordinal(3)).isEqualTo("3rd")
    assertThat(RequestRowCopy.ordinal(11)).isEqualTo("11th")
    assertThat(RequestRowCopy.ordinal(22)).isEqualTo("22nd")
    assertThat(RequestRowCopy.bytes(850 * 1024, resources)).isEqualTo("850 KB")
    assertThat(RequestRowCopy.bytes(2L * 1024 * 1024 * 1024, resources)).isEqualTo("2 GB")
    assertThat(RequestRowCopy.bytesOf(850 * 1024, 1_288_490_189, resources)).isEqualTo("850 KB of 1.2 GB")
    assertThat(RequestRowCopy.shortLink("https://youtu.be/abc")).isEqualTo("youtu.be/abc")
    assertThat(RequestRowCopy.isVideo(RequestRowSamples.waiting)).isTrue()
    assertThat(RequestRowCopy.isVideo(RequestRowSamples.request("a", RequestState.WAITING, kind = RequestKind.FILE, fileName = "talk.opus"))).isFalse()
    assertThat(RequestRowCopy.sourceIcon(RequestRowSamples.request("x", RequestState.WAITING, site = Site.X))).isEqualTo(PhonoIcons.SiteX)
  }
}
