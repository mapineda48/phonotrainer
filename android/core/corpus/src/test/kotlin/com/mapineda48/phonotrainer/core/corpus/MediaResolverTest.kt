package com.mapineda48.phonotrainer.core.corpus

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.database.library.dao.MediaFacts
import com.mapineda48.phonotrainer.core.database.library.dao.MediaFile
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import java.io.File
import org.junit.Test

/**
 * The media of a clip from its rows (architecture 7.4, 11.1, 11.6; spec 7.11): which files play, and the video words
 * of spec 7.11 from what the listing said and what the phone holds.
 */
class MediaResolverTest {
  @Test
  fun theWavsArePreferredAndTheOpusRenditionsStandIn() {
    val withWav = resolve(rows = listOf(ready("audio_mix", "AUDIO_MIX"), ready("audio_mix_opus", "AUDIO_MIX")))
    val opusOnly = resolve(rows = listOf(ready("audio_mix_opus", "AUDIO_MIX"), ready("audio_dialogue_opus", "AUDIO_DIALOGUE")))

    assertThat(withWav!!.audioMix.name).isEqualTo("audio_mix")
    assertThat(withWav.audioDialogue).isNull()
    assertThat(opusOnly!!.audioMix.name).isEqualTo("audio_mix_opus")
    assertThat(opusOnly.audioDialogue!!.name).isEqualTo("audio_dialogue_opus")
  }

  @Test
  fun aRowCountsOnlyWhenVerifiedAndOnThePhone() {
    val notVerified = resolve(rows = listOf(row("audio_mix", "AUDIO_MIX", localState = "DOWNLOADING", file = "x")))
    val notThere = resolve(rows = listOf(ready("audio_mix", "AUDIO_MIX")), onPhone = emptySet())

    assertThat(notVerified).isNull()
    assertThat(notThere).isNull()
  }

  @Test
  fun thePhoneVideoIsPreferredAndCarriesNoRotationOrOffset() {
    val media = resolve(
      facts = facts(rotation = 90, offset = 250),
      rows = listOf(ready("audio_mix", "AUDIO_MIX"), ready("original", "VIDEO"), ready("video_phone", "VIDEO"), ready("poster", "POSTER")),
    )!!

    assertThat(media.videoFile!!.name).isEqualTo("video_phone")
    assertThat(media.videoRotation).isEqualTo(0)
    assertThat(media.videoOffsetMs).isEqualTo(0)
    assertThat(media.videoCodec).isNull()
    assertThat(media.poster!!.name).isEqualTo("poster")
  }

  @Test
  fun unknownRotationAndOffsetAreZero() {
    val media = resolve(facts = facts(rotation = null, offset = null), rows = listOf(ready("audio_mix", "AUDIO_MIX"), ready("original", "VIDEO")))!!

    assertThat(media.videoRotation).isEqualTo(0)
    assertThat(media.videoOffsetMs).isEqualTo(0)
  }

  @Test
  fun theVideoWordsFollowTheListingAndThePhone() {
    val audio = listOf(ready("audio_mix", "AUDIO_MIX"))

    assertThat(video(facts(audioOnlyJob = true), audio + ready("original", "VIDEO"))).isEqualTo(VideoAvailability.AudioOnlyClip)
    assertThat(video(facts(mediaKind = "AUDIO"), audio)).isEqualTo(VideoAvailability.AudioOnlyClip)
    assertThat(video(facts(mediaKind = "NONE"), audio)).isEqualTo(VideoAvailability.AudioOnlyClip)
    assertThat(video(facts(mediaKind = null), audio)).isEqualTo(VideoAvailability.AudioOnlyClip)
    assertThat(video(facts(), audio)).isEqualTo(VideoAvailability.NotAvailableFromComputer)
    assertThat(video(facts(), audio + ready("original", "VIDEO"))).isEqualTo(VideoAvailability.OnThisPhone)
    assertThat(video(facts(), audio + row("original", "VIDEO", localState = "DOWNLOADING", remoteBytes = 200, partBytes = 50)))
      .isEqualTo(VideoAvailability.Downloading(25))
    assertThat(video(facts(), audio + row("original", "VIDEO", localState = "ABSENT")))
      .isEqualTo(VideoAvailability.NotOnThisPhone)
    assertThat(video(facts(), audio + row("video_phone", "VIDEO", localState = "ABSENT", remoteStatus = "PREPARING")))
      .isEqualTo(VideoAvailability.GettingReadyOnComputer)
    assertThat(video(facts(), audio + row("original", "VIDEO", localState = "ABSENT", remoteStatus = "FAILED")))
      .isEqualTo(VideoAvailability.NotAvailableFromComputer)
  }

  @Test
  fun aDownloadNeverReadsAsDoneBeforeItIsVerified() {
    val full = row("original", "VIDEO", localState = "DOWNLOADING", remoteBytes = 100, partBytes = 100)
    val unknownSize = row("original", "VIDEO", localState = "DOWNLOADING", remoteBytes = null, partBytes = 100)

    assertThat(video(facts(), listOf(ready("audio_mix", "AUDIO_MIX"), full))).isEqualTo(VideoAvailability.Downloading(99))
    assertThat(video(facts(), listOf(ready("audio_mix", "AUDIO_MIX"), unknownSize))).isEqualTo(VideoAvailability.Downloading(0))
  }

  private fun video(facts: MediaFacts, rows: List<MediaFile>): VideoAvailability = resolve(facts, rows)!!.videoAvailability

  private fun resolve(
    facts: MediaFacts = facts(),
    rows: List<MediaFile>,
    onPhone: Set<String>? = null,
  ) = MediaResolver.resolve(facts, rows) { path -> if (onPhone == null || path in onPhone) File(path) else null }

  private fun facts(
    mediaKind: String? = "VIDEO",
    audioOnlyJob: Boolean = false,
    rotation: Int? = 0,
    offset: Long? = 0,
  ) = MediaFacts(
    jobId = "a", title = "Clip a", mediaKind = mediaKind, audioOnlyJob = audioOnlyJob, hasDialogue = false,
    videoCodec = "h264", videoWidth = 1920, videoHeight = 1080, videoRotation = rotation, videoOffsetMs = offset,
    deletedOnComputer = false,
  )

  private fun ready(name: String, role: String) = row(name, role, localState = "READY", file = name)

  private fun row(
    name: String,
    role: String,
    localState: String,
    file: String? = null,
    remoteStatus: String = "READY",
    remoteBytes: Long? = null,
    partBytes: Long? = null,
  ) = MediaFile(name, role, remoteStatus, remoteBytes, localState, file, partBytes)
}
