package com.mapineda48.phonotrainer.core.requests.rules

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.network.api.ApiResult
import org.junit.Test

/** The small pure rules of the queue: types and names (8.3), titles (spec 8.2), answer classes (8.1), the size rule. */
class QueueRulesTest {
  @Test
  fun theCopyExtensionComesFromTheClosedListByNameOrType() {
    assertThat(MediaTypes.extensionOf("VID_0001.MP4", null)).isEqualTo("mp4")
    assertThat(MediaTypes.extensionOf("clip", "video/quicktime")).isEqualTo("mov")
    assertThat(MediaTypes.extensionOf(null, "audio/mpeg; charset=binary")).isEqualTo("mp3")
    // A hostile name never names the file: only its suffix is read, and only from the closed list.
    assertThat(MediaTypes.extensionOf("../../databases/library.db", "video/mp4")).isEqualTo("mp4")
    assertThat(MediaTypes.extensionOf("../../databases/library.db", null)).isNull()
    // AMR and 3GP voice notes are refused (SH-AC10).
    assertThat(MediaTypes.extensionOf("note.amr", "audio/amr")).isNull()
    assertThat(MediaTypes.extensionOf("note.3gp", "video/3gpp")).isNull()
  }

  @Test
  fun aDisplayNameIsReducedToASafeBaseName() {
    assertThat(MediaTypes.safeDisplayName("../../databases/library.db")).isEqualTo("library.db")
    assertThat(MediaTypes.safeDisplayName("folder\\sub\\clip.mp4")).isEqualTo("clip.mp4")
    assertThat(MediaTypes.safeDisplayName("a\u202Ebc\u0000.mp4")).isEqualTo("abc.mp4")
    assertThat(MediaTypes.safeDisplayName("x".repeat(500) + ".mp4")!!.length).isEqualTo(MediaTypes.MAX_NAME_CHARS)
    assertThat(MediaTypes.safeDisplayName("..")).isNull()
    assertThat(MediaTypes.safeDisplayName("/")).isNull()
  }

  @Test
  fun theUploadNameKeepsTheStemWithTheCopysExtension() {
    assertThat(MediaTypes.uploadName("VID_0001.mp4", "m4a")).isEqualTo("VID_0001.m4a")
    assertThat(MediaTypes.uploadName("../../x/.hidden.mp4", "mp4")).isEqualTo("hidden.mp4")
    assertThat(MediaTypes.uploadName(null, "mp4")).isEqualTo("upload.mp4")
    val long = MediaTypes.uploadName("y".repeat(300) + ".mp4", "mp4")
    assertThat(long.length).isAtMost(MediaTypes.MAX_NAME_CHARS)
    assertThat(long).endsWith(".mp4")
  }

  @Test
  fun videosAreToldFromAudio() {
    assertThat(MediaTypes.isVideo("mp4", null)).isTrue()
    assertThat(MediaTypes.isVideo("mp4", "audio/mp4")).isFalse()
    assertThat(MediaTypes.isVideo("m4a", null)).isFalse()
    assertThat(MediaTypes.isVideo(null, "video/webm")).isTrue()
  }

  @Test
  fun aSharedLinksTitleIsItsSubjectOrTheTextAroundIt() {
    assertThat(Titles.hint("Great scene https://youtu.be/abc?si=xyz", null, "https://youtu.be/abc?si=xyz")).isEqualTo("Great scene")
    assertThat(Titles.hint("https://youtu.be/abc", "  A walk\nin the park ", "https://youtu.be/abc")).isEqualTo("A walk in the park")
    assertThat(Titles.hint("https://youtu.be/abc", null, "https://youtu.be/abc")).isNull()
    assertThat(Titles.hint("Watch - https://x.com/a/status/1 |", null, "https://x.com/a/status/1")).isEqualTo("Watch")
    assertThat(Titles.hint("https://a.example/1 https://a.example/2", null, "https://a.example/1")).isNull()
    assertThat(Titles.clean("a\u0007b\u2028c" + "z".repeat(300)).length).isEqualTo(Titles.MAX_CHARS)
  }

  @Test
  fun answersFallIntoThePhoneClassesOfServerContract81() {
    assertThat(Outcomes.refused(401, "credential_rejected", "")).isEqualTo(Outcome.Blocking("credential_rejected"))
    assertThat(Outcomes.refused(426, "app_too_old", "")).isEqualTo(Outcome.Blocking("app_too_old"))
    assertThat(Outcomes.refused(403, "phone_access_off", "")).isEqualTo(Outcome.Blocking("phone_access_off"))
    assertThat(Outcomes.refused(507, "no_space", "")).isEqualTo(Outcome.UploadsBlocked)
    assertThat(Outcomes.refused(409, "upload_busy", "busy")).isEqualTo(Outcome.Transient("upload_busy", "busy"))
    assertThat(Outcomes.refused(503, "cannot_check_link", "")).isEqualTo(Outcome.Transient("cannot_check_link", null))
    assertThat(Outcomes.refused(502, "", "")).isEqualTo(Outcome.Transient(null, null))
    assertThat(Outcomes.refused(400, "refused_address", "private")).isEqualTo(Outcome.Permanent("refused_address", "private"))
    assertThat(Outcomes.refused(413, "too_large", "")).isEqualTo(Outcome.Permanent("too_large", null))
    assertThat(Outcomes.refused(410, "source_gone", "")).isEqualTo(Outcome.Permanent("source_gone", null))
    assertThat(Outcomes.refused(409, "key_conflict", "")).isEqualTo(Outcome.Defect("key_conflict", null))
    assertThat(Outcomes.of(ApiResult.Failed(AppError.Computer.Unreachable(ProbeOutcome.NoAnswer(30))))).isEqualTo(Outcome.Unreachable)
    assertThat(Outcomes.of(ApiResult.Failed(AppError.Computer.CredentialRejected))).isEqualTo(Outcome.Blocking("credential_rejected"))
    assertThat(Outcomes.of(ApiResult.Failed(AppError.Computer.Refused("refused_address", "")))).isEqualTo(Outcome.Permanent("refused_address", null))
  }

  @Test
  fun aFileStartsInTheBackgroundOnlyWhenItFitsOneRun() {
    // Nothing measured yet: 200 MB fit (architecture 8.5).
    assertThat(BackgroundWindow.fits(200_000_000, null)).isTrue()
    assertThat(BackgroundWindow.fits(600_000_000, null)).isFalse()
    // 2 MB/s for 360 s is 720 MB.
    assertThat(BackgroundWindow.fits(600_000_000, 2_000_000.0)).isTrue()
    assertThat(BackgroundWindow.fits(800_000_000, 2_000_000.0)).isFalse()
    assertThat(BackgroundWindow.fits(800_000_000, 2_000_000.0, resumable = true)).isTrue()
  }
}
