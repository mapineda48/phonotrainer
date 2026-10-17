package com.mapineda48.phonotrainer.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness
import com.mapineda48.phonotrainer.core.player.support.Noisy
import com.mapineda48.phonotrainer.core.player.support.PlayerHarness.Media
import com.mapineda48.phonotrainer.core.player.support.onMain
import com.mapineda48.phonotrainer.core.player.support.waitFor
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The feed's pool on real codecs (work-plan WP-11 Proof; architecture 11.1, 11.2): merged playback from the short's
 * start with the WAV as the only audio, speed with pitch kept, track switches, loops, the track fallback, the video
 * fallbacks, unplugged earphones and the release of codecs.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FeedPlayerPoolTest {
  private val harness = PlayerHarness()
  private val job = JobId("20261004-133519-a77c")
  private lateinit var pool: FeedPlayerPool

  @After
  fun tearDown() {
    if (::pool.isInitialized) onMain { pool.release() }
    harness.release()
  }

  private fun start(vararg items: com.mapineda48.phonotrainer.core.domain.model.FeedItem, index: Int = 0, autoplay: Boolean = true) {
    if (!::pool.isInitialized) pool = harness.pool()
    onMain {
      pool.setQueue(items.toList(), index)
      pool.settle(index, autoplay)
    }
  }

  private fun position(): Long = onMain { pool.positionMs() }

  private fun waitPlayedPast(ms: Long) = waitFor("playback past $ms ms") { pool.positionMs() > ms }

  @Test
  fun mergedPlaybackStartsAtTheShortsStartAndPlaysTheWavNotTheVideosOwnAudio() {
    harness.clip(job, true)
    pool = harness.pool()
    onMain { pool.setQueue(listOf(harness.item(harness.media(job), 2_000, 6_000)), 0) }
    onMain { pool.settle(0, autoplay = false) }
    waitFor("the short prepared") { pool.currentPlayer()?.playbackState == Player.STATE_READY }
    assertThat(position()).isEqualTo(2_000)

    onMain { pool.play() }
    waitPlayedPast(2_400)

    val tracks = onMain { pool.currentPlayer()!!.currentTracks }
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    assertThat(audio).hasSize(1)
    assertThat(audio.single().isSelected).isTrue()
    assertThat(audio.single().mediaTrackGroup.id).startsWith("0:") // the first child: the analysed audio
    assertThat(audio.single().getTrackFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_RAW)
    assertThat(tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)).isTrue()
    // What reaches the speaker is the WAV's 440 Hz, not the video's own 1 kHz.
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
    with(onMain { pool.state.value }) {
      assertThat(playing).isTrue()
      assertThat(videoShown).isTrue()
      assertThat(error).isNull()
      assertThat(trackFallback).isNull()
    }
  }

  @Test
  fun speedKeepsThePitchAndCarriesOverToTheNextShort() {
    harness.clip(job, true)
    val media = harness.media(job)
    start(harness.item(media, 0, 4_000), harness.item(media, 4_000, 8_000, firstSegment = 1), autoplay = false)
    onMain {
      pool.setSpeed(0.5f)
      pool.play()
    }
    waitPlayedPast(200)
    val before = position()
    Thread.sleep(1_000)
    val advanced = position() - before

    assertThat(advanced).isIn(com.google.common.collect.Range.closed(350L, 650L)) // about 0.5 s per second
    assertThat(onMain { pool.currentPlayer()!!.playbackParameters }).isEqualTo(PlaybackParameters(0.5f, 1f))
    // After the speed change the tone is still 440 Hz: no octave down (220 Hz).
    assertThat(harness.heard(pool).dominantAfter(listOf(220.0, Media.MIX_HZ, 880.0))).isEqualTo(Media.MIX_HZ)

    // F-06-AC8: the next short plays at 0.5× too.
    onMain { pool.settle(1, autoplay = true) }
    waitFor("the next short playing") { pool.state.value.index == 1 && pool.positionMs() > 4_100 }
    assertThat(onMain { pool.currentPlayer()!!.playbackParameters.speed }).isEqualTo(0.5f)
    assertThat(onMain { pool.state.value.speed }).isEqualTo(0.5f)
  }

  @Test
  fun holdToSlowDownReturnsToTheChosenSpeed() {
    harness.clip(job, true)
    start(harness.item(harness.media(job), 0, 6_000))
    onMain { pool.setSpeed(0.75f) }

    onMain { pool.holdSlow(true) }
    assertThat(onMain { pool.currentPlayer()!!.playbackParameters.speed }).isEqualTo(0.5f)
    assertThat(onMain { pool.state.value.holdSlow }).isTrue()

    onMain { pool.holdSlow(false) }
    assertThat(onMain { pool.currentPlayer()!!.playbackParameters.speed }).isEqualTo(0.75f)
    assertThat(onMain { pool.state.value.speed }).isEqualTo(0.75f)
  }

  @Test
  fun aTrackSwitchKeepsThePosition() {
    harness.clip(job, true)
    start(harness.item(harness.media(job), 0, 8_000))
    waitPlayedPast(1_500)
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)

    val before = position()
    onMain { pool.setTrack(Track.DIALOGUE) }
    val after = position()

    assertThat(after - before).isIn(com.google.common.collect.Range.closed(-50L, 100L))
    waitFor("playing again after the switch") { pool.positionMs() > after + 300 }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.DIALOGUE_HZ)
    with(onMain { pool.state.value }) {
      assertThat(track).isEqualTo(Track.DIALOGUE)
      assertThat(playing).isTrue()
      assertThat(trackFallback).isNull()
    }
  }

  @Test
  fun loopShortWrapsAndKeepsPlaying() {
    harness.clip(job, true)
    start(harness.item(harness.media(job), 1_000, 2_200))
    onMain { pool.setLoop(LoopMode.SHORT) }

    val positions = sample(2_500)

    assertThat(positions.zipWithNext().any { (a, b) -> b < a - 500 }).isTrue() // wrapped to the start
    assertThat(positions.all { it in 1_000..2_200 }).isTrue()
    with(onMain { pool.state.value }) {
      assertThat(ended).isFalse()
      assertThat(playing).isTrue()
      assertThat(loop).isEqualTo(LoopMode.SHORT)
    }
  }

  @Test
  fun loopPhraseStaysInsideThePhrase() {
    harness.clip(job, true, 1_200L to 1_900L, 2_200L to 3_800L)
    start(harness.item(harness.media(job), 1_000, 4_000, firstSegment = 0, lastSegment = 1), autoplay = false)
    onMain { pool.seekTo(1_300) }
    waitFor("the clip's phrases") { harness.content.data.isNotEmpty() }
    onMain {
      pool.setLoop(LoopMode.PHRASE)
      pool.play()
    }

    val positions = sample(2_500)

    assertThat(positions.max()).isAtMost(1_900 + LATE_MS)
    assertThat(positions.zipWithNext().any { (a, b) -> b < a - 300 }).isTrue()
    assertThat(positions.drop(5).min()).isAtLeast(1_200 - 20)
  }

  @Test
  fun replayPhrasePlaysTheCurrentPhraseThenPauses() {
    harness.clip(job, true, 1_200L to 1_900L, 2_200L to 3_800L)
    start(harness.item(harness.media(job), 1_000, 4_000, firstSegment = 0, lastSegment = 1), autoplay = false)
    onMain { pool.seekTo(2_500) }
    Thread.sleep(300) // the clip's phrases load

    onMain { pool.replayPhrase() }
    waitFor("the replay to start") { pool.positionMs() in 2_200..2_400 && pool.state.value.playing }
    waitFor("the replay to stop at the phrase end", timeoutMs = 4_000) { !pool.state.value.playing }

    assertThat(position()).isIn(com.google.common.collect.Range.closed(3_800L - 50, 3_800L + LATE_MS))
    assertThat(onMain { pool.state.value.ended }).isFalse()
  }

  @Test
  fun playSpanPlaysTheWordAndThenPauses() {
    harness.clip(job, true)
    start(harness.item(harness.media(job), 0, 6_000), autoplay = false)

    onMain { pool.playSpan(ClipSpan(job, 3_000, 3_100, null)) } // stretched to 0.25 s: 2 925 to 3 175
    waitFor("the span to play and stop", timeoutMs = 3_000) { !pool.state.value.playing && pool.positionMs() > 3_100 }

    assertThat(position()).isIn(com.google.common.collect.Range.closed(3_175L - 30, 3_175L + LATE_MS))
  }

  @Test
  fun aShortThatEndsStopsAndReplayingItOnceWorks() {
    // F-07-AC7, player side: the end is reported; "Replay it once" is a seek to the start and play.
    harness.clip(job, true)
    val item = harness.item(harness.media(job), 1_000, 2_000)
    start(item)
    waitFor("the short to end", timeoutMs = 4_000) { pool.state.value.ended }
    with(onMain { pool.state.value }) {
      assertThat(playing).isFalse()
      assertThat(ended).isTrue()
    }

    onMain {
      pool.seekTo(item.short.playStartMs)
      pool.play()
    }
    waitFor("the replay") { pool.state.value.playing && !pool.state.value.ended }
    waitFor("the replay to end", timeoutMs = 4_000) { pool.state.value.ended }
    assertThat(onMain { pool.state.value.playing }).isFalse()
  }

  @Test
  fun becomingNoisyPauses() {
    harness.clip(job, true)
    start(harness.item(harness.media(job), 0, 8_000))
    waitPlayedPast(500)

    Noisy.send()

    waitFor("the pause after the earphones were unplugged", timeoutMs = 4_000) { !pool.state.value.playing }
    val paused = position()
    Thread.sleep(500)
    assertThat(position()).isEqualTo(paused) // and it does not resume by itself
    assertThat(onMain { harness.arbiter.owner.value }).isNull()
  }

  @Test
  fun aVideoOfACodecThisPhoneCannotPlayPlaysAsAudio() {
    harness.clip(job, true)
    start(harness.item(harness.media(job, codec = "unsupported-codec"), 0, 6_000))
    waitPlayedPast(800)

    with(onMain { pool.state.value }) {
      assertThat(videoShown).isFalse()
      assertThat(playing).isTrue()
    }
    assertThat(onMain { pool.currentPlayer()!!.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }).isFalse()
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
  }

  @Test
  fun aVideoDecodeErrorFallsBackToAudioAtTheSamePosition() {
    harness.clip(job, true)
    pool = harness.pool(failVideoAfterMs = 800)
    val media = harness.media(job)
    start(harness.item(media, 0, 8_000), harness.item(media, 8_000, 11_000, firstSegment = 1))
    waitFor("the decode failure", timeoutMs = 6_000) { pool.state.value.error == AppError.Media.VideoDecode }
    val atFailure = position()

    with(onMain { pool.state.value }) {
      assertThat(videoShown).isFalse()
      assertThat(playing).isTrue()
    }
    assertThat(atFailure).isAtLeast(600)
    waitFor("playback going on") { pool.positionMs() > atFailure + 500 }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
    // The clip's other shorts play audio only too.
    onMain { pool.settle(1, autoplay = true) }
    waitFor("the next short") { pool.positionMs() > 8_200 }
    with(onMain { pool.state.value }) {
      assertThat(videoShown).isFalse()
      assertThat(error).isEqualTo(AppError.Media.VideoDecode)
    }
  }

  @Test
  fun missingAudioSaysTheShortCouldNotPlay() {
    harness.clip(job, true)
    val gone = java.io.File(harness.context.cacheDir, "missing.wav")
    start(harness.item(harness.media(job, mix = gone, dialogue = null, video = null), 0, 4_000))

    waitFor("the audio error") { pool.state.value.error == AppError.Media.AudioMissing }
    assertThat(onMain { pool.state.value.playing }).isFalse()
  }

  @Test
  fun dialogueChosenAndNotOnThePhonePlaysTheMixWithItsLine() {
    harness.clip(job, dialogueSeparation = true)
    pool = harness.pool()
    onMain { pool.setTrack(Track.DIALOGUE) }
    start(harness.item(harness.media(job, dialogue = null), 0, 6_000))

    waitFor("the fallback line") { pool.state.value.trackFallback == TrackFallback.DIALOGUE_NOT_ON_PHONE }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
  }

  @Test
  fun dialogueChosenForAClipWithoutOnePlaysTheMixWithItsLine() {
    harness.clip(job, dialogueSeparation = false)
    pool = harness.pool()
    onMain { pool.setTrack(Track.DIALOGUE) }
    start(harness.item(harness.media(job, dialogue = null), 0, 6_000))

    waitFor("the fallback line") { pool.state.value.trackFallback == TrackFallback.NO_DIALOGUE_TRACK }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
  }

  @Test
  fun mixChosenWithOnlyTheDialogueOnThePhonePlaysTheDialogueWithItsLine() {
    harness.clip(job, dialogueSeparation = true)
    start(harness.item(harness.media(job, mix = Media.dialogue, dialogue = Media.dialogue), 0, 6_000))

    waitFor("the fallback line") { pool.state.value.trackFallback == TrackFallback.MIX_NOT_ON_PHONE }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.DIALOGUE_HZ)
  }

  @Test
  fun anArrivedDialogueTrackPlaysFromTheNextShortOfThatClip() {
    // F-06-AC13, player side.
    harness.clip(job, dialogueSeparation = true)
    pool = harness.pool()
    onMain { pool.setTrack(Track.DIALOGUE) }
    val waiting = harness.media(job, dialogue = null)
    start(harness.item(waiting, 0, 5_000), harness.item(waiting, 5_000, 10_000, firstSegment = 1))
    waitFor("the fallback line") { pool.state.value.trackFallback == TrackFallback.DIALOGUE_NOT_ON_PHONE }
    waitPlayedPast(800)

    val arrived = harness.media(job)
    onMain { pool.setQueue(listOf(harness.item(arrived, 0, 5_000), harness.item(arrived, 5_000, 10_000, firstSegment = 1)), 0) }

    // The short that is playing does not switch by itself.
    Thread.sleep(400)
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.MIX_HZ)
    assertThat(onMain { pool.state.value.trackFallback }).isEqualTo(TrackFallback.DIALOGUE_NOT_ON_PHONE)

    onMain { pool.settle(1, autoplay = true) }
    waitFor("the next short") { pool.state.value.index == 1 && pool.positionMs() > 5_300 }
    assertThat(harness.heard(pool).dominantAfter(Media.TONES)).isEqualTo(Media.DIALOGUE_HZ)
    assertThat(onMain { pool.state.value.trackFallback }).isNull()
  }

  @Test
  fun thePoolReleasesItsCodecsOnStopAndWhenWatchIsHiddenAndPlaysAgainOnReturn() {
    harness.clip(job, true)
    val media = harness.media(job)
    start(
      harness.item(media, 0, 3_000),
      harness.item(media, 3_000, 6_000, firstSegment = 1),
      harness.item(media, 6_000, 9_000, firstSegment = 2),
      index = 1,
    )
    waitPlayedPast(3_600)
    waitFor("three prepared players") { (0..2).all { pool.playerAt(it)?.playbackState == Player.STATE_READY } }
    assertThat(harness.decoderCounter.live).isEqualTo(3)

    // The app leaves the foreground (process ON_STOP): every codec released, the position kept.
    harness.foreground.value = false
    waitFor("every player stopped") { (0..2).all { pool.playerAt(it)?.playbackState == Player.STATE_IDLE } }
    val kept = position()
    assertThat(onMain { pool.state.value.playing }).isFalse()
    waitFor("no live decoder") { harness.decoderCounter.live == 0 }

    harness.foreground.value = true
    waitFor("prepared again") { (0..2).all { pool.playerAt(it)?.playbackState == Player.STATE_READY } }
    assertThat(onMain { pool.state.value.playing }).isFalse() // nothing resumes by itself
    assertThat(position()).isIn(com.google.common.collect.Range.closed(kept - KEPT_MS, kept + KEPT_MS))
    onMain { pool.play() }
    waitPlayedPast(kept + 300)

    // Watch hidden by a tab switch: the same.
    onMain { pool.setVisible(false) }
    waitFor("stopped while hidden") { (0..2).all { pool.playerAt(it)?.playbackState == Player.STATE_IDLE } }
    waitFor("no live decoder while hidden") { harness.decoderCounter.live == 0 }
    val hiddenAt = position()
    onMain { pool.setVisible(true) }
    waitFor("prepared on return") { (0..2).all { pool.playerAt(it)?.playbackState == Player.STATE_READY } }
    assertThat(position()).isIn(com.google.common.collect.Range.closed(hiddenAt - KEPT_MS, hiddenAt + KEPT_MS))
    onMain { pool.play() }
    waitPlayedPast(hiddenAt + 300)
    assertThat(harness.decoderCounter.live).isEqualTo(3)
  }

  @Test
  fun anAudioOnlyClipPlaysWithoutAVideoFrame() {
    harness.clip(job, true)
    start(harness.item(harness.media(job, video = null, codec = null), 0, 4_000))
    waitPlayedPast(500)

    with(onMain { pool.state.value }) {
      assertThat(videoShown).isFalse()
      assertThat(firstFrameRendered).isFalse()
      assertThat(playing).isTrue()
    }
    assertThat(harness.decoderCounter.live).isEqualTo(0)
  }

  /** Positions read every 20 ms for [ms]. */
  private fun sample(ms: Long): List<Long> {
    val out = ArrayList<Long>()
    val end = android.os.SystemClock.uptimeMillis() + ms
    while (android.os.SystemClock.uptimeMillis() < end) {
      out += position()
      Thread.sleep(20)
    }
    return out
  }

  private companion object {
    /** How far past a boundary a position read on the test thread may be before the player message stops it. */
    const val LATE_MS = 120L

    /** A stopped player keeps its position to within a few milliseconds of the last read while it played. */
    const val KEPT_MS = 40L
  }
}
