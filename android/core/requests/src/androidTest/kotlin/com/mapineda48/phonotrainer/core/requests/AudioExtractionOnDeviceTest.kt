package com.mapineda48.phonotrainer.core.requests

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.mapineda48.phonotrainer.core.common.hash.Sha256
import com.mapineda48.phonotrainer.core.domain.model.FileOptions
import com.mapineda48.phonotrainer.core.domain.model.FileSource
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.requests.android.MediaAudioExtraction
import com.mapineda48.phonotrainer.core.requests.intake.ExtractorInput
import com.mapineda48.phonotrainer.core.testing.synthetic.SyntheticMedia
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Audio only" for a local video on the device (architecture 8.3): the AAC track of a real MP4 is remuxed into M4A
 * without re-encoding, from a file and from a provider's descriptor; a video without a track the phone can separate
 * gives nothing, and the intake keeps the whole file. The clip's length is read from the copy the intake keeps
 * (CR-WP22-1), whichever it is.
 */
@RunWith(AndroidJUnit4::class)
class AudioExtractionOnDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val made = mutableListOf<File>()

  @After
  fun clean() {
    made.forEach { it.delete() }
  }

  /** A 2 s MP4 made here with WP-11's [SyntheticMedia]: H.264 and, when [withAudio], an AAC tone. */
  private fun video(name: String, withAudio: Boolean): File = File(context.cacheDir, name).also {
    made += it
    val spec = SyntheticMedia.VideoSpec(fps = FPS, durationMs = LENGTH_MS, audioToneHz = TONE_HZ.takeIf { withAudio })
    SyntheticMedia.video(it, spec)
  }

  private fun tracks(file: File): List<String> {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      return (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
    } finally {
      extractor.release()
    }
  }

  @Test
  fun anAacTrackIsRemuxedWithoutReencodingFromAFileAndFromADescriptor() {
    val source = video("av.mp4", withAudio = true)
    assertThat(tracks(source)).containsExactly(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC)

    val fromFile = File(context.cacheDir, "from-file.part").also { made += it }
    assertThat(MediaAudioExtraction().extract(ExtractorInput.Path(source), fromFile) {}).isEqualTo("m4a")
    assertThat(tracks(fromFile)).containsExactly(MediaFormat.MIMETYPE_AUDIO_AAC)
    assertThat(fromFile.length()).isLessThan(source.length())

    val fromDescriptor = File(context.cacheDir, "from-descriptor.part").also { made += it }
    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      val input = ExtractorInput.Descriptor(descriptor.fileDescriptor, descriptor.statSize)
      assertThat(MediaAudioExtraction().extract(input, fromDescriptor) {}).isEqualTo("m4a")
    }
    assertThat(tracks(fromDescriptor)).containsExactly(MediaFormat.MIMETYPE_AUDIO_AAC)
  }

  @Test
  fun aVideoWithoutAnAudioTrackGivesNothing() {
    val silent = video("silent.mp4", withAudio = false)
    val target = File(context.cacheDir, "silent.part").also { made += it }

    assertThat(MediaAudioExtraction().extract(ExtractorInput.Path(silent), target) {}).isNull()
    assertThat(target.exists()).isFalse()
  }

  @Test
  fun theIntakeKeepsTheAudioTrackOrTheWholeFile() = runBlocking {
    DeviceQueue().use { queue ->
      video("intake-av.mp4", withAudio = true)
      val silent = video("intake-silent.mp4", withAudio = false)
      val options = FileOptions(audioOnly = true)
      fun source(name: String) = FileSource(TestMediaProvider.fileUri(context, name), null, null, null, persisted = false)

      // The expedited intake work copies each one, as it would after a share.
      val ids = queue.intake.add(listOf(source("intake-av.mp4"), source("intake-silent.mp4")), options, null).added
      ids.forEach { queue.awaitState(it, "WAITING", "COPY_FAILED") }

      val audio = queue.core.requests.get(ids[0].value)!!
      assertThat(audio.state).isEqualTo("WAITING")
      assertThat(audio.audioExtracted).isTrue()
      assertThat(audio.copyFile).endsWith("source.m4a")
      assertThat(tracks(queue.files.resolve(RequestId(audio.id), audio.copyFile)!!)).containsExactly(MediaFormat.MIMETYPE_AUDIO_AAC)
      val whole = queue.core.requests.get(ids[1].value)!!
      assertThat(whole.state).isEqualTo("WAITING")
      assertThat(whole.wholeFileFallback).isTrue()
      assertThat(whole.copyFile).endsWith("source.mp4")
      assertThat(whole.fileSha256).isEqualTo(Sha256.of(silent).sha256)
      // Both synthetic videos are 2 s long; the extracted audio and the whole file both say so.
      listOf(audio, whole).forEach { row ->
        assertWithMessage(row.copyFile).that(row.fileDurationMs).isAtLeast(SECONDS_2_LOW_MS)
        assertWithMessage(row.copyFile).that(row.fileDurationMs).isAtMost(SECONDS_2_HIGH_MS)
      }
    }
  }

  private companion object {
    const val FPS = 15
    const val LENGTH_MS = 2_000L
    const val TONE_HZ = 440.0

    // The encoders may add or trim a frame: 2 s within a few hundred milliseconds.
    const val SECONDS_2_LOW_MS = 1_500L
    const val SECONDS_2_HIGH_MS = 2_500L
  }
}
