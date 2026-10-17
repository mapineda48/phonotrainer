package com.mapineda48.phonotrainer.core.player.arbiter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.player.ForegroundSignal
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager

/** Architecture 11.4, Android review M4: one test per focus-change code, plus ownership and the foreground. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FocusAudioArbiterTest {
  private val audioManager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
  private val audio: ShadowAudioManager = shadowOf(audioManager)
  private val foreground = MutableStateFlow(true)
  private lateinit var arbiter: FocusAudioArbiter
  private val feed = RecordingClient()
  private val span = RecordingClient()

  @Before
  fun setUp() {
    arbiter = FocusAudioArbiter(
      audioManager,
      object : ForegroundSignal {
        override val foreground = this@FocusAudioArbiterTest.foreground
      },
      PlayerDispatchers(UnconfinedTestDispatcher()),
    )
  }

  private fun focusChange(code: Int) = audio.lastAudioFocusRequest.listener.onAudioFocusChange(code)

  @Test
  fun aClaimRequestsFocusForSpeechThatIsNotPausedWhenDucked() {
    assertThat(arbiter.claim(PlaybackOwner.FEED, feed)).isTrue()

    val request = audio.lastAudioFocusRequest.audioFocusRequest
    assertThat(request.focusGain).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
    assertThat(request.willPauseWhenDucked()).isFalse()
    assertThat(request.acceptsDelayedFocusGain()).isFalse()
    assertThat(request.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_MEDIA)
    assertThat(request.audioAttributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_SPEECH)
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.FEED)
    assertThat(feed.volumes).containsExactly(1f)
  }

  @Test
  fun lossPausesAndNeverResumes() {
    arbiter.claim(PlaybackOwner.FEED, feed)

    focusChange(AudioManager.AUDIOFOCUS_LOSS)

    assertThat(feed.pauses).containsExactly(PauseReason.FOCUS_LOSS)
    assertThat(arbiter.owner.value).isNull()
    assertThat(audio.lastAbandonedAudioFocusRequest).isNotNull()
    focusChange(AudioManager.AUDIOFOCUS_GAIN)
    assertThat(feed.pauses).containsExactly(PauseReason.FOCUS_LOSS)
    assertThat(feed.volumes).containsExactly(1f)
  }

  @Test
  fun transientLossPausesAndDoesNotResumeWhenTheFocusComesBack() {
    arbiter.claim(PlaybackOwner.FEED, feed)

    focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertThat(feed.pauses).containsExactly(PauseReason.FOCUS_LOSS)
    assertThat(arbiter.owner.value).isNull()

    focusChange(AudioManager.AUDIOFOCUS_GAIN)
    assertThat(feed.pauses).containsExactly(PauseReason.FOCUS_LOSS)
    assertThat(feed.volumes).containsExactly(1f) // the claim's volume only: nothing resumed or restored
    assertThat(arbiter.owner.value).isNull()
    assertThat(audio.lastAbandonedAudioFocusRequest).isNotNull()
  }

  @Test
  fun aDuckLowersTheVolumeAndTheGainRestoresItWithoutEverPausing() {
    arbiter.claim(PlaybackOwner.FEED, feed)

    focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    assertThat(feed.volumes.last()).isEqualTo(FocusAudioArbiter.DUCKED_VOLUME)
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.FEED)

    focusChange(AudioManager.AUDIOFOCUS_GAIN)
    assertThat(feed.volumes).containsExactly(1f, 0.2f, 1f).inOrder()
    assertThat(feed.pauses).isEmpty()
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.FEED)
  }

  @Test
  fun aClaimWhileDuckedStartsAtTheDuckedVolume() {
    arbiter.claim(PlaybackOwner.FEED, feed)
    focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

    arbiter.claim(PlaybackOwner.SPAN, span)

    assertThat(span.volumes).containsExactly(FocusAudioArbiter.DUCKED_VOLUME)
    focusChange(AudioManager.AUDIOFOCUS_GAIN)
    assertThat(span.volumes).containsExactly(0.2f, 1f).inOrder()
  }

  @Test
  fun aNewClaimPausesThePreviousOwnerOnly() {
    arbiter.claim(PlaybackOwner.FEED, feed)
    arbiter.claim(PlaybackOwner.SPAN, span)

    assertThat(feed.pauses).containsExactly(PauseReason.OTHER_OWNER)
    assertThat(span.pauses).isEmpty()
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.SPAN)

    arbiter.claim(PlaybackOwner.SPAN, span) // the same owner again: nothing paused
    assertThat(span.pauses).isEmpty()
  }

  @Test
  fun anOwnerOutsideThisModuleIsTrackedByItsClaim() {
    arbiter.claim(PlaybackOwner.FEED, feed)
    arbiter.claim(PlaybackOwner.RECORD_AB)

    assertThat(feed.pauses).containsExactly(PauseReason.OTHER_OWNER)
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.RECORD_AB)
    arbiter.release(PlaybackOwner.RECORD_AB)
    assertThat(arbiter.owner.value).isNull()
  }

  @Test
  fun aReleaseByAnotherClientOrOwnerIsIgnored() {
    arbiter.claim(PlaybackOwner.SPAN, span)

    arbiter.release(PlaybackOwner.SPAN, feed)
    arbiter.release(PlaybackOwner.FEED)
    assertThat(arbiter.owner.value).isEqualTo(PlaybackOwner.SPAN)

    arbiter.release(PlaybackOwner.SPAN, span)
    assertThat(arbiter.owner.value).isNull()
    assertThat(audio.lastAbandonedAudioFocusRequest).isNotNull()
  }

  @Test
  fun aRefusedRequestPlaysNothing() {
    audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    assertThat(arbiter.claim(PlaybackOwner.FEED, feed)).isFalse()

    assertThat(arbiter.owner.value).isNull()
    assertThat(feed.volumes).isEmpty()
  }

  @Test
  fun interruptPausesOnlyTheNamedOwner() {
    arbiter.claim(PlaybackOwner.SHADOWING, span)

    arbiter.interrupt(PlaybackOwner.FEED)
    assertThat(span.pauses).isEmpty()

    arbiter.interrupt(PlaybackOwner.SHADOWING)
    assertThat(span.pauses).containsExactly(PauseReason.OTHER_OWNER)
    assertThat(arbiter.owner.value).isNull()
  }

  @Test
  fun leavingTheForegroundPausesTheOwner() {
    arbiter.claim(PlaybackOwner.FEED, feed)

    foreground.value = false

    assertThat(feed.pauses).containsExactly(PauseReason.BACKGROUND)
    assertThat(arbiter.owner.value).isNull()
    assertThat(audio.lastAbandonedAudioFocusRequest).isNotNull()
  }

  private class RecordingClient : ArbiterClient {
    val pauses = mutableListOf<PauseReason>()
    val volumes = mutableListOf<Float>()

    override fun onArbiterPause(reason: PauseReason) {
      pauses += reason
    }

    override fun onArbiterVolume(volume: Float) {
      volumes += volume
    }
  }
}
