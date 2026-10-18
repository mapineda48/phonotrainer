package com.mapineda48.phonotrainer.feature.connect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Copy diagnostics" (F-22-AC4; architecture 20.3, security review L-7): the computer's address becomes its class, this
 * phone's address, the ids and every name stay out, and every other fact is in words.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTextTest {
  private val resources = ApplicationProvider.getApplicationContext<Context>().resources

  @Test
  fun theTextSaysEveryFactInWords() {
    val text = DiagnosticsText.build(ConnectSamples.detailsNoAnswer, ConnectSamples.platform, resources)
    assertThat(text.lines()).containsExactly(
      "PhonoTrainer connection diagnostics",
      "App: 1.0",
      "Android: 16 (API 36)",
      "Status: not reachable",
      "Last check: No answer after 3 s, 1 min ago",
      "Computer's address: not a home-network address",
      "Address changed: 1 day ago",
      "API version: 6",
      "Waiting on this phone: 2 · On the computer: 1 · Clips to bring: 3",
      "Background: limited by Android",
      "Video decoders: AV1 up to 3840 × 2160 · hardware; H.264 up to 4096 × 2176 · hardware; VP9 up to 1920 × 1080 · software",
    ).inOrder()
  }

  @Test
  fun aPrivateAddressIsSaidAsItsBlockOnly() {
    val home = ConnectSamples.detailsConnected.let { it.copy(diagnostics = it.diagnostics.copy(address = HostPort("192.168.0.0", 8000))) }
    val text = DiagnosticsText.build(home, ConnectSamples.platform, resources)
    assertThat(text).contains("Computer's address: private 192.168/16")
    assertThat(text).doesNotContain("192.168.0.0")
    assertThat(text).doesNotContain("8000")
  }

  @Test
  fun noAddressIdNameOrCredentialEverReachesTheText() {
    val states = listOf(
      ConnectSamples.detailsConnected,
      ConnectSamples.detailsPhoneAccessOff,
      ConnectSamples.detailsNoAnswer,
      ConnectSamples.detailsNotConnected,
      ConnectSamples.detailsConnected.copy(status = ComputerStatus.DifferentComputer("Study desk")),
    )
    states.forEach { state ->
      val text = DiagnosticsText.build(state, ConnectSamples.platform, resources)
      listOf("192.0.2.", "a1b2c3d4", "Study desk", ":8000").forEach { assertThat(text).doesNotContain(it) }
    }
  }

  @Test
  fun aPhoneThatNeverCheckedSaysSo() {
    val text = DiagnosticsText.build(ConnectSamples.detailsNotConnected.copy(decoders = emptyList()), ConnectSamples.platform.copy(appVersion = null), resources)
    assertThat(text).contains("Status: not connected to a computer")
    assertThat(text).contains("Last check: none yet")
    assertThat(text).contains("Computer's address: none stored")
    assertThat(text).contains("Address changed: no")
    assertThat(text).contains("API version: unknown")
    assertThat(text).contains("Video decoders: none reported")
    assertThat(text).doesNotContain("App:")
  }

  @Test
  fun everyStatusHasNameFreeWords() {
    val statuses = listOf(
      ComputerStatus.NotConnected, ComputerStatus.Offline, ComputerStatus.NotOnWifi, ComputerStatus.Looking,
      ComputerStatus.Checking, ComputerStatus.Connected("Study desk", 0, 0, false),
      ComputerStatus.NotReachable("Study desk", null, false, ProbeOutcome.Refused), ComputerStatus.DifferentComputer("Study desk"),
      ComputerStatus.ConnectAgain, ComputerStatus.ComputerTooOld, ComputerStatus.AppTooOld,
    )
    val words = statuses.map { resources.getString(DiagnosticsText.statusWords(it)) }
    assertThat(words).containsNoDuplicates()
    words.forEach { assertThat(it).doesNotContain("Study desk") }
  }

  @Test
  fun codecsHaveTheirEverydayNames() {
    assertThat(DecoderWords.codec("video/av01")).isEqualTo("AV1")
    assertThat(DecoderWords.codec("video/avc")).isEqualTo("H.264")
    assertThat(DecoderWords.codec("video/hevc")).isEqualTo("HEVC")
    assertThat(DecoderWords.codec("video/x-vnd.on2.vp9")).isEqualTo("VP9")
    assertThat(DecoderWords.codec("video/x-vnd.on2.vp8")).isEqualTo("VP8")
    assertThat(DecoderWords.codec("video/mp4v-es")).isEqualTo("video/mp4v-es")
  }
}
