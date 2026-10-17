package com.mapineda48.phonotrainer.core.player.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Builds every Media3 player of this module; tests on the device hand in renderers that fail or record. */
internal fun interface ExoPlayerFactory {
  fun create(): ExoPlayer
}

/**
 * The production players (architecture 11.1, 11.4): speech for media, audio focus left to the arbiter
 * (`handleAudioFocus = false`), earphones unplugged pause (`setHandleAudioBecomingNoisy(true)`), confined to the main
 * looper like every caller of this module.
 */
@OptIn(UnstableApi::class)
internal class DefaultExoPlayerFactory(
  private val context: Context,
  private val renderers: RenderersFactory,
) : ExoPlayerFactory {
  @Inject constructor(@ApplicationContext context: Context) : this(context, DefaultRenderersFactory(context))

  override fun create(): ExoPlayer = ExoPlayer.Builder(context, renderers)
    .setAudioAttributes(SPEECH, false)
    .setHandleAudioBecomingNoisy(true)
    .setLooper(Looper.getMainLooper())
    .build()

  companion object {
    val SPEECH: AudioAttributes = AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
      .build()
  }
}

/**
 * The media sources of a short and of a span (architecture 11.1, 11.5), all from local files: the feed never streams
 * (spec D-12).
 */
@OptIn(UnstableApi::class)
internal class ClipSources @Inject constructor() {
  private val files = FileDataSource.Factory()
  private val all = ProgressiveMediaSource.Factory(files, DefaultExtractorsFactory())
  private val videoTracksOnly = ProgressiveMediaSource.Factory(files, VideoOnlyExtractorsFactory(DefaultExtractorsFactory()))

  /** The analysed audio (WAV or Opus) of one track. */
  fun audio(file: File): MediaSource = all.createMediaSource(item(file))

  /**
   * The video file with its own audio left out at the extractor, so the analysed audio is the only audio the player
   * can select (the explicit audio override of architecture 11.1, Android review M3).
   */
  fun videoOnly(file: File): MediaSource = videoTracksOnly.createMediaSource(item(file))

  /**
   * One short: the analysed audio **first** (the merged timeline and clock are the first child's), merged with the
   * video when there is one; a positive [ShortPlan.videoOffsetMs] skips that much of the video, so analysed time t
   * shows video time t + offset. The whole is clipped to the short's play bounds, so position 0 is
   * [ShortPlan.playStartMs].
   */
  fun short(plan: ShortPlan): MediaSource {
    val audio = audio(plan.audio)
    val merged = if (plan.videoMode == VideoMode.MERGED && plan.video != null) {
      val video = videoOnly(plan.video).let {
        if (plan.videoOffsetMs > 0) ClippingMediaSource.Builder(it).setStartPositionMs(plan.videoOffsetMs).build() else it
      }
      MergingMediaSource(true, false, audio, video)
    } else {
      audio
    }
    return ClippingMediaSource.Builder(merged)
      .setStartPositionMs(plan.playStartMs)
      .setEndPositionMs(plan.playEndMs)
      .build()
  }

  /** A span of one track's audio, already stretched to be audible ([SpanBounds]). */
  fun span(file: File, startMs: Long, endMs: Long): MediaSource =
    ClippingMediaSource.Builder(audio(file)).setStartPositionMs(startMs).setEndPositionMs(endMs).build()

  private fun item(file: File): MediaItem = MediaItem.fromUri(Uri.fromFile(file))
}

/** Every extractor of [delegate], keeping only video tracks. */
@OptIn(UnstableApi::class)
internal class VideoOnlyExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
  override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::VideoOnlyExtractor).toTypedArray()

  override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
    delegate.createExtractors(uri, responseHeaders).map(::VideoOnlyExtractor).toTypedArray()
}

@OptIn(UnstableApi::class)
private class VideoOnlyExtractor(private val delegate: Extractor) : Extractor {
  override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

  override fun init(output: ExtractorOutput) = delegate.init(VideoOnlyOutput(output))

  override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

  override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

  override fun release() = delegate.release()

  override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class VideoOnlyOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
  override fun track(id: Int, type: Int): TrackOutput =
    if (type == C.TRACK_TYPE_VIDEO) delegate.track(id, type) else DiscardingTrackOutput()

  override fun endTracks() = delegate.endTracks()

  override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}
