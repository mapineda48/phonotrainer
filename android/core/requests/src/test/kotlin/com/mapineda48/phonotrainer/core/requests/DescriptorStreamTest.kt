package com.mapineda48.phonotrainer.core.requests

import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.requests.android.DescriptorStream
import com.mapineda48.phonotrainer.core.requests.intake.ExtractorInput
import java.io.File
import java.io.FileInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A shared item's descriptor (architecture 8.3): after an extractor read it, the whole-file fallback starts again from
 * the first byte ("Sent the whole file"), even though the stream itself was never read.
 */
@RunWith(RobolectricTestRunner::class)
class DescriptorStreamTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun aRewindAfterTheExtractorReadTheDescriptorStartsAtTheFirstByte() {
    val bytes = ByteArray(10_000) { (it % 251).toByte() }
    val file = File(folder.root, "clip.mp4").apply { writeBytes(bytes) }
    DescriptorStream(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { source ->
      assertThat(source.seekable).isTrue()
      val input = source.extractorInput() as ExtractorInput.Descriptor
      // What MediaExtractor does to the shared offset of the descriptor it was given.
      FileInputStream(input.descriptor).read(ByteArray(4_000))

      assertThat(source.rewind()).isTrue()
      assertThat(source.stream().readBytes()).isEqualTo(bytes)
    }
  }
}
