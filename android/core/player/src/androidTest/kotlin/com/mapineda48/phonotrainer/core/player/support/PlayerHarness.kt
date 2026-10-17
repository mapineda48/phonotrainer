package com.mapineda48.phonotrainer.core.player.support

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.platform.app.InstrumentationRegistry
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.VideoAvailability
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.decoder.MediaCodecDecoderCapabilities
import com.mapineda48.phonotrainer.core.player.decoder.PlatformVideoDecoders
import com.mapineda48.phonotrainer.core.player.feed.FeedPlayerPool
import com.mapineda48.phonotrainer.core.player.media.ClipSources
import com.mapineda48.phonotrainer.core.player.media.DefaultExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.media.ExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.span.ExoSpanPlayer
import com.mapineda48.phonotrainer.core.testing.fake.corpus.FakeClipContentRepository
import com.mapineda48.phonotrainer.core.testing.synthetic.SyntheticMedia
import com.mapineda48.phonotrainer.core.testing.synthetic.ToneMeter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.fail

/** Runs [block] on the main thread, where every player of this module lives, and returns its result. */
fun <T> onMain(block: () -> T): T {
  if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
  var result: Result<T>? = null
  InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
  return result!!.getOrThrow()
}

/** Polls [condition] on the main thread until it holds, failing after [timeoutMs]. */
fun waitFor(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
  val end = SystemClock.uptimeMillis() + timeoutMs
  while (!onMain(condition)) {
    if (SystemClock.uptimeMillis() > end) fail("timed out waiting for $what")
    Thread.sleep(POLL_MS)
  }
}

private const val POLL_MS = 10L

/**
 * Records the PCM the player sends to the audio output, **after** the speed change (a tee placed after Sonic), so a
 * test can tell which tone was heard and whether the pitch was kept.
 */
@OptIn(UnstableApi::class)
class PcmCapture : TeeAudioProcessor.AudioBufferSink {
  @Volatile var sampleRate = 0
    private set
  private var channels = 1
  private val samples = ShortArray(CAPACITY)
  private var written = 0L

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    synchronized(this) {
      sampleRate = sampleRateHz
      channels = channelCount.coerceAtLeast(1)
    }
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    synchronized(this) {
      while (data.remaining() >= 2 * channels) {
        var sum = 0
        repeat(channels) { sum += data.short }
        samples[(written % CAPACITY).toInt()] = (sum / channels).toShort()
        written++
      }
    }
  }

  /** How many frames have gone through so far. */
  fun frames(): Long = synchronized(this) { written }

  /** The last [ms] of audio (mono). */
  fun recent(ms: Long): ShortArray = synchronized(this) {
    val count = (sampleRate * ms / MS).toInt().coerceAtMost(written.toInt()).coerceAtMost(CAPACITY)
    ShortArray(count) { i -> samples[((written - count + i) % CAPACITY).toInt()] }
  }

  /** Which of [candidates] is the loudest tone in the last [ms]; there must be that much audio already. */
  fun dominant(candidates: List<Double>, ms: Long = 300): Double {
    val samples = recent(ms)
    if (sampleRate == 0 || samples.size < sampleRate * ms / MS / 2) fail("only ${samples.size} samples reached the output")
    return ToneMeter.dominant(samples, sampleRate, candidates)
  }

  /** Waits until [ms] of new audio has gone through, then answers [dominant]. */
  fun dominantAfter(candidates: List<Double>, ms: Long = 300): Double {
    val end = SystemClock.uptimeMillis() + WAIT_MS
    while (sampleRate == 0) {
      if (SystemClock.uptimeMillis() > end) fail("no audio reached the output")
      Thread.sleep(POLL_MS)
    }
    val start = frames()
    while (frames() - start < sampleRate * ms / MS) {
      if (SystemClock.uptimeMillis() > end) fail("no audio reached the output")
      Thread.sleep(POLL_MS)
    }
    return dominant(candidates, ms)
  }

  private companion object {
    const val CAPACITY = 48_000 * 4
    const val MS = 1000L
    const val WAIT_MS = 5_000L
  }
}

/** The default chain with a tee after the speed change. */
@OptIn(UnstableApi::class)
private class TeeAfterSpeed(capture: PcmCapture) : AudioProcessorChain {
  private val delegate = DefaultAudioSink.DefaultAudioProcessorChain()
  private val processors: Array<AudioProcessor> = delegate.audioProcessors + TeeAudioProcessor(capture)

