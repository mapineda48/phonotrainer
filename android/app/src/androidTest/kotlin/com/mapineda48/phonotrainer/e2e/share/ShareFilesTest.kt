package com.mapineda48.phonotrainer.e2e.share

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.RequestState
import com.mapineda48.phonotrainer.feature.share.R
import com.mapineda48.phonotrainer.feature.share.ShareTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * Shared files (work-plan WP-23 Proof; spec 8.3, 7.7, SH-AC10, SH-AC13, SH-AC14, SH-AC15, D-49): a real other app
 * shares video and audio through its own provider, which may lie about names and sizes, with read grants that end
 * with the sheet.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ShareFilesTest : ShareAreaTest() {
  @Test
  fun anAmrVoiceNoteIsExplainedWhenTheSheetOpensAndNothingIsCreated() {
    share.shareFiles(listOf(share.amrItem()), type = "audio/amr")

    share.awaitText(words(R.string.share_unsupported))
    assertThat(compose.onAllNodes(hasTestTag(ShareTags.PRIMARY)).fetchSemanticsNodes()).isEmpty()
    share.tap(ShareTags.CLOSE)
    share.awaitClosed()
    share.assertSharingAppOnTop()
    assertThat(share.requests()).isEmpty()
  }

  @Test
  fun twoVideosAndAnAmrAddTwoRequestsAndNameTheThird() {
    share.putClip()
    share.shareFiles(listOf(share.item(name = "first.mp4"), share.item(name = "second.mp4"), share.amrItem()))

    share.awaitText(words(R.string.share_title))
    share.addToQueue()
    share.notNowIfAsked()
    // SH-AC14: "2 of 3 added. 1 file type isn't supported: voice.amr."
    share.awaitText(app.resources.getQuantityString(R.plurals.share_partial_added, 3, 2, 3))
    share.awaitText("voice.amr")
    val added = share.awaitRequests(30_000) { list -> list.size == 2 && list.all { it.state == RequestState.WAITING } }
    assertThat(added.map { it.fileName }).containsExactly("first.mp4", "second.mp4")
    assertThat(added.map { it.kind }.toSet()).containsExactly(RequestKind.FILE)
  }

  @Test
  fun aProviderThatLiesAboutTheNameAndSizeIsCopiedByWhatItReallySends() {
    val bytes = share.putClip()
    share.shareFiles(listOf(share.item(name = "../../databases/evil‮gnp.mp4", declared = "1000")))

    // The name is only its printable base name; the lie about the size does not stop the copy.
    share.awaitText("evilgnp.mp4")
    share.addToQueue()
    share.notNowIfAsked()
    val request = share.awaitRequests(30_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(request.fileName).isEqualTo("evilgnp.mp4")
    assertThat(keptFiles(request.id)).containsExactly("source.mp4")
    assertThat(bytes).isGreaterThan(1000)
  }

  @Test
  fun aVideoAboveFiveHundredMegabytesIsAddedAsAudioOnlyByDefault() {
    // SH-AC6 (the sheet's part): a provider that says 600 MB; "Audio only" is preselected and only the audio is kept.
    share.putClip()
    share.shareFiles(listOf(share.item(declared = "600000000")))

    share.awaitTag(ShareTags.LARGE_FILE)
    compose.onNodeWithTag(ShareTags.AUDIO_ONLY).assertIsOn()
    share.addToQueue()
    share.notNowIfAsked()
    val request = share.awaitRequests(30_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(keptFiles(request.id)).containsExactly("source.m4a")
  }

  @Test
  fun noSpaceBeforeTheCopyStatesTheSpaceAndTryAudioOnlyAddsTheAudioTrack() {
    // A computer that takes anything, and a provider that claims 100 GB: the phone has no room for that copy.
    computerAccepts(maxUploadBytes = 1L shl 40)
    share.putClip()
    share.shareFiles(listOf(share.item(declared = (100L shl 30).toString())))
    // So large a video has "Audio only" preselected; the learner wants the video.
    compose.onNodeWithTag(ShareTags.AUDIO_ONLY).assertIsOn()
    share.tap(ShareTags.AUDIO_ONLY)
    share.addToQueue()

    share.awaitTag(ShareTags.TRY_AUDIO_ONLY)
    share.awaitText(words(R.string.share_no_space_video, "").substringBefore(" MB"))
    assertThat(share.requests()).isEmpty()

    // SH-AC15: "Try audio only" makes one request for the audio track, read while the sheet still holds the item.
    share.tap(ShareTags.TRY_AUDIO_ONLY)
    share.notNowIfAsked()
    val request = share.awaitRequests(30_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(keptFiles(request.id)).containsExactly("source.m4a")
  }

  @Test
  fun noSpaceDuringTheCopyOffersTryAudioOnlyOnTheSameRequest() {
    // The provider claims 1000 bytes and the computer accepts one byte less than the whole video: its copy stops
    // mid-way (the bytes that really arrive are counted, architecture 8.3), while its audio track alone fits.
    val bytes = share.putClip()
    computerAccepts(maxUploadBytes = bytes - 1)
    share.shareFiles(listOf(share.item(declared = "1000")))
    share.addToQueue()
    share.notNowIfAsked()

    share.awaitTag(ShareTags.TRY_AUDIO_ONLY)
    share.awaitTag(ShareTags.REMOVE)
    val failed = share.awaitRequests { it.singleOrNull()?.state == RequestState.COPY_FAILED }.single()
    assertThat(failed.lastError?.code).isEqualTo("copy_no_space")
    assertThat(failed.actions).contains(RequestAction.TRY_AUDIO_ONLY)
    assertThat(keptFiles(failed.id)).isEmpty()

    // The same request goes back to preparing for its audio track, which fits; no second request, nothing half-made.
    share.tap(ShareTags.TRY_AUDIO_ONLY)
    val request = share.awaitRequests(30_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(request.id).isEqualTo(failed.id)
    assertThat(keptFiles(request.id)).containsExactly("source.m4a")
    share.awaitText(words(R.string.share_done_added))
    share.awaitClosed()
    share.assertSharingAppOnTop()
  }

  @Test
  fun onceTheSheetHasClosedTryAudioOnlyIsGoneFromTheRequest() {
    val bytes = share.putClip()
    computerAccepts(maxUploadBytes = bytes - 1)
    share.shareFiles(listOf(share.item(declared = "1000")))
    share.addToQueue()
    share.notNowIfAsked()
    share.awaitTag(ShareTags.TRY_AUDIO_ONLY)

    share.tap(ShareTags.CLOSE)
    share.awaitClosed()
    // D-49: the shared item cannot be read again, so the Requests row offers "Remove" only.
    val request = share.awaitRequests { list -> list.singleOrNull()?.let { RequestAction.TRY_AUDIO_ONLY !in it.actions } == true }.single()
    assertThat(request.state).isEqualTo(RequestState.COPY_FAILED)
    assertThat(request.actions).containsExactly(RequestAction.REMOVE)
  }

  @Test
  fun backDuringACopyAsksFirstAndKeepAddingFinishesIt() {
    share.shareFiles(listOf(share.slowItem(bytes = 3L * 1024 * 1024, pauseMs = 60)))
    share.addToQueue()
    share.notNowIfAsked()
    share.awaitTag(ShareTags.PREPARING)

    // SH-AC13: "Stop adding this video?", and "Keep adding" (the default) continues the copy.
    share.back()
    share.awaitText(words(R.string.share_stop_title_video))
    share.tapText(words(R.string.share_keep_adding))
    val request = share.awaitRequests(60_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(keptFiles(request.id)).containsExactly("source.mp4")
    share.awaitClosed()
    share.assertSharingAppOnTop()
  }

  @Test
  fun stopDuringACopyLeavesNothing() {
    share.shareFiles(listOf(share.slowItem(bytes = 8L * 1024 * 1024, pauseMs = 60)))
    share.addToQueue()
    share.notNowIfAsked()
    share.awaitTag(ShareTags.PREPARING)
    val preparing = share.awaitRequests { it.singleOrNull()?.state == RequestState.PREPARING }.single()

    share.back()
    share.tapText(words(R.string.share_stop))
    share.awaitClosed()
    share.awaitRequests { it.isEmpty() }
    assertThat(keptFiles(preparing.id)).isEmpty()
  }

  @Test
  fun homeDuringALargeCopyAndTheCopyCompletes() {
    share.shareFiles(listOf(share.slowItem(bytes = 4L * 1024 * 1024, pauseMs = 40)))
    share.addToQueue()
    share.notNowIfAsked()
    share.awaitTag(ShareTags.PREPARING)

    // The expedited intake keeps copying with the sheet in the background (architecture 8.3, Android review H2a).
    share.device.pressHome()
    val request = share.awaitRequests(60_000) { it.singleOrNull()?.state == RequestState.WAITING }.single()
    assertThat(keptFiles(request.id)).containsExactly("source.mp4")
  }
}
