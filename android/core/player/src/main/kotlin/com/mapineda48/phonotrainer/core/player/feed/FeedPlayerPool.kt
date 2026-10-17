package com.mapineda48.phonotrainer.core.player.feed

import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.mapineda48.phonotrainer.core.domain.api.ClipContentRepository
import com.mapineda48.phonotrainer.core.domain.api.DecoderCapabilities
import com.mapineda48.phonotrainer.core.domain.api.FeedPlayer
import com.mapineda48.phonotrainer.core.domain.model.AppError
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipMedia
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.FeedItem
import com.mapineda48.phonotrainer.core.domain.model.FeedPlaybackState
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LoopMode
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import com.mapineda48.phonotrainer.core.player.arbiter.ArbiterClient
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.arbiter.PauseReason
import com.mapineda48.phonotrainer.core.player.media.ClipSources
import com.mapineda48.phonotrainer.core.player.media.ExoPlayerFactory
import com.mapineda48.phonotrainer.core.player.media.PhraseSpan
import com.mapineda48.phonotrainer.core.player.media.PhraseSpans
import com.mapineda48.phonotrainer.core.player.media.ShortPlan
import com.mapineda48.phonotrainer.core.player.media.ShortPlanner
import com.mapineda48.phonotrainer.core.player.media.SpanBounds
import com.mapineda48.phonotrainer.core.player.media.VideoMode
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The feed's player (architecture 11.1, 11.2): a pool of three Media3 players for the vertical pager. When the pager
 * settles on page n the pool keeps players on n-1, n and n+1, prepared from local files and paused at their start, so
 * a swipe starts sound at once (spec SC-08), and plays n when asked.
 *
 * Each short is one player whose source is the analysed audio of the chosen track, **first**, merged with the video
 * (its own audio left out), clipped to the short's play bounds: one timeline, one clock, so captions, the mouth and the
 * video follow the analysed audio exactly. A negative video offset uses [FollowerVideo] instead. Positions
 * ([positionMs], [seekTo], spans) are ms on the clip's analysed timeline.
 *
 * Decoders are scarce (Android review M2): while Watch is hidden ([setVisible]) or the app is in the background, every
 * player is stopped (codecs released, positions kept) and prepared again, paused, on return. Only the current page
 * renders to a surface ([com.mapineda48.phonotrainer.core.player.android.PlayerSurface]); `firstFrameRendered` says
 * when the poster may give way.
 *
 * Speed (pitch kept), track and loop are the learner's and carry over to every short (spec F-06 item 6, F-06-AC8).
 * A track change rebuilds the current short at the same position; fresh media from [setQueue] (a track arrived,
 * F-06-AC13) never switches the short that is current, only the others. A video that cannot be decoded falls back to
 * the audio at the same position with [AppError.Media.VideoDecode] for every short of that clip; unreadable audio
 * gives [AppError.Media.AudioMissing].
 *
 * One instance per Watch ViewModel (unscoped binding), released in its `onCleared`. Main thread only.
 */
