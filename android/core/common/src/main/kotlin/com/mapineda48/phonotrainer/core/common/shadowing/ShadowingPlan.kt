package com.mapineda48.phonotrainer.core.common.shadowing

import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.ShadowingPhase
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The shadowing loop as a plain list of timed steps (spec F-12; architecture 11.5): listen at the slow speed, then a
 * silence as long as what was played plus [REPEAT_MARGIN_MS] for the learner to say it, [SLOW_ROUNDS] times; then
 * once more at full speed. Four rounds and eight steps at 0.5× or 0.75×; three rounds when the slow speed is 1×.
 *
 * A port of the web client's `features/analysis/lib/shadowing.ts` `shadowingPlan`, kept pure so `:core:player`'s
 * `ShadowingRunner` (WP-11) only executes it and the timing is testable without audio. The phase texts ("Round 2 of
 * 4: listen at 0.75×", "Round 2 of 4: your turn — say it now") are UI copy and live with the runner's and the
 * lesson's string resources; each [ShadowingStep] carries what they need.
 */
object ShadowingPlan {
  /** Extra silence after the gap, so the learner is not cut off mid-word (web `REPEAT_MARGIN_MS`). */
  const val REPEAT_MARGIN_MS: Long = 400

  /** Slow rounds before the full-speed one. */
  const val SLOW_ROUNDS: Int = 3

  /** A span is never treated as shorter than this (web `Math.max(0.2, …)`). */
  const val MIN_SPAN_MS: Long = 200

  /** The steps for [span] at [slowRate] (0.5 or 0.75 on screen). */
  fun plan(span: ClipSpan, slowRate: Float, slowRounds: Int = SLOW_ROUNDS): List<ShadowingStep> =
    plan(span.startMs, span.endMs, slowRate, slowRounds)

  /**
   * The steps for the stretch [startMs]..[endMs]: for each round a [ShadowingPhase.LISTEN] step lasting the stretch
   * at its rate, then a [ShadowingPhase.YOUR_TURN] step [REPEAT_MARGIN_MS] longer. Durations are rounded to the
   * nearest millisecond, halves up (JavaScript's `Math.round`).
   */
  fun plan(startMs: Long, endMs: Long, slowRate: Float, slowRounds: Int = SLOW_ROUNDS): List<ShadowingStep> {
    require(slowRate > 0f && slowRate.isFinite()) { "slowRate must be a positive speed" }
    require(slowRounds >= 0) { "slowRounds must not be negative" }
    val seconds = max(MIN_SPAN_MS, endMs - startMs) / MS_PER_S
    val rounds = slowRounds + if (slowRate == 1f) 0 else 1
    return buildList(rounds * 2) {
      for (round in 1..rounds) {
        val rate = if (round <= slowRounds) slowRate else 1f
        val ms = (seconds / rate.toDouble() * MS_PER_S).roundToLong()
        add(ShadowingStep(ShadowingPhase.LISTEN, round, rounds, rate, ms))
        add(ShadowingStep(ShadowingPhase.YOUR_TURN, round, rounds, rate, ms + REPEAT_MARGIN_MS))
      }
    }
  }

  /** Total length of a plan, in ms. */
  fun totalMs(steps: List<ShadowingStep>): Long = steps.sumOf { it.ms }

  private const val MS_PER_S = 1000.0
}

/**
 * One step of a [ShadowingPlan] (web `ShadowPhase`): [phase] of [round] (1-based) out of [rounds]; the last round is
 * the full-speed one. [rate] is the playback speed of the round (the silence of a [ShadowingPhase.YOUR_TURN] step is
 * sized for it); [ms] how long the step lasts.
 */
data class ShadowingStep(val phase: ShadowingPhase, val round: Int, val rounds: Int, val rate: Float, val ms: Long) {
  /** The round played at full speed (the web's "listen at full speed"). */
  val fullSpeed: Boolean get() = rate == 1f
}
