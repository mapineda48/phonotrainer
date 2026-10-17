package com.mapineda48.phonotrainer.core.player.shadowing

import android.content.Context
import androidx.annotation.MainThread
import com.mapineda48.phonotrainer.core.common.shadowing.ShadowingPlan
import com.mapineda48.phonotrainer.core.common.shadowing.ShadowingStep
import com.mapineda48.phonotrainer.core.domain.api.Announcer
import com.mapineda48.phonotrainer.core.domain.api.ShadowingRunner
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.PlaybackOwner
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import com.mapineda48.phonotrainer.core.domain.model.ShadowingState
import com.mapineda48.phonotrainer.core.domain.model.Track
import com.mapineda48.phonotrainer.core.player.PlayerDispatchers
import com.mapineda48.phonotrainer.core.player.R
import com.mapineda48.phonotrainer.core.player.arbiter.ArbiterClient
import com.mapineda48.phonotrainer.core.player.arbiter.FocusAudioArbiter
import com.mapineda48.phonotrainer.core.player.arbiter.PauseReason
import com.mapineda48.phonotrainer.core.player.span.SpanEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The live line of each phase (spec F-12, the web's `describePhase`), announced politely (F-12-AC4). */
internal class ShadowingWords @Inject constructor(@ApplicationContext private val context: Context) {
  fun phase(step: ShadowingStep): String = when {
    step.phase == ShadowingPhase.YOUR_TURN -> context.getString(R.string.player_shadowing_your_turn, step.round, step.rounds)
    step.fullSpeed -> context.getString(R.string.player_shadowing_listen_full, step.round, step.rounds)
    else -> context.getString(R.string.player_shadowing_listen, step.round, step.rounds, rate(step.rate))
  }

  fun stopped(): String = context.getString(R.string.player_shadowing_stopped)

  /** 0.75 → "0.75×", 0.5 → "0.5×", as the web writes `${rate}×`. */
  private fun rate(rate: Float): String =
    context.getString(R.string.player_speed, BigDecimal(rate.toString()).stripTrailingZeros().toPlainString())
}

/**
 * Runs the shadowing loop (spec F-12, architecture 11.5) over the span player: the pure [ShadowingPlan] (WP-12) says
 * what each of the 8 phases does and how long it lasts (3 rounds when the slow speed is 1×); a listen plays the span
 * from its start at the round's speed, a "your turn" is silence. Each phase is published in [state] and announced
 * politely. Timing follows the plan, as the web's timers do, not the audio.
 *
 * [run] returns when the plan has finished or the loop stopped: [stop], a cancelled caller, another sound claiming the
 * arbiter (a swipe to another short, spec F-12-AC3), unplugged earphones or the app leaving the foreground, or audio
 * focus lost to a call, which also announces "Shadowing stopped.". However it ends, the span player's speed from
 * before the loop is restored.
 */
@Singleton
internal class PlanShadowingRunner @Inject constructor(
  private val engine: SpanEngine,
  private val arbiter: FocusAudioArbiter,
  private val announcer: Announcer,
  private val words: ShadowingWords,
  dispatchers: PlayerDispatchers,
) : ShadowingRunner, ArbiterClient {
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
  private val states = MutableStateFlow<ShadowingState>(ShadowingState.Idle)
  override val state: StateFlow<ShadowingState> = states.asStateFlow()
  private var job: Job? = null

  override suspend fun run(span: ClipSpan, slowSpeed: Float, track: Track) {
    job?.cancel()
    val run = scope.launch { execute(span, slowSpeed, track) }
    job = run
    try {
      run.join()
    } finally {
      run.cancel()
    }
  }

  @MainThread
  override fun stop() {
    job?.cancel()
  }

  private suspend fun execute(span: ClipSpan, slowSpeed: Float, track: Track) {
    val plan = ShadowingPlan.plan(span, slowSpeed)
    val previousSpeed = engine.speed
    try {
      if (!engine.load(span, track)) return
      if (!arbiter.claim(PlaybackOwner.SHADOWING, this)) return
      for (step in plan) {
        states.value = ShadowingState.Running(step.round, step.rounds, step.phase, step.rate)
        announcer.polite(words.phase(step))
        if (step.phase == ShadowingPhase.LISTEN) engine.listen(step.rate) else engine.pause()
        delay(step.ms)
      }
    } finally {
      engine.pause()
      engine.setSpeed(previousSpeed)
      arbiter.release(PlaybackOwner.SHADOWING, this)
      states.value = ShadowingState.Idle
    }
  }

  override fun onArbiterPause(reason: PauseReason) {
    val running = job?.isActive == true
    if (running && reason == PauseReason.FOCUS_LOSS) announcer.polite(words.stopped())
    job?.cancel()
  }

  override fun onArbiterVolume(volume: Float) = engine.setVolume(volume)
}
