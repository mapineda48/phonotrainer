package com.mapineda48.phonotrainer.core.ui.component

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.designsystem.icons.PhonoIcons
import com.mapineda48.phonotrainer.core.domain.model.ClipAvailability
import com.mapineda48.phonotrainer.core.domain.model.ClipOrigin
import com.mapineda48.phonotrainer.core.domain.model.ClipSource
import com.mapineda48.phonotrainer.core.domain.model.ClipSummary
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.domain.model.remote.SourceKind
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The words of clip cards and rows (spec F-03 item 7, F-04, 7.11; design 9.18). */
@RunWith(RobolectricTestRunner::class)
class ClipCardCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

  private val clip = ClipSummary(
    id = JobId("20261004-133519-a77c"),
    title = "Coffee shop small talk",
    durationS = 261.0,
    words = 691,
    changeCount = 14,
    source = ClipSource(SourceKind.LINK, Site.YOUTUBE, null, null),
    availability = ClipAvailability.OnThisPhone,
    video = VideoAvailability.OnThisPhone,
    kept = true,
    origin = ClipOrigin.ACTIVE,
    olderRules = false,
    shortsSeen = 12,
    shortsTotal = 15,
    addedAt = Instant.EPOCH,
  )

  @Test
  fun lengthsReadInMinutesAndSeconds() {
    assertThat(ClipCardCopy.duration(261.0, resources)).isEqualTo("4 min 21 s")
    assertThat(ClipCardCopy.duration(240.2, resources)).isEqualTo("4 min")
    assertThat(ClipCardCopy.duration(44.6, resources)).isEqualTo("45 s")
    assertThat(ClipCardCopy.duration(3_780.0, resources)).isEqualTo("1 h 3 min")
    assertThat(ClipCardCopy.duration(-3.0, resources)).isEqualTo("0 s")
  }

  @Test
  fun theRowMetaLineHasLengthWordsAndChangesAndSaysAudioOnly() {
    assertThat(ClipCardCopy.rowMeta(clip, resources)).isEqualTo("4 min 21 s · 691 words · 14 changes")
    val radio = clip.copy(durationS = 614.0, words = 1_489, changeCount = 1, video = VideoAvailability.AudioOnlyClip)
    assertThat(ClipCardCopy.rowMeta(radio, resources)).isEqualTo("10 min 14 s · Audio only · 1,489 words · 1 change")
    assertThat(ClipCardCopy.rowMeta(clip.copy(durationS = null, words = null, changeCount = null), resources)).isEmpty()
  }

  @Test
  fun theCardMetaLineCountsShorts() {
    assertThat(ClipCardCopy.cardMeta(clip, resources)).isEqualTo("4 min 21 s · 15 shorts")
    assertThat(ClipCardCopy.cardMeta(clip.copy(shortsTotal = 1), resources)).isEqualTo("4 min 21 s · 1 short")
  }

  @Test
  fun everySourceHasItsWordAndIcon() {
    val table = listOf(
      ClipSource(SourceKind.LINK, Site.YOUTUBE, null, null),
      ClipSource(SourceKind.LINK, Site.YOUTUBE_SHORTS, null, null),
      ClipSource(SourceKind.LINK, Site.X, null, null),
      ClipSource(SourceKind.LINK, Site.OTHER, null, null),
      ClipSource(SourceKind.LINK, null, null, null),
      ClipSource(SourceKind.UPLOAD, null, null, "a.mp4"),
      ClipSource(SourceKind.PATH, null, null, null),
      ClipSource(SourceKind.IMPORT, null, null, null),
      ClipSource(SourceKind.UNKNOWN, null, null, null),
    ).map { ClipCardCopy.source(it, resources) to ClipCardCopy.sourceIcon(it) }
    assertThat(table).containsExactly(
      "YouTube" to PhonoIcons.SiteYoutube,
      "YouTube" to PhonoIcons.SiteYoutube,
      "X" to PhonoIcons.SiteX,
      "Website" to PhonoIcons.SiteOther,
      "Website" to PhonoIcons.SiteOther,
      "Uploaded file" to PhonoIcons.SitePhoneFile,
      "Computer" to PhonoIcons.SiteComputer,
      "Computer" to PhonoIcons.SiteComputer,
      "Computer" to PhonoIcons.SiteComputer,
    ).inOrder()
  }

  @Test
  fun everyAvailabilityHasItsWords() {
    val words = listOf(
      ClipAvailability.OnThisPhone,
      ClipAvailability.Bringing(45),
      ClipAvailability.NotOnThisPhone,
      ClipAvailability.GettingReadyOnComputer,
      ClipAvailability.DownloadPaused(PauseReason.BUDGET_REACHED),
      ClipAvailability.DownloadPaused(PauseReason.FREE_SPACE_FLOOR),
      ClipAvailability.DownloadPaused(PauseReason.NOT_CONNECTED),
      ClipAvailability.NeedsDownloadingAgain,
    ).map { ClipCardCopy.availability(it, resources) }
    assertThat(words).containsExactly(
      "On this phone",
      "Bringing to this phone 45 %",
      "Not on this phone",
      "Getting ready on your computer",
      "Download paused: storage budget reached",
      "Download paused: your phone is almost full",
      "Download paused. Downloads continue the next time this phone reaches your computer.",
      "Needs downloading again",
    ).inOrder()
  }

  @Test
  fun everyVideoAvailabilityHasItsWords() {
    val words = listOf(
      VideoAvailability.OnThisPhone,
      VideoAvailability.Downloading(40),
      VideoAvailability.NotOnThisPhone,
      VideoAvailability.GettingReadyOnComputer,
      VideoAvailability.NotAvailableFromComputer,
      VideoAvailability.CannotPlayOnThisPhone,
      VideoAvailability.AudioOnlyClip,
    ).map { ClipCardCopy.video(it, resources) }
    assertThat(words).containsExactly(
      "Video on this phone",
      "Video downloading 40 %",
      "Video not on this phone",
      "Video getting ready on your computer",
      "Video not available from your computer",
      "This video can't play on this phone",
      "Audio only",
    ).inOrder()
  }

  @Test
  fun theProgressHintSaysWhereToContinue() {
    assertThat(ClipCardCopy.seen(clip, resources)).isEqualTo("You've seen 12 of 15 shorts")
    assertThat(ClipCardCopy.seen(clip.copy(shortsSeen = 0), resources)).isEqualTo("15 shorts")
    assertThat(ClipCardCopy.seen(clip.copy(shortsSeen = 1, shortsTotal = 1), resources)).isEqualTo("You've seen 1 of 1 short")
    assertThat(ClipCardCopy.seen(clip.copy(shortsTotal = 0), resources)).isNull()
  }

  @Test
  fun tagsAppearOnlyWhenTheyApply() {
    assertThat(ClipCardCopy.origin(ClipOrigin.ACTIVE, resources)).isNull()
    assertThat(ClipCardCopy.origin(ClipOrigin.FORGOTTEN_COMPUTER, resources)).isEqualTo("From a computer you forgot")
    assertThat(ClipCardCopy.origin(ClipOrigin.OTHER_LIBRARY, resources)).isEqualTo("From another library")
    assertThat(ClipCardCopy.olderRules(clip, resources)).isNull()
    assertThat(ClipCardCopy.olderRules(clip.copy(olderRules = true), resources))
      .isEqualTo("Older rules: re-analyze on your computer to update")
    assertThat(ClipCardCopy.kept(clip, resources)).isEqualTo("Kept")
    assertThat(ClipCardCopy.kept(clip.copy(kept = false), resources)).isNull()
  }

  @Test
  fun theRowSentenceIsTheRowsWordsInReadingOrder() {
    assertThat(ClipCardCopy.rowSentence(clip, resources)).isEqualTo(
      "Coffee shop small talk, 4 min 21 s · 691 words · 14 changes, YouTube, On this phone, Kept, " +
        "You've seen 12 of 15 shorts",
    )
    val other = clip.copy(kept = false, olderRules = true, origin = ClipOrigin.OTHER_LIBRARY, shortsTotal = 0)
    assertThat(ClipCardCopy.rowSentence(other, resources)).isEqualTo(
      "Coffee shop small talk, 4 min 21 s · 691 words · 14 changes, YouTube, On this phone, " +
        "Older rules: re-analyze on your computer to update, From another library",
    )
  }

  @Test
  fun sizesUseDecimalUnits() {
    val sizes = listOf(1L, 512L, 640_000L, 2_100_000L, 2_000_000L, 17_000_000L, 1_200_000_000L)
      .map { ClipCardCopy.bytes(it, resources) }
    assertThat(sizes).containsExactly("1 byte", "512 bytes", "640 KB", "2.1 MB", "2 MB", "17 MB", "1.2 GB").inOrder()
  }
}