@OptIn(UnstableApi::class)
internal class FeedPlayerPool @Inject constructor(
  private val players: ExoPlayerFactory,
  private val sources: ClipSources,
  private val arbiter: FocusAudioArbiter,
  private val decoders: DecoderCapabilities,
  private val content: ClipContentRepository,
  foregroundSignal: ForegroundSignal,
  dispatchers: PlayerDispatchers,
) : FeedPlayer, FeedVisibility, FeedSurfaces, ArbiterClient {
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
  private val states = MutableStateFlow(INITIAL)
  override val state: StateFlow<FeedPlaybackState> = states.asStateFlow()
  private val surfaces = MutableStateFlow<SurfaceTarget?>(null)
  override val surface: StateFlow<SurfaceTarget?> = surfaces.asStateFlow()

  private var items: List<FeedItem> = emptyList()
  private var current = -1
  private val slots = ArrayList<Slot>(POOL_SIZE)
  private var chosenTrack = Track.MIX
  private var chosenSpeed = 1f
  private var held = false
  private var loopMode = LoopMode.OFF
  private var visible = true
  private var foreground = true
  private var released = false

  /** Clips whose video failed to decode while this pool lived: their shorts play audio only. */
  private val videoFailed = HashSet<JobId>()
  private val facts = HashMap<JobId, ClipFacts>()
  private val loading = HashSet<JobId>()

  private var boundary: PlayerMessage? = null
  private var boundaryKind: Boundary? = null
  private var follower: Job? = null

  private val active: Boolean get() = visible && foreground && !released

  init {
    scope.launch { foregroundSignal.foreground.collect { inForeground -> changeActivity { foreground = inForeground } } }
  }

  override fun setQueue(items: List<FeedItem>, startIndex: Int) {
    if (released) return
    val playingId = currentSlot()?.plan?.shortId
    this.items = items
    val kept = playingId?.let { id -> items.indexOfFirst { it.short.id == id } }?.takeIf { it >= 0 }
    if (kept != null) {
      current = kept
      for (slot in slots.toList()) {
        val id = slot.plan?.shortId ?: continue
        val index = items.indexOfFirst { it.short.id == id }
        if (index < 0) {
          slot.clear()
          continue
        }
        slot.index = index
        if (index == current) continue // a short that is playing never switches track by itself (architecture 11.1)
        val fresh = planFor(index)
        if (fresh.sameMedia(slot.plan)) slot.plan = fresh else slot.load(fresh)
      }
      assignSlots(current)
    } else {
      leaveCurrent()
      arbiter.release(PlaybackOwner.FEED, this)
      cancelBoundary()
      current = if (startIndex in items.indices) startIndex else -1
      if (current >= 0) assignSlots(current) else slots.forEach { it.clear() }
    }
    refreshState()
  }

  override fun settle(index: Int, autoplay: Boolean) {
    if (released || index !in items.indices) return
    arbiter.interrupt(PlaybackOwner.SHADOWING) // a swipe stops shadowing (spec F-12-AC3)
    if (index != current) {
      leaveCurrent()
      cancelBoundary()
      if (!autoplay) arbiter.release(PlaybackOwner.FEED, this)
      current = index
    }
    assignSlots(index)
    currentSlot()?.player?.repeatMode = repeatModeOf(loopMode)
    applySpeed()
    refreshState()
    if (autoplay) play()
  }

  override fun play() {
    val slot = currentSlot() ?: return
    if (!active || slot.audioFailed) return
    if (!arbiter.claim(PlaybackOwner.FEED, this)) {
      refreshState()
      return
    }
    val player = slot.player
    if (slot.ended || player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    slot.ended = false
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.playWhenReady = true
    if (loopMode == LoopMode.PHRASE && boundaryKind == null) armPhraseLoop(slot)
    refreshState()
  }

  override fun pause() {
    pauseCurrent()
    arbiter.release(PlaybackOwner.FEED, this)
  }

  override fun seekTo(positionMs: Long) {
    val slot = currentSlot() ?: return
    val plan = slot.plan ?: return
    slot.player.seekTo(windowOf(plan, positionMs))
    slot.ended = false
    slot.follower?.sync()
    if (boundaryKind == Boundary.PHRASE_LOOP) armPhraseLoop(slot) else cancelBoundary()
    refreshState()
  }

  override fun setSpeed(speed: Float) {
    chosenSpeed = speed
    applySpeed()
    refreshState()
  }

  override fun holdSlow(held: Boolean) {
    this.held = held
    applySpeed()
    refreshState()
  }

  override fun setTrack(track: Track) {
    if (track == chosenTrack) return
    chosenTrack = track
    for (slot in slots) {
      if (slot.plan == null) continue
      val fresh = planFor(slot.index)
      when {
        fresh.sameMedia(slot.plan) -> slot.plan = fresh
        slot.index == current -> {
          val playing = slot.player.playWhenReady
          slot.load(fresh, slot.player.currentPosition)
          slot.player.playWhenReady = playing && active
        }
        else -> slot.load(fresh)
      }
    }
    applySpeed()
    currentSlot()?.player?.repeatMode = repeatModeOf(loopMode)
    refreshState()
  }

  override fun setLoop(mode: LoopMode) {
    loopMode = mode
    val slot = currentSlot()
    slot?.player?.repeatMode = repeatModeOf(mode)
    if (mode == LoopMode.PHRASE && slot != null) armPhraseLoop(slot) else if (boundaryKind == Boundary.PHRASE_LOOP) cancelBoundary()
    refreshState()
  }

  override fun replayPhrase() {
    val slot = currentSlot() ?: return
    val plan = slot.plan ?: return
    val phrase = PhraseSpans.at(phrasesOf(slot), positionMs()) ?: PhraseSpan(plan.playStartMs, plan.playEndMs)
    slot.player.seekTo(windowOf(plan, phrase.startMs))
    slot.ended = false
    play()
    when (loopMode) {
      LoopMode.PHRASE -> armPhraseLoop(slot)
      LoopMode.SHORT -> cancelBoundary()
      LoopMode.OFF -> armBoundary(slot, phrase.endMs, Boundary.PAUSE)
    }
  }

  override fun playSpan(span: ClipSpan) {
    val slot = currentSlot() ?: return
    val plan = slot.plan ?: return
    if (span.jobId != plan.shortId.jobId) return
    val bounds = SpanBounds.audible(span.startMs, span.endMs)
    slot.player.seekTo(windowOf(plan, bounds.first))
    slot.ended = false
    play()
    armBoundary(slot, bounds.last, Boundary.PAUSE)
  }

  override fun positionMs(): Long {
    val slot = currentSlot() ?: return 0
    val plan = slot.plan ?: return 0
    return (plan.playStartMs + slot.player.currentPosition).coerceIn(plan.playStartMs, plan.playEndMs)
  }

  @MainThread
  override fun setVisible(visible: Boolean) = changeActivity { this.visible = visible }

  override fun release() {
    if (released) return
    arbiter.release(PlaybackOwner.FEED, this)
    cancelBoundary()
    released = true
    slots.forEach { it.release() }
    slots.clear()
    follower?.cancel()
    scope.cancel()
    surfaces.value = null
    states.update { it.copy(playing = false, firstFrameRendered = false) }
  }

  override fun onArbiterPause(reason: PauseReason) = pauseCurrent()

  override fun onArbiterVolume(volume: Float) {
    slots.forEach { it.player.volume = volume }
  }

  /** The current short's player, for tests on the device. */
  internal fun currentPlayer(): ExoPlayer? = currentSlot()?.player

  /** The player prepared for page [index], for tests on the device. */
  internal fun playerAt(index: Int): ExoPlayer? = slots.firstOrNull { it.index == index && it.plan != null }?.player

  private fun changeActivity(change: () -> Unit) {
    val was = active
    change()
    val now = active
    if (was == now) return
    if (!now) {
      arbiter.release(PlaybackOwner.FEED, this)
      cancelBoundary()
      slots.forEach { it.suspend() }
    } else {
      slots.forEach { it.resume() }
      currentSlot()?.let { if (loopMode == LoopMode.PHRASE) armPhraseLoop(it) }
    }
    refreshState()
  }

  private fun currentSlot(): Slot? = slots.firstOrNull { it.index == current && it.plan != null }

  /** Players on [index], [index] + 1 and [index] - 1; a slot that already holds one of them keeps it. */
  private fun assignSlots(index: Int) {
    val wanted = listOf(index, index + 1, index - 1).filter { it in items.indices }
    val held = HashMap<Int, Slot>()
    for (slot in slots) {
      val i = slot.index
      if (i in wanted && i !in held && slot.plan?.shortId == items[i].short.id) held[i] = slot
    }
    val free = slots.filter { it !in held.values }.toMutableList()
    for (i in wanted) {
      requestFacts(items[i].media.jobId)
      if (i in held) continue
      val slot = free.removeFirstOrNull() ?: newSlot()
      slot.index = i
      slot.load(planFor(i))
      held[i] = slot
    }
    free.forEach { it.clear() }
    for ((i, slot) in held) {
      if (i == current) continue
      slot.player.repeatMode = Player.REPEAT_MODE_OFF
      slot.player.playWhenReady = false
    }
  }

  private fun newSlot(): Slot = Slot(players.create()).also { slots += it }

  private fun planFor(index: Int): ShortPlan {
    val item = items[index]
    val job = item.media.jobId
    return ShortPlanner.plan(item, chosenTrack, facts[job]?.hasDialogue, job in videoFailed, ::canPlay)
  }

  private fun canPlay(media: ClipMedia): Boolean {
    val codec = media.videoCodec ?: return true
    val width = media.videoWidth ?: return true
    val height = media.videoHeight ?: return true
    return decoders.canPlay(codec, width, height)
  }

  /** The previous current short stops and goes back to its start, so returning to it starts it over. */
  private fun leaveCurrent() {
    val slot = currentSlot() ?: return
    slot.player.playWhenReady = false
    slot.player.repeatMode = Player.REPEAT_MODE_OFF
    if (slot.player.playbackState != Player.STATE_IDLE) slot.player.seekTo(0)
    slot.ended = false
    slot.follower?.sync()
  }

  private fun pauseCurrent() {
    val slot = currentSlot() ?: return
    slot.player.playWhenReady = false
    slot.follower?.sync()
    refreshState()
  }

  private fun applySpeed() {
    for (slot in slots) {
      val speed = if (held && slot.index == current) HOLD_SPEED else chosenSpeed
      val parameters = PlaybackParameters(speed, 1f)
      if (slot.player.playbackParameters != parameters) slot.player.playbackParameters = parameters
      slot.follower?.sync()
    }
  }

  private fun refreshState() {
    val slot = currentSlot()
    val plan = slot?.plan
    states.update {
      FeedPlaybackState(
        index = current.coerceAtLeast(0),
        playing = active && slot != null && slot.player.playWhenReady && !slot.ended,
        speed = chosenSpeed,
        track = chosenTrack,
        loop = loopMode,
        ended = slot?.ended == true,
        holdSlow = held,
        videoShown = plan != null && plan.videoMode != VideoMode.NONE,
        firstFrameRendered = active && slot?.firstFrame == true,
        error = slot?.let(::errorOf),
        trackFallback = plan?.trackFallback,
      )
    }
    surfaces.value = if (active && slot != null) SurfaceTarget(current, slot.follower?.player ?: slot.player) else null
    val following = active && slot?.follower != null
    if (following && follower?.isActive != true) {
      follower = scope.launch {
        while (isActive) {
          currentSlot()?.follower?.sync()
          delay(FollowerVideo.SYNC_MS)
        }
      }
    } else if (!following) {
      follower?.cancel()
      follower = null
    }
  }

  private fun errorOf(slot: Slot): AppError.Media? = when {
    slot.audioFailed -> AppError.Media.AudioMissing
    slot.plan?.shortId?.jobId in videoFailed && items.getOrNull(slot.index)?.media?.videoFile != null ->
      AppError.Media.VideoDecode
    else -> null
  }

  private fun phrasesOf(slot: Slot): List<PhraseSpan> {
    val item = items.getOrNull(slot.index) ?: return emptyList()
    val clip = facts[item.media.jobId]?.phrases ?: return emptyList()
    return PhraseSpans.of(clip, item.short)
  }

  /** At the end of the current phrase, back to its start (architecture 11.1: "Loop: Phrase" uses player messages). */
  private fun armPhraseLoop(slot: Slot) {
    val plan = slot.plan ?: return
    val phrase = PhraseSpans.at(phrasesOf(slot), plan.playStartMs + slot.player.currentPosition)
    if (phrase == null) {
      cancelBoundary()
      boundaryKind = Boundary.PHRASE_LOOP // armed for real when the clip's phrases are known
      return
    }
    armBoundary(slot, phrase.endMs, Boundary.PHRASE_LOOP)
  }

  private fun armBoundary(slot: Slot, endMs: Long, kind: Boundary) {
    cancelBoundary()
    val plan = slot.plan ?: return
    boundaryKind = kind
    boundary = slot.player.createMessage { _, _ -> onBoundary(slot, kind) }
      .setLooper(Looper.getMainLooper())
      .setPosition(windowOf(plan, endMs))
      .setDeleteAfterDelivery(true)
      .send()
  }

  private fun cancelBoundary() {
    boundary?.cancel()
    boundary = null
    boundaryKind = null
  }

  private fun onBoundary(slot: Slot, kind: Boundary) {
    if (released || slot !== currentSlot()) return
    boundary = null
    boundaryKind = null
    when (kind) {
      Boundary.PAUSE -> pause()
      Boundary.PHRASE_LOOP -> {
        val plan = slot.plan ?: return
        val phrase = PhraseSpans.at(phrasesOf(slot), plan.playStartMs + slot.player.currentPosition - LOOP_BACK_MS)
          ?: return
        slot.player.seekTo(windowOf(plan, phrase.startMs))
        slot.follower?.sync()
        armBoundary(slot, phrase.endMs, Boundary.PHRASE_LOOP)
      }
    }
  }

  private fun requestFacts(job: JobId) {
    if (job in facts || !loading.add(job)) return
    scope.launch {
      val data: ClipData? = try {
        content.clipData(job)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failed: Exception) {
        null
      }
      loading.remove(job)
      // Unreadable clip data cannot say whether the clip has a dialogue track: word it as "not on this phone yet".
      facts[job] = ClipFacts(
        hasDialogue = data?.meta?.dialogueSeparation ?: (data == null),
        phrases = data?.let(PhraseSpans::ofClip).orEmpty(),
      )
      onFacts(job)
    }
  }

  private fun onFacts(job: JobId) {
    if (released) return
    for (slot in slots) {
      val plan = slot.plan ?: continue
      if (plan.shortId.jobId != job || slot.index !in items.indices) continue
      val fresh = planFor(slot.index)
      if (fresh.sameMedia(plan)) slot.plan = fresh
    }
    val slot = currentSlot()
    if (slot != null && slot.plan?.shortId?.jobId == job && loopMode == LoopMode.PHRASE && boundary == null) {
      armPhraseLoop(slot)
    }
    refreshState()
  }

  private fun repeatModeOf(mode: LoopMode): Int =
    if (mode == LoopMode.SHORT) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

  private fun windowOf(plan: ShortPlan, positionMs: Long): Long =
    (positionMs - plan.playStartMs).coerceIn(0, plan.playEndMs - plan.playStartMs)

  private fun isVideoFault(player: ExoPlayer, plan: ShortPlan, error: PlaybackException): Boolean {
    if (plan.videoMode != VideoMode.MERGED) return false
    val exo = error as? ExoPlaybackException ?: return false
    return when (exo.type) {
      ExoPlaybackException.TYPE_RENDERER ->
        exo.rendererIndex != C.INDEX_UNSET && player.getRendererType(exo.rendererIndex) == C.TRACK_TYPE_VIDEO ||
          MimeTypes.isVideo(exo.rendererFormat?.sampleMimeType)
      // Either file may be at fault; while the audio is there, try it alone before giving up on the short.
      ExoPlaybackException.TYPE_SOURCE -> plan.audio.isFile
      else -> false
    }
  }

  /** The video cannot play: this clip's shorts play audio only, the current one from where it was. */
  private fun fallBackToAudio(failed: Slot) {
    val plan = failed.plan ?: return
    videoFailed += plan.shortId.jobId
    for (slot in slots) {
      val other = slot.plan ?: continue
      if (other.shortId.jobId != plan.shortId.jobId || other.videoMode == VideoMode.NONE) continue
      val isCurrent = slot.index == current
      val playing = isCurrent && slot.player.playWhenReady
      val position = if (isCurrent) slot.player.currentPosition else 0L
      slot.load(planFor(slot.index), position)
      slot.player.playWhenReady = playing && active
    }
    applySpeed()
    currentSlot()?.player?.repeatMode = repeatModeOf(loopMode)
    refreshState()
  }

  private fun onError(slot: Slot, error: PlaybackException) {
    val plan = slot.plan ?: return
    if (isVideoFault(slot.player, plan, error)) {
      fallBackToAudio(slot)
      return
    }
    slot.audioFailed = true
    slot.player.playWhenReady = false
    if (slot.index == current) arbiter.release(PlaybackOwner.FEED, this)
    refreshState()
  }

  private inner class Slot(val player: ExoPlayer) : Player.Listener {
    var index = -1
    var plan: ShortPlan? = null
    var follower: FollowerVideo? = null
    var firstFrame = false
    var ended = false
    var audioFailed = false
    private val followerListener = object : Player.Listener {
      override fun onRenderedFirstFrame() = onFirstFrame()

      override fun onPlayerError(error: PlaybackException) = dropFollower()

      override fun onTracksChanged(tracks: Tracks) {
        if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) dropFollower()
      }
    }

    init {
      player.addListener(this)
    }

    fun load(plan: ShortPlan, positionMs: Long = 0) {
      this.plan = plan
      firstFrame = false
      ended = false
      audioFailed = false
      follower?.release()
      follower = null
      player.setMediaSource(sources.short(plan), positionMs)
      val video = plan.video
      if (plan.videoMode == VideoMode.FOLLOWER && video != null) {
        follower = FollowerVideo(players.create(), player, plan, video, sources).also { it.player.addListener(followerListener) }
      }
      if (active) {
        player.prepare()
        follower?.prepare()
      }
    }

    fun clear() {
      plan = null
      index = -1
      firstFrame = false
      ended = false
      audioFailed = false
      player.stop()
      player.clearMediaItems()
      follower?.release()
      follower = null
    }

    fun suspend() {
      player.playWhenReady = false
      player.stop()
      follower?.stop()
      firstFrame = false
    }

    fun resume() {
      if (plan == null) return
      player.prepare()
      follower?.prepare()
    }

    fun release() {
      player.removeListener(this)
      player.release()
      follower?.release()
      follower = null
    }

    fun onFirstFrame() {
      firstFrame = true
      if (index == current) refreshState()
    }

    private fun dropFollower() {
      val plan = plan ?: return
      videoFailed += plan.shortId.jobId
      follower?.release()
      follower = null
      this.plan = plan.copy(video = null, videoMode = VideoMode.NONE)
      refreshState()
    }

    override fun onRenderedFirstFrame() {
      if (follower == null) onFirstFrame()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState != Player.STATE_ENDED || index != current || plan == null) return
      ended = true
      player.playWhenReady = false
      follower?.sync()
      cancelBoundary()
      arbiter.release(PlaybackOwner.FEED, this@FeedPlayerPool)
      refreshState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      if (index != current) return
      if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
        follower?.sync()
        arbiter.release(PlaybackOwner.FEED, this@FeedPlayerPool)
      }
      refreshState()
    }

    override fun onPlayerError(error: PlaybackException) = onError(this, error)

    override fun onTracksChanged(tracks: Tracks) {
      val plan = plan ?: return
      if (plan.videoMode != VideoMode.MERGED || tracks.isEmpty) return
      // No decoder for the video at all: Media3 plays the audio and leaves the video unselected, with no error.
      if (!tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) fallBackToAudio(this)
    }
  }

  private class ClipFacts(val hasDialogue: Boolean, val phrases: List<PhraseSpan>)

  private enum class Boundary { PAUSE, PHRASE_LOOP }

  private companion object {
    const val POOL_SIZE = 3

    /** "Hold to slow down" (spec F-07 gestures). */
    const val HOLD_SPEED = 0.5f

    /** The phrase loop's message fires at the phrase end; this finds the phrase that just ended, not the next. */
    const val LOOP_BACK_MS = 50L

    val INITIAL = FeedPlaybackState(
      index = 0,
      playing = false,
      speed = 1f,
      track = Track.MIX,
      loop = LoopMode.OFF,
      ended = false,
      holdSlow = false,
      videoShown = false,
      firstFrameRendered = false,
      error = null,
      trackFallback = null,
    )
  }
}
