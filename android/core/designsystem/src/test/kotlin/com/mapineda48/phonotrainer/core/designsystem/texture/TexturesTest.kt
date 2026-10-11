package com.mapineda48.phonotrainer.core.designsystem.texture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The seeded textures of design 5.4, 7.3 and 7.4 are deterministic, bounded and never fill a cell. */
class TexturesTest {
  @Test
  fun fnv1aIsTheStandardThirtyTwoBitHash() {
    assertThat(Seeds.unsigned(Seeds.fnv1a32(""))).isEqualTo(0x811C9DC5L)
    assertThat(Seeds.unsigned(Seeds.fnv1a32("a"))).isEqualTo(0xE40C292CL)
    assertThat(Seeds.unsigned(Seeds.fnv1a32("foobar"))).isEqualTo(0xBF9CF968L)
  }

  @Test
  fun theHalftoneIsTheSameEveryTimeForTheSameSeedAndDiffersAcrossSeeds() {
    val a = Halftone.dots(320f, 200f, 8f, seed = 42)
    val b = Halftone.dots(320f, 200f, 8f, seed = 42)
    val c = Halftone.dots(320f, 200f, 8f, seed = 43)
    assertThat(a).isEqualTo(b)
    assertThat(a).isNotEqualTo(c)
    assertThat(a).isNotEmpty()
    // A dot never fills its cell, so the paper always shows between dots.
    assertThat(a.maxOf { it.radius }).isAtMost(0.46f * 8f)
    assertThat(a.all { it.x in 0f..320f && it.y in 0f..200f }).isTrue()
  }

  @Test
  fun aCornerHalftoneFadesAwayFromItsCorner() {
    val dots = Halftone.dots(300f, 300f, 10f, seed = 7, area = HalftoneArea.CORNER_TOP_END)
    assertThat(dots.none { it.x < 30f && it.y > 270f }).isTrue()
    assertThat(Halftone.dots(0f, 100f, 8f, seed = 1)).isEmpty()
  }

  @Test
  fun theGrainTileIsTileableNoiseAroundWhite() {
    val levels = PaperGrain.levels(intArrayOf(64, 32, 16, 8, 4), stretchX = 1, sparse = false)
    assertThat(levels.size).isEqualTo(PaperGrain.TILE * PaperGrain.TILE)
    assertThat(levels.all { it in 0..255 }).isTrue()
    assertThat(levels.toSet().size).isGreaterThan(32)
    val fibres = PaperGrain.levels(intArrayOf(32, 16), stretchX = 8, sparse = true)
    // Sparse: most of the fibre tile is plain white.
    assertThat(fibres.count { it == 255 }).isGreaterThan(fibres.size / 2)
    assertThat(PaperGrain.levels(intArrayOf(32), 1, false)).isEqualTo(PaperGrain.levels(intArrayOf(32), 1, false))
  }
}