  override fun getAudioProcessors(): Array<AudioProcessor> = processors

  override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
    delegate.applyPlaybackParameters(playbackParameters)

  override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = delegate.applySkipSilenceEnabled(skipSilenceEnabled)

  override fun getMediaDuration(playoutDuration: Long): Long = delegate.getMediaDuration(playoutDuration)

  override fun getSkippedOutputFrameCount(): Long = delegate.skippedOutputFrameCount
}

/** A video renderer that fails like a decoder that gives up, once playback has gone [afterMs] into the short. */
@OptIn(UnstableApi::class)
private class FailingVideoRenderer(
  context: Context,
  selector: MediaCodecSelector,
  joiningMs: Long,
  fallback: Boolean,
  handler: Handler?,
  listener: VideoRendererEventListener?,
  private val afterMs: Long,
) : MediaCodecVideoRenderer(context, selector, joiningMs, fallback, handler, listener, DROPPED_FRAMES) {
  private var startedAtUs = -1L

  override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
    super.render(positionUs, elapsedRealtimeUs)
    if (state != Renderer.STATE_STARTED) return
    if (startedAtUs < 0) startedAtUs = positionUs
    if (positionUs - startedAtUs > afterMs * MICROS) {
      throw createRendererException(IllegalStateException("synthetic decoder failure"), null, PlaybackException.ERROR_CODE_DECODING_FAILED)
    }
  }

  private companion object {
    const val DROPPED_FRAMES = 50
    const val MICROS = 1000L
  }
}

@OptIn(UnstableApi::class)
private class TestRenderers(
  context: Context,
  private val capture: PcmCapture,
  private val failVideoAfterMs: Long?,
) : DefaultRenderersFactory(context) {
  override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
    DefaultAudioSink.Builder(context).setAudioProcessorChain(TeeAfterSpeed(capture)).build()

  override fun buildVideoRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: android.os.Handler,
    eventListener: VideoRendererEventListener,
    allowedVideoJoiningTimeMs: Long,
    out: ArrayList<Renderer>,
  ) {
    val after = failVideoAfterMs
    if (after == null) {
      super.buildVideoRenderers(
        context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
        allowedVideoJoiningTimeMs, out,
      )
    } else {
      out += FailingVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, after)
    }
  }
}

/** Counts live video decoders across every player a factory made (Android review M2). */
@OptIn(UnstableApi::class)
class DecoderCounter : AnalyticsListener {
  private val liveCount = java.util.concurrent.atomic.AtomicInteger()
  private val peakCount = java.util.concurrent.atomic.AtomicInteger()
  val live: Int get() = liveCount.get()
  val peak: Int get() = peakCount.get()

  override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
    val now = liveCount.incrementAndGet()
    peakCount.accumulateAndGet(now, ::maxOf)
  }

  override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
    liveCount.decrementAndGet()
  }

  fun resetPeak() = peakCount.set(liveCount.get())
}

/**
 * Wires the real player classes by hand on the device (work-plan 2.4: core instrumented tests use no Hilt component),
 * with the settable fakes for what `:core:player` does not own and synthetic media made once per process.
 */
@OptIn(UnstableApi::class)
internal class PlayerHarness {
  val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  val foreground = MutableStateFlow(true)
  val content = FakeClipContentRepository()
  val decoderCounter = DecoderCounter()
  val created = CopyOnWriteArrayList<ExoPlayer>()

  /** Each player's own output: a prepared neighbour pre-rolls its audio too, so one shared capture would mix them. */
  private val captures = java.util.concurrent.ConcurrentHashMap<ExoPlayer, PcmCapture>()
  private val signal = object : ForegroundSignal {
    override val foreground = this@PlayerHarness.foreground
  }
  private val dispatchers = PlayerDispatchers(Dispatchers.Main.immediate)
  val arbiter: FocusAudioArbiter by lazy {
    onMain { FocusAudioArbiter(context.getSystemService(AudioManager::class.java), signal, dispatchers) }
  }
  val decoders = MediaCodecDecoderCapabilities(PlatformVideoDecoders())

  fun factory(failVideoAfterMs: Long? = null): ExoPlayerFactory = ExoPlayerFactory {
    val capture = PcmCapture()
    DefaultExoPlayerFactory(context, TestRenderers(context, capture, failVideoAfterMs)).create().also {
      it.addAnalyticsListener(decoderCounter)
      captures[it] = capture
      created += it
    }
  }

