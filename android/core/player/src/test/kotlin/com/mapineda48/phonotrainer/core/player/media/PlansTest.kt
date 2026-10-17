package com.mapineda48.phonotrainer.core.player.media

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.domain.model.TrackFallback
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import java.io.File
import org.junit.Test

/** Architecture 11.1 (track fallback, video modes) and 11.5 (audible spans), spec D-47, F-06-AC13: the pure parts. */
class PlansTest {
  private val job = JobId("job")
  private val mix = File("audio_mix.wav")
  private val dialogue = File("audio_dialogue.wav")
  private val video = File("original.mp4")
  private val short = ShortInfo(ShortId(job, 2), 3, 9, 4, playStartMs = 10_750, playEndMs = 21_400, spotlight = null, markedWords = 0)

  private fun media(
    audioMix: File = mix,
    audioDialogue: File? = dialogue,
    videoFile: File? = video,
    codec: String? = "h264",
    offsetMs: Long = 0,
    availability: VideoAvailability = VideoAvailability.OnThisPhone,
  ) = ClipMedia(job, audioMix, audioDialogue, videoFile, codec, 1280, 720, 0, offsetMs, null, availability)

  private fun plan(
    media: ClipMedia = media(),
    chosen: Track = Track.MIX,
    hasDialogueTrack: Boolean? = true,
    videoFailed: Boolean = false,
    canPlay: Boolean = true,
  ) = ShortPlanner.plan(FeedItem(short, media), chosen, hasDialogueTrack, videoFailed) { canPlay }

  @Test
  fun theChosenTrackPlaysWhenItIsOnThePhone() {
    assertThat(plan(chosen = Track.MIX).let { Triple(it.audio, it.playedTrack, it.trackFallback) })
      .isEqualTo(Triple(mix, Track.MIX, null))
    assertThat(plan(chosen = Track.DIALOGUE).let { Triple(it.audio, it.playedTrack, it.trackFallback) })
      .isEqualTo(Triple(dialogue, Track.DIALOGUE, null))
  }

  @Test
  fun dialogueChosenAndNotDownloadedYetPlaysTheMix() {
    val plan = plan(media(audioDialogue = null), chosen = Track.DIALOGUE, hasDialogueTrack = true)

    assertThat(plan.audio).isEqualTo(mix)
    assertThat(plan.playedTrack).isEqualTo(Track.MIX)
    assertThat(plan.trackFallback).isEqualTo(TrackFallback.DIALOGUE_NOT_ON_PHONE)
  }

  @Test
  fun dialogueChosenForAClipAnalysedWithoutOnePlaysTheMix() {
    val plan = plan(media(audioDialogue = null), chosen = Track.DIALOGUE, hasDialogueTrack = false)

    assertThat(plan.audio).isEqualTo(mix)
    assertThat(plan.trackFallback).isEqualTo(TrackFallback.NO_DIALOGUE_TRACK)
  }

  @Test
  fun untilTheClipsAnalysisIsReadTheLineWaits() {
    assertThat(plan(media(audioDialogue = null), chosen = Track.DIALOGUE, hasDialogueTrack = null).trackFallback).isNull()
  }

  @Test
  fun mixChosenWithOnlyTheDialogueOnThePhonePlaysTheDialogue() {
    // WP-10: when only the dialogue track is on the phone, ClipMedia.audioMix holds the dialogue file too.
    val plan = plan(media(audioMix = dialogue, audioDialogue = dialogue), chosen = Track.MIX)

    assertThat(plan.audio).isEqualTo(dialogue)
    assertThat(plan.playedTrack).isEqualTo(Track.DIALOGUE)
    assertThat(plan.trackFallback).isEqualTo(TrackFallback.MIX_NOT_ON_PHONE)
  }

  @Test
  fun freshMediaWithTheArrivedTrackPlaysItWithNoLine() {
    // F-06-AC13, player side: the same short re-planned once its dialogue file arrived.
    val before = plan(media(audioDialogue = null), chosen = Track.DIALOGUE)
    val after = plan(media(), chosen = Track.DIALOGUE)

    assertThat(after.sameMedia(before)).isFalse()
    assertThat(after.audio).isEqualTo(dialogue)
    assertThat(after.trackFallback).isNull()
  }

