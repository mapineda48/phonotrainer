package com.mapineda48.phonotrainer.core.common.files

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The layout of architecture 4.6 and the confinement check every opened path goes through. */
class FileLayoutTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val job = JobId("20261004-133519-a77c")
  private val request = RequestId("5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11")

  @Test
  fun pathsFollowTheLayout() {
    val files = folder.root
    assertThat(FileLayout.artifact(files, job, "audio_mix", "0123456789abcdef", "wav").relativeTo(files).path)
      .isEqualTo("clips/20261004-133519-a77c/audio_mix.01234567.wav")
    assertThat(FileLayout.artifactPart(files, job, "analysis", "rev1").relativeTo(files).path)
      .isEqualTo("clips/20261004-133519-a77c/.analysis.rev1.part")
    assertThat(FileLayout.requestCopy(files, request, "mp4").relativeTo(files).path)
      .isEqualTo("requests/5a0c4c5e-0b6e-4bd6-9f53-1f1b0bbf7c11/source.mp4")
    assertThat(FileLayout.requestCopyPart(files, request).name).isEqualTo(".copy.part")
  }

  @Test
  fun onlyPathsInsideTheRootAreConfined() {
    val clips = FileLayout.clipsRoot(folder.root)
    assertThat(FileLayout.isConfined(FileLayout.artifact(folder.root, job, "analysis", "ab", "json"), clips)).isTrue()
    assertThat(FileLayout.isConfined(File(clips, "../requests/x"), clips)).isFalse()
    assertThat(FileLayout.isConfined(File(clips, "a/../../outside"), clips)).isFalse()
    assertThat(FileLayout.isConfined(clips, clips)).isFalse()
    assertThat(FileLayout.isConfined(File(folder.root, "clips-evil/x"), clips)).isFalse()
  }
}
