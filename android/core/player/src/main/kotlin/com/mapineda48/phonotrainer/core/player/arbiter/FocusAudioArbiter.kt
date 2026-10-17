package com.mapineda48.phonotrainer.core.player.arbiter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.mapineda48.phonotrainer.core.domain.api.AudioArbiter
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the arbiter paused an owner. None of them resumes playback by itself (spec F-06 item 6). */
internal enum class PauseReason {
  /** Another owner claimed the sound (one sound at a time). */
  OTHER_OWNER,

  /** `AUDIOFOCUS_LOSS` or `AUDIOFOCUS_LOSS_TRANSIENT` (a call, another app), or the focus request was refused. */
  FOCUS_LOSS,

  /** The app left the foreground (spec D-28). */
  BACKGROUND,
}

/** What a player of this module lets the arbiter do to it. Called on the main thread. */
internal interface ArbiterClient {
  /** Pause now; the arbiter has already taken the sound away, so the client must not call [FocusAudioArbiter.release]. */
  fun onArbiterPause(reason: PauseReason)

  /** [FocusAudioArbiter.DUCKED_VOLUME] while another app's short sound plays, [FocusAudioArbiter.FULL_VOLUME] after. */
  fun onArbiterVolume(volume: Float)
}

/**
 * The single owner of "who is playing" (architecture 11.4): the feed, the span player, the quiz, record A/B and
 * shadowing. A new claim pauses the previous owner. Audio focus is requested here, not by the players (they are built
 * with `handleAudioFocus = false`), so that:
 * - `AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT` pause with no automatic resume, even when the focus comes back;
 * - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` (a notification sound) never pauses: the volume drops to [DUCKED_VOLUME] and is
 *   restored on `AUDIOFOCUS_GAIN`; the request sets `setWillPauseWhenDucked(false)`, so the system does not turn ducks
 *   into pauses for speech content (Android review M4).
 * Leaving the foreground pauses the owner too (spec D-28). Earphones being unplugged are handled by each player
 * (`setHandleAudioBecomingNoisy(true)`), which pauses itself and gives its claim back.
 *
 * Owners outside this module (record A/B, v1-complete) use [claim] and [release] of the domain interface; the players
 * here also register an [ArbiterClient] so the arbiter can pause and duck them. Main thread only.
 */
@Singleton
internal class FocusAudioArbiter(
  private val audioManager: AudioManager,
  foreground: ForegroundSignal,
  dispatchers: PlayerDispatchers,
) : AudioArbiter {
  @Inject constructor(
    @ApplicationContext context: Context,
    foreground: ForegroundSignal,
    dispatchers: PlayerDispatchers,
  ) : this(context.getSystemService(AudioManager::class.java), foreground, dispatchers)

  private val owners = MutableStateFlow<PlaybackOwner?>(null)

  /** Who holds the sound now; null when nobody does. */
  val owner: StateFlow<PlaybackOwner?> = owners.asStateFlow()

  private var client: ArbiterClient? = null
  private var hasFocus = false
  private var ducked = false

  private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(SPEECH)
    .setWillPauseWhenDucked(false)
    .setAcceptsDelayedFocusGain(false)
    .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
    .build()

  init {
    CoroutineScope(SupervisorJob() + dispatchers.main).launch {
      foreground.foreground.collect { inForeground -> if (!inForeground) pauseOwner(PauseReason.BACKGROUND) }
    }
  }

  @MainThread
  override fun claim(owner: PlaybackOwner) {
    claim(owner, null)
  }

  /**
   * [owner] takes the sound; the previous owner, if another, is paused. Returns false when the system refused audio
   * focus (a call is ringing or in progress): nothing may play then.
   */
  @MainThread
  fun claim(owner: PlaybackOwner, client: ArbiterClient?): Boolean {
    val previous = this.client
    val previousOwner = owners.value
    if (previousOwner != null && (previousOwner != owner || previous !== client)) {
      this.client = null
      owners.value = null
      previous?.onArbiterPause(PauseReason.OTHER_OWNER)
    }
    if (!hasFocus) {
      hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
      if (!hasFocus) {
        this.client = null
        owners.value = null
        return false
      }
    }
    this.client = client
    owners.value = owner
    client?.onArbiterVolume(if (ducked) DUCKED_VOLUME else FULL_VOLUME)
    return true
  }

  @MainThread
  override fun release(owner: PlaybackOwner) {
    if (owners.value == owner) giveBack()
  }

  /** Gives the sound back only if [client] still holds it for [owner]. */
  @MainThread
  fun release(owner: PlaybackOwner, client: ArbiterClient) {
    if (owners.value == owner && this.client === client) giveBack()
  }

  /** Pauses [owner] if it holds the sound (a swipe stops shadowing, spec F-12-AC3). */
  @MainThread
  fun interrupt(owner: PlaybackOwner) {
    if (owners.value == owner) pauseOwner(PauseReason.OTHER_OWNER)
  }

  private fun giveBack() {
    client = null
    owners.value = null
    abandonFocus()
  }

  private fun pauseOwner(reason: PauseReason) {
    val paused = client
    val hadOwner = owners.value != null
    client = null
    owners.value = null
    if (hadOwner) paused?.onArbiterPause(reason)
    abandonFocus()
  }

  private fun abandonFocus() {
    if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
    hasFocus = false
    ducked = false
  }

  @MainThread
  internal fun onFocusChange(change: Int) {
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> pauseOwner(PauseReason.FOCUS_LOSS)
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        // Paused for good: when the focus comes back nothing resumes (spec F-06 item 6). The request is kept until
        // then only so the system can tell us; the next claim asks again.
        val paused = client
        val hadOwner = owners.value != null
        client = null
        owners.value = null
        hasFocus = false
        if (hadOwner) paused?.onArbiterPause(PauseReason.FOCUS_LOSS)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        ducked = true
        client?.onArbiterVolume(DUCKED_VOLUME)
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        if (ducked) client?.onArbiterVolume(FULL_VOLUME)
        ducked = false
        hasFocus = owners.value != null
        if (!hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
      }
    }
  }

  companion object {
    /** The volume while another app's notification ducks us (architecture 11.4). */
    const val DUCKED_VOLUME: Float = 0.2f
    const val FULL_VOLUME: Float = 1f

    /** Speech for media playback (architecture 11.4). */
    val SPEECH: AudioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
  }
}
