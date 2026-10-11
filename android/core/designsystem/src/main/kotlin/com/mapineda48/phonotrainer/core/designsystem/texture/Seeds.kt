package com.mapineda48.phonotrainer.core.designsystem.texture

/**
 * The stable hash of the generated visuals (architecture 11.6, design 7.3 and 7.4): FNV-1a, 32 bits, over the UTF-8
 * bytes of a job id or a title, so each clip looks different and the same every time.
 */
object Seeds {
  private const val OFFSET_BASIS = 0x811C9DC5.toInt()
  private const val PRIME = 0x01000193

  fun fnv1a32(text: String): Int {
    var hash = OFFSET_BASIS
    for (b in text.encodeToByteArray()) {
      hash = hash xor (b.toInt() and 0xFF)
      hash *= PRIME
    }
    return hash
  }

  /** [hash] as an unsigned value, for `% n` choices. */
  fun unsigned(hash: Int): Long = hash.toLong() and 0xFFFFFFFFL
}

/** A small deterministic generator (xorshift32) for textures drawn from a seed. */
internal class SeededRandom(seed: Int) {
  private var state = if (seed == 0) 0x6D2B79F5 else seed

  fun nextInt(): Int {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    state = x
    return x
  }

  /** In [0, 1). */
  fun nextFloat(): Float = (nextInt() ushr 8) / 16_777_216f
}
