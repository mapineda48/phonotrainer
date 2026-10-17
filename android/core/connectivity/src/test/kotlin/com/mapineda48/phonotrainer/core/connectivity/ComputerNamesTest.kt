package com.mapineda48.phonotrainer.core.connectivity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * One cleaner for every computer name that leaves the module (server-contract 2.3, design 9.26, security review L-2)
 * and the key names are compared on for the same-name conflict (spec D-39, F-02-AC10).
 */
class ComputerNamesTest {
  private val zeroWidthSpace = "\u200B"
  private val softHyphen = "\u00AD"
  private val rightToLeftOverride = "\u202E"
  private val firstStrongIsolate = "\u2068"
  private val popDirectionalIsolate = "\u2069"
  private val leftToRightMark = "\u200E"
  private val lineSeparator = "\u2028"
  private val byteOrderMark = "\uFEFF"

  @Test
  fun formatCharactersAreRemoved() {
    assertThat(ComputerNames.clean("Study desk$zeroWidthSpace")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("Study${softHyphen}desk")).isEqualTo("Studydesk")
    assertThat(ComputerNames.clean("${firstStrongIsolate}Study desk$popDirectionalIsolate")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("Study desk$leftToRightMark")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("${byteOrderMark}Study ${rightToLeftOverride}desk")).isEqualTo("Study desk")
  }

  @Test
  fun controlsAndLineBreaksBecomeOneSpaceAndTheNameIsTrimmed() {
    assertThat(ComputerNames.clean("Study\ndesk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("Study\r\n\tdesk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("Study${lineSeparator}desk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("Study\u2029desk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("  Study \u00A0  desk \u0007 ")).isEqualTo("Study desk")
  }

  @Test
  fun aLongNameKeepsSixtyGraphemesAndNeverSplitsALetterOrAnEmoji() {
    val long = "x".repeat(3_000)
    assertThat(ComputerNames.clean(long)).isEqualTo("x".repeat(59) + "\u2026")

    val accented = "e\u0301".repeat(70) // "e" and a combining acute accent: one grapheme each
    val cleaned = ComputerNames.clean(accented)
    assertThat(cleaned).isEqualTo("e\u0301".repeat(59) + "\u2026")

    val emoji = "\uD83D\uDCBB".repeat(61) // a laptop, a surrogate pair each
    assertThat(ComputerNames.clean(emoji)).isEqualTo("\uD83D\uDCBB".repeat(59) + "\u2026")

    val exactly = "y".repeat(60)
    assertThat(ComputerNames.clean(exactly)).isEqualTo(exactly)
  }

  @Test
  fun anEmptyNameFallsBackToTheDiscoveryNameAndThenToAFixedOne() {
    assertThat(ComputerNames.clean("$zeroWidthSpace \n", fallback = "Study desk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean(null, fallback = "Study\ndesk")).isEqualTo("Study desk")
    assertThat(ComputerNames.clean("", fallback = rightToLeftOverride)).isEqualTo(ComputerNames.FALLBACK)
    assertThat(ComputerNames.clean(null)).isEqualTo("PhonoTrainer computer")
    // An unpaired surrogate is malformed text: removed.
    assertThat(ComputerNames.clean("Study desk\uD800")).isEqualTo("Study desk")
  }

  @Test
  fun aCleanNameIsLeftAsItIs() {
    assertThat(ComputerNames.clean("Study desk")).isEqualTo("Study desk")
    val accentsAndEmoji = "Caf\u00E9 \u2014 na\u00EFve \uD83D\uDCBB"
    assertThat(ComputerNames.clean(accentsAndEmoji)).isEqualTo(accentsAndEmoji)
    assertThat(ComputerNames.clean(ComputerNames.clean("Study\n${zeroWidthSpace}desk"))).isEqualTo("Study desk")
  }

  @Test
  fun namesThatReadAlikeHaveOneConflictKey() {
    val key = ComputerNames.conflictKey("Study desk")
    listOf(
      "Study desk$zeroWidthSpace",
      "Study${softHyphen}desk",
      "${firstStrongIsolate}Study desk$popDirectionalIsolate",
      "Study desk$leftToRightMark",
      "Study\ndesk",
      "STUDY DESK",
      "  study   desk ",
      "\uFF33\uFF54\uFF55\uFF44\uFF59 \uFF44\uFF45\uFF53\uFF4B", // full-width letters
      "Study\u00A0desk",
      "Study${rightToLeftOverride} desk",
    ).forEach { lookalike ->
      assertThat(ComputerNames.conflictKey(lookalike)).isEqualTo(key)
    }
    assertThat(ComputerNames.conflictKey("Kitchen")).isNotEqualTo(key)
    assertThat(ComputerNames.conflictKey("Study desk 2")).isNotEqualTo(key)
  }
}