  /** What [player] sent to the audio output. */
  fun heard(player: ExoPlayer?): PcmCapture = captures[requireNotNull(player) { "no player" }] ?: error("not made by this harness")

  /** What the pool's current short sent to the audio output. */
  fun heard(pool: FeedPlayerPool): PcmCapture = heard(onMain { pool.currentPlayer() })

  /** What the only player made so far (a span player's) sent to the audio output. */
  fun heardFirst(): PcmCapture = heard(created.first())

  fun pool(failVideoAfterMs: Long? = null): FeedPlayerPool =
    onMain { FeedPlayerPool(factory(failVideoAfterMs), ClipSources(), arbiter, decoders, content, signal, dispatchers) }

  fun spanPlayer(): ExoSpanPlayer = onMain { ExoSpanPlayer(factory(), ClipSources(), content, arbiter, signal, dispatchers) }

  /** A clip's media built from the shared synthetic files. */
  fun media(
    job: JobId,
    mix: File = Media.mix,
    dialogue: File? = Media.dialogue,
    video: File? = Media.video,
    codec: String? = "h264",
    offsetMs: Long = 0,
    width: Int = Media.VIDEO.width,
    height: Int = Media.VIDEO.height,
  ) = ClipMedia(
    job, mix, dialogue, video, codec, width, height, 0, offsetMs, null,
    if (video == null) VideoAvailability.AudioOnlyClip else VideoAvailability.OnThisPhone,
  )

  /** One short of [job] from [startMs] to [endMs]; its first and last segments index the clip's phrases. */
  fun item(media: ClipMedia, startMs: Long, endMs: Long, firstSegment: Int = 0, lastSegment: Int = firstSegment) =
    FeedItem(ShortInfo(ShortId(media.jobId, firstSegment), 1, 1, lastSegment, startMs, endMs, null, 0), media)

  /** Registers the clip's analysis: [phrases] as (start, end) in ms, and whether it has a dialogue track. */
  fun clip(job: JobId, dialogueSeparation: Boolean?, vararg phrases: Pair<Long, Long>) {
    content.data[job] = ClipData(
      job,
      "rev",
      ClipMeta(Media.DURATION_MS / 1000.0, "en", 3, null, dialogueSeparation, null),
      phrases.mapIndexed { i, (start, end) ->
        Phrase(i, start / 1000.0, end / 1000.0, "", emptyList(), null, null, null, null, null, emptyList())
      },
      ClipDataSummary(emptyMap(), emptyMap()),
    )
  }

  /** Releases every player this harness made and gives the sound back. */
  fun release() {
    onMain { created.forEach { it.release() } }
  }

  /** The synthetic files, made once per test process (encoding takes a few seconds). */
  object Media {
    const val DURATION_MS = 12_000L
    const val MIX_HZ = 440.0
    const val DIALOGUE_HZ = 660.0
    const val VIDEO_AUDIO_HZ = 1000.0
    val TONES = listOf(MIX_HZ, DIALOGUE_HZ, VIDEO_AUDIO_HZ)
    val VIDEO = SyntheticMedia.VideoSpec(width = 320, height = 240, fps = 30, durationMs = DURATION_MS, audioToneHz = VIDEO_AUDIO_HZ)

    /** A phone-sized landscape video, for the decoder and memory measurement. */
    val HD = VIDEO.copy(width = 1280, height = 720, durationMs = 8_000, audioToneHz = null, bitRate = 2_500_000)

    /** Key frames 5 s apart, so most short starts are far from one. */
    val LONG_GOP = VIDEO.copy(keyframeIntervalS = 5, audioToneHz = null)

    private val dir: File by lazy {
      File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "synthetic").apply { mkdirs() }
    }
    val mix: File by lazy { SyntheticMedia.wav(File(dir, "audio_mix.wav"), DURATION_MS, MIX_HZ) }
    val dialogue: File by lazy { SyntheticMedia.wav(File(dir, "audio_dialogue.wav"), DURATION_MS, DIALOGUE_HZ) }
    val video: File by lazy { File(dir, "original.mp4").also { SyntheticMedia.video(it, VIDEO) } }
    val hd: File by lazy { File(dir, "original_hd.mp4").also { SyntheticMedia.video(it, HD) } }
    val longGop: File by lazy { File(dir, "original_long_gop.mp4").also { SyntheticMedia.video(it, LONG_GOP) } }
  }
}
