package com.mapineda48.phonotrainer.feature.share

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.FileRefusal
import com.mapineda48.phonotrainer.core.domain.model.FileRefusalReason
import com.mapineda48.phonotrainer.core.domain.model.Site
import com.mapineda48.phonotrainer.feature.share.intake.SharedKind
import com.mapineda48.phonotrainer.feature.share.intake.SharedMedia
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sheet's words (spec 8.2 to 8.4.1, 7.7) and numbers. */
@RunWith(RobolectricTestRunner::class)
class ShareCopyTest {
  private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

  @Test
  fun sitesHaveTheirWords() {
    assertThat(Site.entries.map { ShareCopy.siteTitle(it, resources) })
      .containsExactly("YouTube link", "YouTube Shorts link", "X link", "Website link").inOrder()
  }

  @Test
  fun aPartialMultipleShareSaysHowManyWereAddedAndNamesTheRefused() {
    val amr = FileRefusal("voice.amr", FileRefusalReason.UNSUPPORTED_TYPE, null)

    assertThat(ShareCopy.partial(2, 3, listOf(amr), resources))
      .isEqualTo("2 of 3 added. 1 file type isn't supported: voice.amr.")
    assertThat(
      ShareCopy.partial(
        1,
        6,
        listOf(
          amr,
          FileRefusal("note.3gp", FileRefusalReason.UNSUPPORTED_TYPE, null),
          FileRefusal("film.mkv", FileRefusalReason.TOO_LARGE, null),
          FileRefusal(null, FileRefusalReason.UNREADABLE, null),
          FileRefusal(null, FileRefusalReason.TOO_MANY, null),
        ),
        resources,
      ),
    ).isEqualTo(
      "1 of 6 added. 2 file types aren't supported: voice.amr, note.3gp. 1 file is larger than your computer accepts: " +
        "film.mkv. 1 file couldn't be read. 1 file over the limit of 10 wasn't added.",
    )
  }

  @Test
  fun eachRefusalHasTheSpecsMessage() {
    fun of(reason: FileRefusalReason, needed: Long? = null, video: Boolean = true) =
      ShareCopy.refusal(FileRefusal("clip.mp4", reason, needed), video, 2L * 1024 * 1024 * 1024, resources)

    assertThat(of(FileRefusalReason.UNSUPPORTED_TYPE)).isEqualTo(
      "This file type can't be analyzed. Supported: MP4, MOV, MKV, WebM, AVI, M4V, MP3, M4A, AAC, WAV, FLAC, OGG, Opus.",
    )
    assertThat(of(FileRefusalReason.TOO_LARGE)).isEqualTo("This file is larger than your computer accepts (2 GB).")
    assertThat(of(FileRefusalReason.NO_SPACE, needed = 900L * 1024 * 1024)).isEqualTo(
      "Not enough space on this phone to keep this video until your computer is reachable. Free 900 MB or choose " +
        "Audio only.",
    )
    assertThat(of(FileRefusalReason.NO_SPACE, needed = 5L * 1024 * 1024, video = false)).isEqualTo(
      "Not enough space on this phone to keep this file until your computer is reachable. Free 5 MB.",
    )
    assertThat(of(FileRefusalReason.UNREADABLE)).isEqualTo(
      "PhonoTrainer couldn't read this file. Open it once in the app that has it, then share it again.",
    )
    assertThat(of(FileRefusalReason.OWN_PROVIDER)).isEqualTo(of(FileRefusalReason.UNREADABLE))
    assertThat(of(FileRefusalReason.TOO_MANY)).isEqualTo("Only 10 files can be added at once.")
  }

  @Test
  fun theFilesAreSummarizedByKindSizeAndDuration() {
    assertThat(ShareCopy.oneFileLine(SharedKind.VIDEO, 48_500_000, 42_000, resources)).isEqualTo("Video · 46 MB · 0:42")
    assertThat(ShareCopy.oneFileLine(SharedKind.AUDIO, null, null, resources)).isEqualTo("Audio")
    assertThat(ShareCopy.manyFilesLine(3, 3, 0, 432_013_312, resources)).isEqualTo("3 videos · 412 MB")
    assertThat(ShareCopy.manyFilesLine(2, 0, 2, null, resources)).isEqualTo("2 audio files")
    assertThat(ShareCopy.manyFilesLine(3, 2, 1, 1_610_612_736, resources)).isEqualTo("3 files · 1.5 GB")
  }

  @Test
  fun numbersAreWrittenShort() {
    assertThat(ShareFormat.bytes(2L * 1024 * 1024 * 1024)).isEqualTo("2 GB")
    assertThat(ShareFormat.bytes(4_404_019)).isEqualTo("4.2 MB")
    assertThat(ShareFormat.bytes(850 * 1024)).isEqualTo("850 KB")
    assertThat(ShareFormat.megabytes(1_200L * 1024 * 1024 + 1)).isEqualTo("1,201")
    assertThat(ShareFormat.duration(42_000)).isEqualTo("0:42")
    assertThat(ShareFormat.duration(3_723_000)).isEqualTo("1:02:03")
  }

  @Test
  fun theLinkIsShortenedWithoutItsSchemeAndShownWholeOnDemand() {
    val link = "https://www.youtube.com/watch?v=Ab3xYz09Qw1&list=PL123"

    assertThat(ShareFormat.link(link, full = false)).isEqualTo("youtube.com/watch?v=Ab3x…")
    assertThat(ShareFormat.isShortened(link)).isTrue()
    assertThat(ShareFormat.link(link, full = true)).isEqualTo(link)
    assertThat(ShareFormat.link("https://youtu.be/abc", full = false)).isEqualTo("youtu.be/abc")
    assertThat(ShareFormat.isShortened("https://youtu.be/abc")).isFalse()
  }

  @Test
  fun theAcceptedTypesAreTheComputersAndNamesAreCleaned() {
    assertThat(SharedMedia.suffixOf("clip.MP4", null)).isEqualTo("mp4")
    assertThat(SharedMedia.suffixOf("voice.amr", "audio/amr")).isNull()
    assertThat(SharedMedia.suffixOf("recording", "audio/ogg; codecs=opus")).isEqualTo("ogg")
    assertThat(SharedMedia.suffixOf(null, "video/3gpp")).isNull()
    assertThat(SharedMedia.kindOf("webm", null)).isEqualTo(SharedKind.VIDEO)
    assertThat(SharedMedia.kindOf("webm", "audio/webm")).isEqualTo(SharedKind.AUDIO)
    assertThat(SharedMedia.safeName("/a/b/../c\u0007lip‮.mp4 ")).isEqualTo("clip.mp4")
    assertThat(SharedMedia.safeName("..")).isNull()
    assertThat(SharedMedia.safeName("x".repeat(300))).hasLength(SharedMedia.MAX_NAME_CHARS)
  }
}
