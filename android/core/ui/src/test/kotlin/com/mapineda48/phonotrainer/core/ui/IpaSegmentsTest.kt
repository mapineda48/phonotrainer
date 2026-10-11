package com.mapineda48.phonotrainer.core.ui

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.ui.IpaSegments.Arrow
import com.mapineda48.phonotrainer.core.ui.IpaSegments.Ipa
import com.mapineda48.phonotrainer.core.ui.IpaSegments.Prose
import com.mapineda48.phonotrainer.core.ui.ipa.IpaReading
import org.junit.Test

/** How `IpaText` finds IPA in a string and reads it aloud (design 4.3, A-05). */
class IpaSegmentsTest {
  private val names = mapOf("ɾ" to "voiced alveolar tap", "w" to "voiced labial-velar approximant", "ɔ" to "open-mid back rounded vowel",
    "t" to "voiceless alveolar stop", "ɚ" to "r-coloured schwa", "aɪ" to "the vowel of price")
  private val reading = IpaReading(listOf("aɪ")) { names[it] }

  @Test
  fun delimitedFormsAndArrowsAreIpa() {
    val parts = IpaSegments.parse("/ˈwɔtɚ/ → [ˈwɔɾɚ]")
    assertThat(parts).containsExactly(Ipa("/ˈwɔtɚ/", "ˈwɔtɚ"), Prose(" "), Arrow("→"), Prose(" "), Ipa("[ˈwɔɾɚ]", "ˈwɔɾɚ")).inOrder()
    assertThat(IpaSegments.isAllIpa(parts)).isTrue()
  }

  @Test
  fun proseKeepsItsSlashesAndIpaInsideProseIsFound() {
    assertThat(IpaSegments.parse("t/d processes")).containsExactly(Prose("t/d processes"))
    val parts = IpaSegments.parse("The schwa /ə/ is weak.")
    assertThat(parts.filterIsInstance<Ipa>().map { it.inner }).containsExactly("ə")
    assertThat(IpaSegments.isAllIpa(parts)).isFalse()
    val bare = IpaSegments.parse("pick it up → pɪ‿kɪ‿ɾʌp")
    assertThat(bare.filterIsInstance<Ipa>().map { it.text }).containsExactly("pɪ‿kɪ‿ɾʌp")
    assertThat(bare.filterIsInstance<Arrow>()).hasSize(1)
  }

  @Test
  fun aTranscriptionIsWholeEvenInLatinLetters() {
    assertThat(IpaSegments.parse("t", whole = true)).containsExactly(Ipa("t", "t"))
    assertThat(IpaSegments.isAllIpa(IpaSegments.parse("t", whole = true))).isTrue()
    assertThat(IpaSegments.parse("t")).containsExactly(Prose("t"))
  }

  @Test
  fun theSpokenTextNamesEverySymbol() {
    val spoken = IpaSegments.spoken(IpaSegments.parse("[ɾ]"), reading, " to ") { w, n -> "$w: $n" }
    assertThat(spoken).isEqualTo("[ɾ]: voiced alveolar tap")
    val pair = IpaSegments.spoken(IpaSegments.parse("/ˈwɔtɚ/ → [ˈwɔɾɚ]"), reading, " to ") { w, n -> "$w: $n" }
    assertThat(pair).isEqualTo(
      "/ˈwɔtɚ/: voiced labial-velar approximant, open-mid back rounded vowel, voiceless alveolar stop, r-coloured schwa" +
        " to [ˈwɔɾɚ]: voiced labial-velar approximant, open-mid back rounded vowel, voiced alveolar tap, r-coloured schwa",
    )
    assertThat(IpaSegments.spoken(IpaSegments.parse("No IPA here"), reading, " to ") { w, n -> "$w: $n" }).isNull()
    // Without names there is nothing better to say than the text itself.
    assertThat(IpaSegments.spoken(IpaSegments.parse("[ɾ]"), IpaReading(), " to ") { w, n -> "$w: $n" }).isNull()
  }

  @Test
  fun symbolsAreCutAsTheTokenizerCutsThem() {
    assertThat(reading.split("tənˈaɪt")).containsExactly("t", "ə", "n", "ˈaɪ", "t").inOrder()
    assertThat(IpaReading().split("bʌʔn̩")).containsExactly("b", "ʌ", "ʔ", "n̩").inOrder()
    assertThat(reading.nameOf("ˈaɪ")).isEqualTo("the vowel of price")
  }
}