  @Test
  fun onlyTheLineDiffersBetweenTheTwoDialogueCases() {
    val waiting = plan(media(audioDialogue = null), chosen = Track.DIALOGUE, hasDialogueTrack = null)
    val known = plan(media(audioDialogue = null), chosen = Track.DIALOGUE, hasDialogueTrack = true)

    assertThat(known.sameMedia(waiting)).isTrue()
    assertThat(known).isNotEqualTo(waiting)
  }

  @Test
  fun videoModes() {
    assertThat(plan().videoMode).isEqualTo(VideoMode.MERGED)
    assertThat(plan(media(offsetMs = 500)).videoMode).isEqualTo(VideoMode.MERGED)
    assertThat(plan(media(offsetMs = -300)).videoMode).isEqualTo(VideoMode.FOLLOWER)
    assertThat(plan(media(videoFile = null)).videoMode).isEqualTo(VideoMode.NONE)
    assertThat(plan(videoFailed = true).videoMode).isEqualTo(VideoMode.NONE)
    assertThat(plan(canPlay = false).videoMode).isEqualTo(VideoMode.NONE)
    assertThat(plan(media(availability = VideoAvailability.CannotPlayOnThisPhone)).videoMode).isEqualTo(VideoMode.NONE)
    assertThat(plan(media(availability = VideoAvailability.AudioOnlyClip)).videoMode).isEqualTo(VideoMode.NONE)
    assertThat(plan(canPlay = false).video).isNull()
    assertThat(plan().video).isEqualTo(video)
  }

  @Test
  fun playBoundsComeFromTheShort() {
    val plan = plan()

    assertThat(plan.playStartMs to plan.playEndMs).isEqualTo(10_750L to 21_400L)
    assertThat(plan.shortId).isEqualTo(short.id)
  }

  @Test
  fun spansShorterThanAQuarterSecondAreStretchedAroundTheirMiddle() {
    assertThat(SpanBounds.audible(1_000, 1_400)).isEqualTo(1_000L..1_400L)
    assertThat(SpanBounds.audible(1_000, 1_100)).isEqualTo(925L..1_175L)
    assertThat(SpanBounds.audible(20, 60)).isEqualTo(0L..250L)
    assertThat(SpanBounds.audible(500, 400)).isEqualTo(375L..625L)
  }

  @Test
  fun phrasesOfAShortAndThePhraseBeingPlayed() {
    val data = ClipData(
      job,
      "rev",
      ClipMeta(30.0, "en", 3, null, true, null),
      listOf(phrase(0, 0.0, 4.0), phrase(1, 4.3, 11.0), phrase(2, 11.8, 14.0), phrase(3, 14.2, 20.0), phrase(4, 23.5, 25.0)),
      ClipDataSummary(emptyMap(), emptyMap()),
    )
    val phrases = PhraseSpans.of(PhraseSpans.ofClip(data), short) // segments 2 to 4

    assertThat(phrases).containsExactly(PhraseSpan(11_800, 14_000), PhraseSpan(14_200, 20_000), PhraseSpan(23_500, 25_000))
      .inOrder()
    assertThat(PhraseSpans.at(phrases, 11_000)).isEqualTo(PhraseSpan(11_800, 14_000)) // the lead-in: the first phrase
    assertThat(PhraseSpans.at(phrases, 15_000)).isEqualTo(PhraseSpan(14_200, 20_000))
    assertThat(PhraseSpans.at(phrases, 21_000)).isEqualTo(PhraseSpan(14_200, 20_000)) // a pause: the phrase before
    assertThat(PhraseSpans.at(phrases, 23_480)).isEqualTo(PhraseSpan(23_500, 25_000)) // a seek just before a start
    assertThat(PhraseSpans.at(emptyList(), 0)).isNull()
  }

  private fun phrase(index: Int, start: Double, end: Double) =
    Phrase(index, start, end, "", emptyList(), null, null, null, null, null, emptyList())
}
