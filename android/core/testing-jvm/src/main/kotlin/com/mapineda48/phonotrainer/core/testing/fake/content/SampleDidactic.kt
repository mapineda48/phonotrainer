package com.mapineda48.phonotrainer.core.testing.fake.content

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Gesture
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.IpaColumn
import com.mapineda48.phonotrainer.core.domain.model.IpaGridRow
import com.mapineda48.phonotrainer.core.domain.model.IpaGroup
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.MouthDemo
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation

/**
 * A slice of the bundled didactic content for tests and previews, transcribed from `didactic.json`: two terms, two
 * measures and two symbols of the glossary, the flapping lesson, three phones and a two-place IPA chart.
 * `:core:content`'s `ContentFakesTest` checks that every key here exists in the bundled content; the texts are
 * samples and may lag behind it.
 */
object SampleDidactic {
  val schwa = GlossaryEntry(
    key = "schwa",
    title = "Schwa",
    body = "The short, relaxed vowel [ə], as in the first sound of about. It is the most frequent vowel in spoken " +
      "English: unstressed vowels turn into it.",
    example = "banana → bənænə",
    learnMore = null,
    learnMoreLabel = null,
  )

  val weakForm = GlossaryEntry(
    key = "weak-form",
    title = "Weak form",
    body = "Small grammar words (to, of, and, can, you…) have a strong form and a weak form. In normal speech the " +
      "weak one, usually with a schwa [ə], is the default.",
    example = "to → tə",
    learnMore = null,
    learnMoreLabel = null,
  )

  val deviate = GlossaryEntry(
    key = "metric:deviate",
    title = "Words that differ from the dictionary",
    body = "Words where at least one sound was not the one the dictionary gives: changed, dropped or added. This is " +
      "the headline of the research on spoken English: most words in real conversation do not sound like their " +
      "dictionary form.",
    example = "water → wɔɾɚ (the t became a flap)",
    learnMore = LinkTarget.Learn,
    learnMoreLabel = "See every change in Learn",
  )

  val schwaShare = GlossaryEntry(
    key = "metric:schwa_share",
    title = "Schwa among the vowels",
    body = "Of all the vowels heard, how many were schwa [ə], the short, relaxed \"uh\" of unstressed syllables. It " +
      "is the most frequent vowel of spoken English; Spanish has no vowel like it, so it is worth learning to hear.",
    example = "about → əbaʊt",
    learnMore = LinkTarget.Ipa("ə"),
    learnMoreLabel = "Schwa in the IPA chart",
  )

  val tap = GlossaryEntry(
    key = "ipa:ɾ",
    title = "[ɾ] alveolar tap",
    body = "The tip bounces off the ridge once, too briefly to build up any pressure. This is what American /t/ and " +
      "/d/ become between vowels. You hear it in “water”, “better” and “get it”. It is the single r of Spanish " +
      "“pero”: you can already say it.",
    example = "As in: the -tt- of “butter”",
    learnMore = LinkTarget.Phenomenon(ChangeLabel("flapping")),
    learnMoreLabel = "See the change it belongs to",
  )

  val stopT = GlossaryEntry(
    key = "ipa:t",
    title = "[t] voiceless alveolar stop",
    body = "The tip of the tongue seals against the ridge behind the upper teeth.",
    example = "As in: tea",
    learnMore = LinkTarget.Ipa("t"),
    learnMoreLabel = "See it in the IPA chart",
  )

  val glossary: Map<String, GlossaryEntry> =
    listOf(schwa, weakForm, deviate, schwaShare, tap, stopT).associateBy { it.key }

  val flapping = LessonContent(
    change = ChangeLabel("flapping"),
    listenFor = "A t or d between vowels becomes one very quick tap of the tongue: “water” sounds like “wadder”, " +
      "“get it” like “geddit”.",
    demo = MouthDemo(
      word = "water",
      dictionary = listOf("w", "ɔ", "t", "ɚ"),
      said = listOf("w", "ɔ", "ɾ", "ɚ"),
      from = "t",
      to = "ɾ",
    ),
    spanishTip = "The flap is the single r of Spanish “pero”. You already have this sound: use it for the t of " +
      "“water”, “better” or “a lot of”.",
    note = "Not before a stressed syllable: “attack” and “return” keep a full t.",
  )

  val lessons: Map<ChangeLabel, LessonContent> = mapOf(flapping.change to flapping)

  /** All eleven articulators; the ones these phones share are fixed. */
  private fun pose(
    body: Double,
    height: Double,
    jaw: Double,
    lipOpen: Double,
    tip: Double,
    tipFront: Double,
    voice: Double,
  ): Map<String, Double> = mapOf(
    "jaw" to jaw, "lipOpen" to lipOpen, "lipRound" to 0.05, "lipTuck" to 0.0, "body" to body, "height" to height,
    "tip" to tip, "tipFront" to tipFront, "root" to 0.3, "velum" to 0.0, "voice" to voice,
  )

  val phoneT = PhoneArticulation(
    symbol = "t",
    name = "voiceless alveolar stop",
    example = "tea",
    cue = "The tip of the tongue seals against the ridge behind the upper teeth.",
    manner = "stop",
    voiced = false,
    gestures = listOf(0.25, 0.75).map { Gesture(it, pose(0.6, 0.5, 0.14, 0.34, 1.0, 0.5, 0.0)) },
    carry = null,
  )

  val phoneTap = PhoneArticulation(
    symbol = "ɾ",
    name = "alveolar tap",
    example = "the -tt- of “butter”",
    cue = "The tip bounces off the ridge once, too briefly to build up any pressure. This is what American /t/ and " +
      "/d/ become between vowels.",
    manner = "tap",
    voiced = true,
    gestures = listOf(Gesture(0.5, pose(0.5, 0.42, 0.18, 0.36, 0.97, 0.52, 1.0))),
    carry = null,
  )

  val phoneH = PhoneArticulation(
    symbol = "h",
    name = "voiceless glottal fricative",
    example = "hat",
    cue = "Nothing blocks the mouth: the noise is made at the vocal folds while the tongue is already shaped for the " +
      "vowel that follows.",
    manner = "glottal",
    voiced = false,
    gestures = emptyList(),
    carry = mapOf("voice" to 0.0),
  )

  val phones: Map<String, PhoneArticulation> = listOf(phoneT, phoneTap, phoneH).associateBy { it.symbol }

  private val alveolar = IpaColumn("alveolar", "Gum ridge", "alveolar")
  private val glottal = IpaColumn("glottal", "Throat", "glottal")

  val ipaLayout = IpaLayout(
    places = listOf(alveolar, glottal),
    consonantRows = listOf(
      IpaGridRow("Stop", "the air is stopped completely, then released", mapOf("alveolar" to listOf("t", "d"))),
      IpaGridRow("Fricative", "the air hisses through a narrow gap", mapOf("glottal" to listOf("h"))),
    ),
    allophonePlaces = listOf(alveolar, glottal),
    allophoneRows = listOf(IpaGridRow("Tap", "", mapOf("alveolar" to listOf("ɾ")))),
    syllabics = listOf("n̩"),
    vowelColumns = listOf(IpaColumn("central", "Central", "")),
    vowelRows = listOf(IpaGridRow("Mid", "", mapOf("central" to listOf("ə")))),
    reducedVowels = listOf("ə"),
    glidingGroups = listOf(IpaGroup("Diphthongs", "the tongue glides from one vowel to another", listOf("eɪ", "aɪ"))),
    placed = listOf("t", "d", "h", "ɾ", "n̩", "ə", "eɪ", "aɪ"),
    unplaced = emptyList(),
  )
}
