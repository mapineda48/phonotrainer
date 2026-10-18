package com.mapineda48.phonotrainer.core.study.quiz

/** A source of numbers in [0, 1), like JavaScript's `Math.random` (web `quiz.ts` `Rng`). */
fun interface Rng {
  fun next(): Double
}

/**
 * The web's small deterministic PRNG (mulberry32, `quiz.ts` `seededRng`), bit for bit: the same seed gives the same
 * numbers on the phone and in the browser, so sessions and sets repeat exactly. [seed] is taken modulo 2³², as the
 * web's `seed >>> 0`.
 */
fun seededRng(seed: Long): Rng {
  var state = seed.toInt()
  return Rng {
    state += 0x6d2b79f5
    var t = state
    t = (t xor (t ushr 15)) * (t or 1)
    t = t xor (t + (t xor (t ushr 7)) * (t or 61))
    ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4_294_967_296.0
  }
}

/** The web's `shuffle`: Fisher–Yates from the end, one draw per position; [items] is not changed. */
fun <T> shuffle(items: List<T>, rng: Rng): List<T> {
  val out = items.toMutableList()
  for (i in out.lastIndex downTo 1) {
    val j = (rng.next() * (i + 1)).toInt()
    val swap = out[i]
    out[i] = out[j]
    out[j] = swap
  }
  return out
}
