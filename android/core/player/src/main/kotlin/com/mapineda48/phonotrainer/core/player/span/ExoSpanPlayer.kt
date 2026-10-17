package com.mapineda48.phonotrainer.core.player.span

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.SpanPlayer
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.SpanPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import com.mapineda48.phonotrainer.core.player.arbiter.ArbiterClient
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.arbiter.PauseReason
import com.mapineda48.phonotrainer.core.player.media.ClipSources
import com.mapineda48.phonotrainer.core.player.media.ExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.media.SpanBounds
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the shadowing runner drives (architecture 11.5): the span player, without its own claim on the sound. */
internal interface SpanEngine {
  /** The speed of the last play; shadowing restores it when it stops. */
  val speed: Float

  fun setSpeed(speed: Float)

  /** Loads [span] of [track] (the other track when it is absent); false when the clip's audio is not on the phone. */
  suspend fun load(span: ClipSpan, track: Track): Boolean

  /** Plays the loaded span once from its start at [rate]. */
  fun listen(rate: Float)

  fun pause()

  fun setVolume(volume: Float)
}

/**
 * Plays a stretch of one clip's local audio (architecture 11.5): lessons, Practice, Learn examples, saved rows, end
 * cards. One player for the whole app, so two spans never sound together and its audio decoder is the only one
 * outside the feed. Spans shorter than 0.25 s are stretched around their middle ([SpanBounds]).
 *
 * [play] returns when the span has played to its end, or when it was stopped, paused by the arbiter (another sound, a
 * call, the app leaving the foreground) or unplugged earphones paused it; with `loop` it plays until then. A caller
 * that is cancelled stops the sound. Missing or unreadable audio sets `state.error` to [AppError.Media.AudioMissing].
 */
@Singleton
@OptIn(UnstableApi::class)
internal class ExoSpanPlayer @Inject constructor(
  private val players: ExoPlayerFactory,
  private val sources: ClipSources,
  private val content: ClipContentRepository,
  private val arbiter: FocusAudioArbiter,
  foreground: ForegroundSignal,
  dispatchers: PlayerDispatchers,
) : SpanPlayer, SpanEngine, ArbiterClient, Player.Listener {
  private val main = dispatchers.main
  private val scope = CoroutineScope(SupervisorJob() + main)
  private val states = MutableStateFlow(SpanPlaybackState(null, false, 0, 1f, Track.MIX, false, null))
  override val state: StateFlow<SpanPlaybackState> = states.asStateFlow()

  private var exo: ExoPlayer? = null
  private var startMs = 0L
  private var waiter: CompletableDeferred<Unit>? = null
  private var ticker: Job? = null

  override val speed: Float get() = states.value.speed

  init {
    scope.launch {
      foreground.foreground.collect { inForeground -> if (!inForeground) exo?.stop() }
    }
  }

  override suspend fun play(span: ClipSpan, speed: Float, track: Track, loop: Boolean) = withContext(main) {
    if (!load(span, track)) return@withContext
    setSpeed(speed)
    states.update { it.copy(loop = loop) }
    if (!arbiter.claim(PlaybackOwner.SPAN, this@ExoSpanPlayer)) {
      states.update { it.copy(playing = false) }
      return@withContext
    }
    val done = CompletableDeferred<Unit>()
    waiter = done
    start(rate = speed, repeat = loop)
    try {
      done.await()
    } finally {
      if (waiter === done) stop()
    }
  }

  @MainThread
  override fun stop() {
    pauseInternal()
    arbiter.release(PlaybackOwner.SPAN, this)
  }

  override suspend fun load(span: ClipSpan, track: Track): Boolean = withContext(main) {
    finishWaiter()
    pauseInternal()
    val media = runCatching { content.media(span.jobId) }.getOrNull()
    val (file, played) = media?.let { choose(it, track) } ?: (null to track)
    if (file == null || !file.isFile) {
      states.update { SpanPlaybackState(span, false, span.startMs, it.speed, track, false, AppError.Media.AudioMissing) }
      return@withContext false
    }
    val bounds = SpanBounds.audible(span.startMs, span.endMs)
    startMs = bounds.first
    val player = player()
    player.setMediaSource(sources.span(file, bounds.first, bounds.last))
    player.prepare()
    states.update { SpanPlaybackState(span, false, bounds.first, it.speed, played, false, null) }
    true
  }

  @MainThread
  override fun setSpeed(speed: Float) {
    exo?.playbackParameters = PlaybackParameters(speed, 1f)
    states.update { it.copy(speed = speed) }
  }

  @MainThread
  override fun listen(rate: Float) = start(rate, repeat = false)

  @MainThread
  override fun pause() = pauseInternal()

  @MainThread
  override fun setVolume(volume: Float) {
    exo?.volume = volume
  }

  override fun onArbiterPause(reason: PauseReason) {
    pauseInternal()
  }

  override fun onArbiterVolume(volume: Float) = setVolume(volume)

  private fun start(rate: Float, repeat: Boolean) {
    val player = player()
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.playbackParameters = PlaybackParameters(rate, 1f)
    player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    player.seekTo(0)
    player.playWhenReady = true
    states.update { it.copy(playing = true, positionMs = startMs) }
    ticker?.cancel()
    ticker = scope.launch {
      while (isActive) {
        exo?.let { p -> states.update { it.copy(positionMs = startMs + p.currentPosition) } }
        delay(TICK_MS)
      }
    }
  }

  private fun pauseInternal() {
    ticker?.cancel()
    ticker = null
    exo?.playWhenReady = false
    states.update { it.copy(playing = false) }
    finishWaiter()
  }

  private fun finishWaiter() {
    waiter?.complete(Unit)
    waiter = null
  }

  private fun player(): ExoPlayer = exo ?: players.create().also {
    it.addListener(this)
    exo = it
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_ENDED) {
      pauseInternal()
      arbiter.release(PlaybackOwner.SPAN, this)
    }
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) giveUpSound()
  }

  override fun onPlayerError(error: PlaybackException) {
    giveUpSound()
    states.update { it.copy(error = AppError.Media.AudioMissing) }
  }

  /** Earphones unplugged or unreadable audio: this sound stops, and so does a shadowing loop playing through it. */
  private fun giveUpSound() {
    pauseInternal()
    if (arbiter.owner.value == PlaybackOwner.SHADOWING) arbiter.interrupt(PlaybackOwner.SHADOWING)
    arbiter.release(PlaybackOwner.SPAN, this)
  }

  private fun choose(media: ClipMedia, track: Track): Pair<File, Track> {
    val dialogue = media.audioDialogue
    val mixIsDialogue = dialogue != null && media.audioMix == dialogue
    return when {
      track == Track.DIALOGUE && dialogue != null -> dialogue to Track.DIALOGUE
      track == Track.DIALOGUE -> media.audioMix to Track.MIX
      mixIsDialogue -> media.audioMix to Track.DIALOGUE
      else -> media.audioMix to Track.MIX
    }
  }

  private companion object {
    /** How often `state.positionMs` follows the playback (a lesson's progress, not captions). */
    const val TICK_MS = 50L
  }
}
