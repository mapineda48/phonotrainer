package com.mapineda48.phonotrainer.core.ui.component

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.domain.model.RequestAction
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.OPEN_LINK
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.REMOVE
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.SHOW_DETAILS
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.TRY_AGAIN
import com.mapineda48.phonotrainer.core.domain.model.RequestAction.TRY_AUDIO_ONLY
import com.mapineda48.phonotrainer.core.domain.model.RequestError
import com.mapineda48.phonotrainer.core.domain.model.RequestKind
import com.mapineda48.phonotrainer.core.domain.model.Site
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one failure-copy mapping (architecture 19; work-plan WP-22 Proof): every code of server-contract 8.1 and 8.2 that
 * leaves a request needing the learner, and every phone-side copy code of architecture 8.3, for both kinds, to the
 * spec 8.6 and 7.7 copy; the actions never outside `Request.actions`; an unknown code as `processing_failed`; the link
 * limit in words; the F-23 notification line.
 */
@RunWith(RobolectricTestRunner::class)
class RequestFailureCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

  private fun error(code: String, message: String? = null, neededBytes: Long? = null, atSending: Boolean = false) =
    RequestError(code, message, downloaderVersion = null, atSending = atSending, neededBytes = neededBytes)

  private fun copy(
    code: String,
    kind: RequestKind,
    available: Set<RequestAction> = RequestAction.entries.toSet(),
    message: String? = null,
    maxLinkDurationS: Long? = 3_600,
    neededBytes: Long? = null,
    isVideo: Boolean? = null,
  ): FailureCopy = RequestFailureCopy.of(
    error(code, message, neededBytes),
    FailureContext(kind, if (kind == RequestKind.LINK) Site.YOUTUBE else null, available, maxLinkDurationS, isVideo = isVideo),
    resources,
  )

  /** One row of the table: the code, the kind, what `Request.actions` holds (WP-09's rule), and the copy expected. */
  private data class Row(
    val code: String,
    val kind: RequestKind,
    val available: Set<RequestAction>,
    val title: String,
    val body: String,
    val actions: List<RequestAction>,
    val message: String? = null,
  )

  private val link = RequestKind.LINK
  private val file = RequestKind.FILE

  /** Spec 8.6 and 7.7, row by row, for both kinds, with the actions `RequestActions` (WP-09) offers for each. */
  private val table = listOf(
    Row("refused_address", link, setOf(REMOVE), "This link can't be analyzed", "It points to a private or local address.", listOf(REMOVE)),
    Row(
      "invalid_url", link, setOf(REMOVE), "This link can't be analyzed",
      "Your computer couldn't accept it as a link to download.", listOf(REMOVE),
    ),
    Row(
      "unsupported_type", file, setOf(REMOVE), "Your computer can't take this file",
      "Its file type isn't supported. Supported: MP4, MOV, MKV, WebM, AVI, M4V, MP3, M4A, AAC, WAV, FLAC, OGG, Opus.",
      listOf(REMOVE),
    ),
    Row("too_large", file, setOf(TRY_AUDIO_ONLY, REMOVE), "Your computer can't take this file", "It is larger than your computer accepts.", listOf(TRY_AUDIO_ONLY, REMOVE)),
    Row("too_large", file, setOf(REMOVE), "Your computer can't take this file", "It is larger than your computer accepts.", listOf(REMOVE)),
    Row(
      "checksum_mismatch", file, setOf(TRY_AGAIN, REMOVE), "Your computer can't take this file",
      "It arrived damaged on your computer three times. Try sending it again.", listOf(TRY_AGAIN, REMOVE),
    ),
    Row(
      "download_forbidden", link, setOf(TRY_AGAIN, TRY_AUDIO_ONLY, OPEN_LINK, REMOVE), "The download failed",
      "The website refused it. This sometimes passes after a while, or with audio only.",
      listOf(TRY_AGAIN, TRY_AUDIO_ONLY, OPEN_LINK, REMOVE),
    ),
    Row(
      "download_unavailable", link, setOf(OPEN_LINK, REMOVE), "This video can't be downloaded",
      "It is private, removed, or needs a login, which PhonoTrainer can't do.", listOf(OPEN_LINK, REMOVE),
    ),
    Row(
      "unsupported_site", link, setOf(OPEN_LINK, REMOVE), "This website isn't supported",
      "Your computer doesn't know how to download from this site. Try a YouTube or X link.", listOf(OPEN_LINK, REMOVE),
    ),
    Row("playlist", link, setOf(OPEN_LINK, REMOVE), "That's a playlist", "Share a single video instead.", listOf(OPEN_LINK, REMOVE)),
    Row(
      "live_stream", link, setOf(TRY_AGAIN, OPEN_LINK, REMOVE), "Live streams can't be analyzed",
      "This video is live or hasn't started yet. Once it has ended and is saved as a video, try again.",
      listOf(TRY_AGAIN, OPEN_LINK, REMOVE),
    ),
    Row(
      "too_long", link, setOf(OPEN_LINK, SHOW_DETAILS, REMOVE), "This video is too long for your computer",
      "Your computer analyzes link videos up to 1 hour long. Share a shorter video; the limit can be changed on your " +
        "computer (see its README).",
      listOf(OPEN_LINK, SHOW_DETAILS, REMOVE),
    ),
    Row("network", link, setOf(TRY_AGAIN, REMOVE), "Your computer couldn't reach the website", "", listOf(TRY_AGAIN, REMOVE)),
    Row("network", file, setOf(TRY_AGAIN, REMOVE), "Your computer couldn't reach the website", "", listOf(TRY_AGAIN, REMOVE)),
    Row("no_space", link, setOf(TRY_AUDIO_ONLY, REMOVE), "Your computer is low on space", "", listOf(TRY_AUDIO_ONLY, REMOVE)),
    Row("no_space", file, setOf(TRY_AGAIN, REMOVE), "Your computer is low on space", "", listOf(TRY_AGAIN, REMOVE)),
    Row(
      "not_media", file, setOf(REMOVE), "Your computer couldn't read this file", "the file has no audio stream", listOf(REMOVE),
      message = "the file has no audio stream",
    ),
    Row("extract_failed", file, setOf(REMOVE), "Your computer couldn't read this file", "", listOf(REMOVE)),
    Row(
      "tool_missing", link, setOf(TRY_AGAIN, REMOVE), "Your computer can't process this yet",
      "It is missing a tool (ffmpeg). See the README on your computer.", listOf(TRY_AGAIN, REMOVE),
    ),
    Row(
      "interrupted", file, setOf(TRY_AGAIN, REMOVE), "Your computer stopped during the analysis",
      "Start PhonoTrainer again, then try again.", listOf(TRY_AGAIN, REMOVE),
    ),
    Row(
      "processing_failed", link, setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE), "The analysis failed", "Segment 3 failed",
      listOf(TRY_AGAIN, SHOW_DETAILS, REMOVE), message = "Segment 3 failed\nTraceback (most recent call last)",
    ),
    Row("cancelled", link, setOf(TRY_AGAIN, REMOVE), "Cancelled", "", listOf(TRY_AGAIN, REMOVE)),
    Row("cancelled", file, setOf(TRY_AGAIN, REMOVE), "Cancelled", "", listOf(TRY_AGAIN, REMOVE)),
    Row("source_gone", file, setOf(REMOVE), "Your computer no longer has this file", "Share it again.", listOf(REMOVE)),
    Row(
      "copy_no_space", file, setOf(TRY_AUDIO_ONLY, REMOVE), "Couldn't keep the file",
      "Not enough space on this phone to keep this video until your computer is reachable. Free 120 MB or choose Audio only.",
      listOf(TRY_AUDIO_ONLY, REMOVE),
    ),
    Row(
      "copy_no_space", file, setOf(REMOVE), "Couldn't keep the file",
      "Not enough space on this phone to keep this video. Free 120 MB, then share it again and choose Audio only.",
      listOf(REMOVE),
    ),
    Row(
      "copy_unreadable", file, setOf(REMOVE), "Couldn't keep the file",
      "PhonoTrainer couldn't read this file. Open it once in the app that has it, then share it again.", listOf(REMOVE),
    ),
    Row("copy_interrupted", file, setOf(REMOVE), "Couldn't keep the file", "The copy didn't finish. Share it again.", listOf(REMOVE)),
    Row("copy_missing", file, setOf(REMOVE), "The copy kept on this phone is missing", "Share the file again to analyze it.", listOf(REMOVE)),
  )

  @Test
  fun everyCodeOfTheSpecMapsToItsCopyForBothKinds() {
    table.forEach { row ->
      val copy = copy(row.code, row.kind, row.available, row.message, neededBytes = 120L * 1024 * 1024)
      assertWithMessage("${row.code} (${row.kind}, ${row.available})")
        .that(copy)
        .isEqualTo(FailureCopy(row.title, row.body, row.actions))
    }
  }

  @Test
  fun everyKnownCodeHasARowInTheTable() {
    assertThat(table.map { it.code }.toSet()).containsExactlyElementsIn(RequestFailureCopy.KNOWN_CODES)
  }

  @Test
  fun theActionsAreNeverOutsideWhatTheRequestOffers() {
    val codes = RequestFailureCopy.KNOWN_CODES + "some_new_code"
    val subsets = (0 until (1 shl RequestAction.entries.size)).map { mask ->
      RequestAction.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }
    codes.forEach { code ->
      RequestKind.entries.forEach { kind ->
        subsets.forEach { available ->
          val actions = copy(code, kind, available).actions
          assertWithMessage("$code ($kind) with $available").that(available).containsAtLeastElementsIn(actions)
          assertWithMessage("$code ($kind) with $available: every offered action is kept").that(actions.toSet()).isEqualTo(available)
          assertWithMessage("$code ($kind): Remove is last").that(actions.lastOrNull() == REMOVE || REMOVE !in available).isTrue()
        }
      }
    }
  }

  @Test
  fun anUnknownCodeReadsAsProcessingFailedWithTheFirstLineOfTheMessage() {
    val copy = copy("some_new_code", RequestKind.LINK, setOf(TRY_AGAIN, SHOW_DETAILS, REMOVE), message = "\n  First line  \nSecond")

    assertThat(copy).isEqualTo(FailureCopy("The analysis failed", "First line", listOf(TRY_AGAIN, SHOW_DETAILS, REMOVE)))
    assertThat(copy("some_new_code", RequestKind.FILE, setOf(REMOVE)).body).isEqualTo("Your computer couldn't finish the analysis.")
  }

  @Test
  fun theLinkLimitIsWrittenInWholeHoursOrMinutesAndTheUnknownLimitHasItsOwnBody() {
    fun body(limit: Long?) = copy("too_long", RequestKind.LINK, setOf(OPEN_LINK, SHOW_DETAILS, REMOVE), maxLinkDurationS = limit).body

    assertThat(body(3_600)).contains("up to 1 hour long.")
    assertThat(body(7_200)).contains("up to 2 hours long.")
    assertThat(body(5_400)).contains("up to 90 minutes long.")
    assertThat(body(null)).isEqualTo(
      "Your computer analyzes link videos up to the length set on your computer. Share a shorter video; the limit can " +
        "be changed on your computer (see its README).",
    )
    assertThat(RequestFailureCopy.limitWords(60, resources)).isEqualTo("1 minute")
    assertThat(RequestFailureCopy.limitWords(0, resources)).isNull()
  }

  @Test
  fun aTooLongLinkOffersNeitherTryAgainNorTryAudioOnly() {
    // SH-AC18: even if a state machine offered them, the 8.6 row of too_long lists neither, so only what it lists and
    // what the request really offers shows; WP-09's rule offers exactly Open the link, Show details and Remove.
    val copy = copy("too_long", RequestKind.LINK, setOf(OPEN_LINK, SHOW_DETAILS, REMOVE))

    assertThat(copy.actions).containsExactly(OPEN_LINK, SHOW_DETAILS, REMOVE).inOrder()
    assertThat(copy.title).isEqualTo("This video is too long for your computer")
  }

  @Test
  fun noSpaceForTheCopyNamesTheFileForAudioAndTheSpaceToFree() {
    val audio = copy("copy_no_space", RequestKind.FILE, setOf(REMOVE), neededBytes = 1_500L * 1024 * 1024, isVideo = false)
    val unknownSpace = copy("copy_no_space", RequestKind.FILE, setOf(TRY_AUDIO_ONLY, REMOVE), neededBytes = null)

    assertThat(audio.body).isEqualTo("Not enough space on this phone to keep this file until your computer is reachable. Free 1,500 MB.")
    assertThat(unknownSpace.body).contains("Free 1 MB or choose Audio only.")
  }

  @Test
  fun theJobNoSpaceBodyIsTheComputersFreeSpaceWhenKnown() {
    val copy = RequestFailureCopy.of(
      error("no_space"),
      FailureContext(RequestKind.LINK, Site.X, setOf(TRY_AUDIO_ONLY, REMOVE), null, freeBytes = 1_500_000_000),
      resources,
    )

    assertThat(copy.body).isEqualTo("Your computer has 1.4 GB free.")
  }

  @Test
  fun aFileTooLargeAtSendingNamesTheComputersLimit() {
    val copy = RequestFailureCopy.of(
      error("too_large", atSending = true),
      FailureContext(RequestKind.FILE, null, setOf(TRY_AUDIO_ONLY, REMOVE), null, maxUploadBytes = 2L * 1024 * 1024 * 1024),
      resources,
    )

    assertThat(copy.body).isEqualTo("It is larger than 2 GB.")
  }

  @Test
  fun theNotificationLineIsTheDownloadLineOrTheTitleWithTheTapHint() {
    assertThat(RequestFailureCopy.notificationLine(error("download_forbidden"), Site.YOUTUBE, resources))
      .isEqualTo("Couldn't download YouTube link — Tap to try again or try audio only.")
    assertThat(RequestFailureCopy.notificationLine(error("download_forbidden"), Site.YOUTUBE_SHORTS, resources))
      .isEqualTo("Couldn't download YouTube Shorts link — Tap to try again or try audio only.")
    assertThat(RequestFailureCopy.notificationLine(error("download_forbidden"), Site.X, resources))
      .isEqualTo("Couldn't download X link — Tap to try again or try audio only.")
    assertThat(RequestFailureCopy.notificationLine(error("download_forbidden"), Site.OTHER, resources))
      .isEqualTo("Couldn't download the link — Tap to try again or try audio only.")
    // F-23-AC4.
    assertThat(RequestFailureCopy.notificationLine(error("too_long"), Site.YOUTUBE, resources))
      .isEqualTo("This video is too long for your computer — Tap to see what you can do.")
    assertThat(RequestFailureCopy.notificationLine(error("interrupted"), null, resources))
      .isEqualTo("Your computer stopped during the analysis — Tap to see what you can do.")
    assertThat(RequestFailureCopy.notificationLine(error("some_new_code"), null, resources))
      .isEqualTo("The analysis failed — Tap to see what you can do.")
  }
}
