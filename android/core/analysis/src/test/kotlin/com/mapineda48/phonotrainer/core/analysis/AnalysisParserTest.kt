package com.mapineda48.phonotrainer.core.analysis

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.JOB
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.REVISION
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.assertCorrupt
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.assertSameClip
import com.mapineda48.phonotrainer.core.analysis.ClipAssertions.parse
import com.mapineda48.phonotrainer.core.domain.model.AnalysisModels
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipDataException
import com.mapineda48.phonotrainer.core.domain.model.Contour
import com.mapineda48.phonotrainer.core.domain.model.DialogueSeparation
import com.mapineda48.phonotrainer.core.domain.model.F0Stats
import com.mapineda48.phonotrainer.core.domain.model.LinkType
import com.mapineda48.phonotrainer.core.domain.model.MetricRatio
import com.mapineda48.phonotrainer.core.domain.model.PhoneEngine
import com.mapineda48.phonotrainer.core.domain.model.Rhythm
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.UnitType
import com.mapineda48.phonotrainer.core.domain.model.WordClass
import com.mapineda48.phonotrainer.core.domain.model.WordForm
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.Malformed
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.Variant
import com.mapineda48.phonotrainer.core.testing.fixtures.AnalysisFixtures.json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The parser on every kind of analysis that exists (WP-12 shape notes, section 3; NFR-08): older keys missing, the
 * espeak engine, rules 1 to 3, unknown values, values of the wrong type, and malformed files, which are corrupt.
 */
class AnalysisParserTest {
  private fun parse(variant: Variant) = parse(AnalysisFixtures.bytes(variant))

  private fun ref(segment: Int, word: Int) = WordRef(JOB, segment, word)

  @Test
  fun mkAnalysisParsesIntoItsDomainTwin() {
    // The two halves of AnalysisFixtures (JSON for the parser, ClipData for modules that cannot parse) never drift.
    assertSameClip(parse(Variant.MK_ANALYSIS), AnalysisFixtures.clipData(JOB, REVISION))
  }

  @Test
  fun aCurrentAnalysisFillsEveryField() {
    val clip = parse(Variant.RULES_3)
    assertThat(clip.jobId).isEqualTo(JOB)
    assertThat(clip.revision).isEqualTo(REVISION)
    with(clip.meta) {
      assertThat(durationS).isEqualTo(6.0)
      assertThat(language).isEqualTo("en")
      assertThat(rulesVersion).isEqualTo(3)
      assertThat(phoneEngine).isEqualTo("timit61")
      assertThat(engine).isEqualTo(PhoneEngine.TIMIT61)
      assertThat(narrowEngine).isTrue()
      assertThat(dialogueSeparation).isTrue()
      assertThat(dialogue)
        .isEqualTo(DialogueSeparation(applied = true, model = "htdemucs", seconds = 22.0, error = null))
      assertThat(phonotrainerVersion).isEqualTo("0.1.0")
      assertThat(models?.asr).isEqualTo("faster-whisper small (int8)")
    }
    assertThat(clip.phrases.map { it.index }).containsExactly(0, 1, 2).inOrder()
    assertThat(clip.wordCount).isEqualTo(12)

    val question = clip.phrases[0]
    assertThat(question.text).isEqualTo("Did you get the water?")
    assertThat(question.startS to question.endS).isEqualTo(0.0 to 1.1)
    assertThat(question.f0!!.size).isEqualTo(100)
    assertThat(question.f0!!.timesS[1]).isEqualTo(0.01f)
    assertThat(question.contour).isEqualTo(Contour.RISING)
    assertThat(question.f0Stats).isEqualTo(F0Stats(212.4, 96.1, "rising", 7.8, 6.1, 150.0, 330.5))
    assertThat(question.emphasisWord).isEqualTo(4)
    assertThat(question.prominence!!.toList()).containsExactly(0.3f, 0.1f, 0.6f, 0.0f, 1.0f).inOrder()
    assertThat(question.wordClass(0)).isEqualTo(WordClass.CONTENT)
    assertThat(question.wordClass(1)).isEqualTo(WordClass.FUNCTION)
    assertThat(question.wordClass(9)).isNull()
    val unit = question.intonationUnits.single()
    assertThat(unit.unitType).isEqualTo(UnitType.YES_NO_QUESTION)
    assertThat(unit.firstWord to unit.lastWord).isEqualTo(0 to 4)
    assertThat(unit.contour).isEqualTo(Contour.RISING)
    assertThat(unit.expected).isEqualTo(Contour.RISING)
    assertThat(unit.matchesExpected).isTrue()
    assertThat(unit.judged).isTrue()
    assertThat(unit.text).isEqualTo("Did you get the water?")
    assertThat(question.rhythm)
      .isEqualTo(Rhythm(47.6, 36.8, 8, 7, 169.0, 62.2, approximate = true, "inter-nucleus intervals (CTC peaks)"))

    val did = question.words[0]
    assertThat(did.text).isEqualTo("Did")
    assertThat(did.dictIpa).isEqualTo("dˈɪd")
    assertThat(did.canonicalIpa).isEqualTo("dɪd")
    assertThat(did.realizedIpa).isEqualTo("dɪdʒ")
    assertThat(did.canonicalPhones).containsExactly(
      TimedPhone("d", 0.0, 0.05), TimedPhone("ɪ", 0.06, 0.11), TimedPhone("d", 0.12, 0.17),
    ).inOrder()
    assertThat(did.realizedPhones.last()).isEqualTo(TimedPhone("dʒ", 0.12, 0.17))
    assertThat(did.changes).containsExactly(ChangeLabel("palatalization"))
    assertThat(did.diffCost).isEqualTo(0.3)
    assertThat(did.boundaryLinkNext).isTrue()
    assertThat(did.linkType).isEqualTo(LinkType.GLIDE_J)
    assertThat(did.crossesBoundary).isTrue()
    assertThat(question.words[2].changes).containsExactly(ChangeLabel("flapping"), ChangeLabel("linking")).inOrder()
    assertThat(question.words[3].changes).isEmpty()
    assertThat(question.words[3].linkType).isNull()

    val statement = clip.phrases[1]
    assertThat(statement.rhythm).isNull()
    assertThat(statement.intonationUnits.single().unitType).isEqualTo(UnitType.STATEMENT)
    val gonna = statement.words[1]
    assertThat(gonna.lexicalForm).isEqualTo("gonna")
    assertThat(gonna.lexicalExpansion).isEqualTo("going to")
    assertThat(statement.words[2].lowConfidence).isTrue()
    val numeral = statement.words[4]
    assertThat(numeral.canonicalText).isEqualTo("nine thirty")
    assertThat(numeral.noCanonical).isFalse()
    assertThat(numeral.form).isNull()
    assertThat(numeral.variantLabels).isEmpty()

    val symbols = clip.phrases[2]
    assertThat(symbols.f0).isNull() // "No measurable pitch in this phrase"
    assertThat(symbols.contour).isNull()
    assertThat(symbols.emphasisWord).isNull()
    assertThat(symbols.f0Stats).isEqualTo(F0Stats(null, null, null, null, null, 75.0, 600.0))
    assertThat(symbols.intonationUnits.single().judged).isFalse()
    val percent = symbols.words[0]
    assertThat(percent.noCanonical).isTrue()
    assertThat(percent.canonicalIpa).isNull()
    assertThat(percent.realizedIpa).isNull()
    assertThat(percent.noDictionaryForm).isTrue()
    assertThat(percent.nothingHeard).isTrue()
    assertThat(symbols.words[1].tooShort).isTrue()

    // the next word on the timeline crosses phrase ends; the gap is measured between heard sounds
    assertThat(clip.nextWord(ref(0, 4))).isEqualTo(ref(1, 0))
    assertThat(clip.nextWord(ref(2, 1))).isNull()
    assertThat(clip.boundaryGapMs(ref(0, 0))).isEqualTo(30) // "Did" ends at 0.17, "you" starts at 0.2

    with(clip.summary) {
      assertThat(changeCounts[ChangeLabel("flapping")]).isEqualTo(3)
      assertThat(changeCounts[ChangeLabel("vowel_reduction")]).isEqualTo(3)
      assertThat(measured).isTrue()
      assertThat(metricsVersion).isEqualTo(4)
      assertThat(ratio("deviate")).isEqualTo(MetricRatio(6, 10))
      assertThat(ratio("weak_forms.greedy")).isEqualTo(MetricRatio(2, 4))
      assertThat(ratio("weak_forms.variant")).isNull()
      assertThat(ratio("glottal_before_syllabic_n")!!.pct).isNull()
      assertThat(metrics["final_t_prevocalic.flap"]).isEqualTo(1.0)
      assertThat(metrics["labels_per_100_words.flapping"]).isEqualTo(25.0)
      assertThat(metrics["rhythm.npvi"]).isEqualTo(47.6)
      // strings, flags and nulls are not numbers
      assertThat(metrics.keys).containsNoneOf("engine", "rhythm.method", "rhythm.approximate", "weak_forms.variant")
    }
  }

  @Test
  fun rules2LacksTheNumeralKeys() {
    val clip = parse(Variant.RULES_2)
    assertThat(clip.meta.rulesVersion).isEqualTo(2)
    assertThat(clip.phrases[1].words[4].canonicalText).isNull()
    val percent = clip.phrases[2].words[0]
    assertThat(percent.noCanonical).isFalse()
    assertThat(percent.noDictionaryForm).isTrue() // still: no canonical phones
  }

  @Test
  fun rules1ReadsAsEspeakWithNothingTheLaterRulesAdded() {
    val clip = parse(Variant.RULES_1)
    with(clip.meta) {
      assertThat(rulesVersion).isNull()
      assertThat(phoneEngine).isNull()
      assertThat(engine).isEqualTo(PhoneEngine.ESPEAK) // "Expected by the aligner"
      assertThat(narrowEngine).isFalse()
      assertThat(dialogueSeparation).isNull()
      assertThat(dialogue).isNull()
    }
    for (phrase in clip.phrases) {
      assertThat(phrase.prominence).isNull()
      assertThat(phrase.wordClasses).isNull()
      assertThat(phrase.intonationUnits).isEmpty()
      assertThat(phrase.rhythm).isNull()
      for (word in phrase.words) {
        assertThat(word.linkType).isNull()
        assertThat(word.form).isNull()
        assertThat(word.variantLabels).isEmpty()
      }
    }
    assertThat(clip.phrases[0].f0Stats).isEqualTo(F0Stats(212.4, 96.1, "rising", null, null, null, null))
    assertThat(clip.phrases[0].words[0].boundaryLinkNext).isTrue() // the ‿ itself predates rules 2
    assertThat(clip.phrases[1].words[1].lexicalExpansion).isEqualTo("going to")
    assertThat(clip.summary.metrics).isEmpty()
    assertThat(clip.summary.measured).isFalse() // "older and not measured"
    assertThat(clip.summary.changeCounts).isNotEmpty()
  }

  @Test
  fun theEarliestAnalysesLackTheLexicalExpansionToo() {
    val clip = parse(Variant.EARLIEST)
    assertThat(clip.phrases[1].words[1].lexicalForm).isEqualTo("gonna")
    assertThat(clip.phrases[1].words[1].lexicalExpansion).isNull()
  }

  @Test
  fun theEspeakEngineCarriesFormScoring() {
    val clip = parse(Variant.ESPEAK)
    assertThat(clip.meta.engine).isEqualTo(PhoneEngine.ESPEAK)
    assertThat(clip.meta.narrowEngine).isFalse()
    assertThat(clip.meta.models).isEqualTo(
      AnalysisModels("faster-whisper small (int8)", "facebook/wav2vec2-lv-60-espeak-cv-ft", alignment = null),
    )
    val you = clip.phrases[0].words[1]
    assertThat(you.form)
      .isEqualTo(WordForm("jə", "juː", weak = true, weakMargin = 0.42, hDropped = null, hDropMargin = null))
    assertThat(you.variantLabels).containsExactly(ChangeLabel("vowel_reduction"))
    assertThat(clip.phrases[0].words[3].form?.strongIpa).isEqualTo("ðiː")
    assertThat(clip.phrases[1].words[3].form)
      .isEqualTo(WordForm("æt", "æt", weak = false, weakMargin = -0.3, hDropped = null, hDropMargin = null))
    assertThat(clip.summary.ratio("weak_forms.variant")).isEqualTo(MetricRatio(2, 3))
    assertThat(clip.summary.metrics["weak_forms.variant.weak"]).isEqualTo(2.0)
  }

  @Test
  fun unknownValuesAreKeptRawAndTypedAsUnknown() {
    val clip = parse(Variant.UNKNOWN_ENUMS)
    assertThat(clip.meta.phoneEngine).isEqualTo("x-engine")
    assertThat(clip.meta.engine).isEqualTo(PhoneEngine.UNKNOWN)
    assertThat(clip.meta.narrowEngine).isTrue() // the web's isNarrowEngine: anything but espeak
    val phrase = clip.phrases[0]
    assertThat(phrase.finalContour).isEqualTo("rising-falling")
    assertThat(phrase.contour).isEqualTo(Contour.UNKNOWN)
    val unit = phrase.intonationUnits.single()
    assertThat(unit.type).isEqualTo("question_tag")
    assertThat(unit.unitType).isEqualTo(UnitType.UNKNOWN)
    assertThat(unit.contour).isEqualTo(Contour.UNKNOWN)
    assertThat(unit.expected).isEqualTo(Contour.UNKNOWN)
    assertThat(phrase.wordClasses).containsExactly("content", "particle", "content", "function", "content").inOrder()
    assertThat(phrase.wordClass(1)).isEqualTo(WordClass.UNKNOWN)
    assertThat(phrase.words[0].boundaryLinkType).isEqualTo("x")
    assertThat(phrase.words[0].linkType).isEqualTo(LinkType.UNKNOWN)
    // an unknown label is kept, and a repeated one appears once
    assertThat(phrase.words[4].changes).containsExactly(ChangeLabel("flapping"), ChangeLabel("new_label")).inOrder()
  }

  @Test
  fun optionalValuesOfTheWrongTypeReadAsAbsent() {
    var doc = AnalysisFixtures.editWords(AnalysisFixtures.mkAnalysis()) { word ->
      if (word["word"] != JsonPrimitive("that")) return@editWords word
      word + mapOf(
        "word" to JsonNull,
        "diff_cost" to json("0.8"),
        "low_confidence" to json("yes"),
        "boundary_link_next" to json(1),
        "phenomena" to json("t_deletion"),
        "dict_ipa" to json(5),
        "attracted_count" to json(1.5),
        "form" to json(mapOf("ipa" to 1, "strong_ipa" to "ðæt", "weak" to true)),
        "variant_labels" to json(listOf("vowel_reduction", 3, "", "vowel_reduction")),
        "canonical_aligned" to json(
          listOf(
            listOf("t", "a", 1), listOf("", 0.1, 0.2), listOf(1, 2, 3), "x", listOf("ð"),
            listOf("æ", 0.46, 0.51, "a fourth cell"), listOf("t", 0.6, 0.55),
          ),
        ),
      )
    }
    doc = AnalysisFixtures.editSegment(doc, 0) { segment ->
      segment + mapOf(
        "text" to json("  does that work  "),
        "emphasis_word_idx" to json(9),
        "prominence" to json(listOf(0.4, "x", 1.0)),
        "word_classes" to json(listOf("function", 2, "content")),
        "f0_track" to json(listOf(listOf(0.0, 110.0), listOf("t", 1), listOf(0.1), "x", listOf(0.2, 112.0))),
        "f0_stats" to json("rising"),
        "rhythm" to json(mapOf("npvi" to 40.0, "n_intervals" to 3)),
        "intonation_units" to json(
          listOf(
            "not a unit",
            mapOf("start" to 0.0, "end" to 0.5, "words" to listOf(2, 1)),
            mapOf("start" to 0.0, "end" to 0.5, "words" to listOf(0, 3)),
            mapOf("start" to "0", "end" to 0.5, "words" to listOf(0, 1)),
            mapOf("start" to 0.0, "end" to 1.1, "words" to listOf(0, 2), "uptalk" to "no", "type" to 4),
          ),
        ),
      )
    }
    doc = JsonObject(
      doc + mapOf(
        "meta" to json(
          mapOf("duration" to -1, "rules_version" to 2.5, "dialogue_separation" to mapOf("applied" to "yes")),
        ),
        "summary" to json(
          mapOf(
            "phenomena_counts" to mapOf("flapping" to 2, "linking" to -1, "x" to 1.5, "y" to "3"),
            "metrics" to null,
          ),
        ),
      ),
    )
    val clip = parse(AnalysisFixtures.encode(doc))

    val that = clip.phrases[0].words[1]
    assertThat(that.text).isEmpty()
    assertThat(that.diffCost).isEqualTo(0.0)
    assertThat(that.lowConfidence).isFalse()
    assertThat(that.boundaryLinkNext).isFalse()
    assertThat(that.changes).isEmpty()
    assertThat(that.dictIpa).isNull()
    assertThat(that.attractedCount).isEqualTo(0)
    assertThat(that.form).isNull()
    assertThat(that.variantLabels).containsExactly(ChangeLabel("vowel_reduction"))
    // only well-formed rows survive; a fourth cell is ignored and an end before its start is raised
    assertThat(that.canonicalPhones)
      .containsExactly(TimedPhone("æ", 0.46, 0.51), TimedPhone("t", 0.6, 0.6)).inOrder()

    val phrase = clip.phrases[0]
    assertThat(phrase.text).isEqualTo("does that work")
    assertThat(phrase.emphasisWord).isNull()
    assertThat(phrase.prominence).isNull()
    assertThat(phrase.wordClasses).isNull()
    assertThat(phrase.f0!!.timesS.toList()).containsExactly(0.0f, 0.2f).inOrder()
    assertThat(phrase.f0Stats).isNull()
    assertThat(phrase.finalContour).isNull()
    assertThat(phrase.rhythm).isNull()
    val unit = phrase.intonationUnits.single()
    assertThat(unit.firstWord to unit.lastWord).isEqualTo(0 to 2)
    assertThat(unit.uptalk).isNull()
    assertThat(unit.type).isNull()
    assertThat(unit.text).isEmpty()

    assertThat(clip.meta.durationS).isEqualTo(3.0) // the last phrase end
    assertThat(clip.meta.rulesVersion).isNull()
    assertThat(clip.meta.dialogueSeparation).isNull()
    assertThat(clip.meta.dialogue).isNull()
    assertThat(clip.summary.changeCounts).containsExactly(ChangeLabel("flapping"), 2)
    assertThat(clip.summary.metrics).isEmpty()
  }

  @Test
  fun nonFiniteNumbersReadAsAbsentWhereOptional() {
    val mark = "__nf__"
    val doc = AnalysisFixtures.editSegment(AnalysisFixtures.mkAnalysis(), 1) { segment ->
      segment + mapOf(
        "f0_track" to json(listOf(listOf(2.0, mark), listOf(2.1, 129.0), listOf(mark, 128.0))),
        "f0_stats" to json(mapOf("mean" to mark, "range" to 20.0, "final_contour" to "falling")),
      )
    }
    val withMeta = JsonObject(doc + ("meta" to json(mapOf("duration" to mark))))
    val text = withMeta.toString().replace("\"$mark\"", "NaN")
    val clip = parse(text)
    assertThat(clip.phrases[1].f0!!.timesS.toList()).containsExactly(2.1f)
    assertThat(clip.phrases[1].f0Stats!!.meanHz).isNull()
    assertThat(clip.meta.durationS).isEqualTo(3.0)
    assertThat(parse(withMeta.toString().replace("\"$mark\"", "-Infinity")).meta.durationS).isEqualTo(3.0)
  }

  @Test
  fun anEmptyClipIsValid() {
    val clip = parse("""{"segments": []}""")
    assertThat(clip.phrases).isEmpty()
    assertThat(clip.wordCount).isEqualTo(0)
    assertThat(clip.meta.durationS).isEqualTo(0.0)
    assertThat(clip.meta.engine).isEqualTo(PhoneEngine.ESPEAK)
    assertThat(clip.summary.changeCounts).isEmpty()
    assertThat(clip.summary.measured).isFalse()
  }

  @Test
  fun timesAreKeptAndAnEndBeforeItsStartIsRaised() {
    val clip = parse(
      """{"segments": [{"start": 5, "end": 4.5, "words": [{"word": "hi", "start": 4.9, "end": 4.8}]},
        {"start": 6, "end": 7, "words": null}]}""",
    )
    assertThat(clip.phrases[0].endS).isEqualTo(5.0)
    assertThat(clip.phrases[0].words.single().endS).isEqualTo(4.9)
    assertThat(clip.phrases[1].words).isEmpty()
    assertThat(clip.meta.durationS).isEqualTo(7.0)
  }

  @Test
  fun changeCountsAreCountedFromTheWordsWhenTheSummaryHasNone() {
    val doc = JsonObject(AnalysisFixtures.analysis(Variant.RULES_3) - "summary")
    val clip = parse(AnalysisFixtures.encode(doc))
    assertThat(clip.summary.changeCounts).isEqualTo(parse(Variant.RULES_3).summary.changeCounts)
    assertThat(clip.summary.metrics).isEmpty()
  }

  @Test
  fun everyMalformedFileIsCorrupt() {
    for (case in Malformed.entries) {
      val gzip = case == Malformed.BAD_GZIP || case == Malformed.TRUNCATED_GZIP
      assertCorrupt(AnalysisFixtures.malformed(case), gzip)
    }
  }

  @Test
  fun corruptMessagesNameOnlyAPath() {
    val word = assertCorrupt(AnalysisFixtures.malformed(Malformed.WORD_WITHOUT_END))
    assertThat(word).hasMessageThat().isEqualTo("segments[1].words[1].end: not a finite number")
    val segment = assertCorrupt(AnalysisFixtures.malformed(Malformed.SEGMENT_NOT_AN_OBJECT))
    assertThat(segment).hasMessageThat().isEqualTo("segments[1]: not an object")
    val start = assertCorrupt(AnalysisFixtures.malformed(Malformed.SEGMENT_START_NAN))
    assertThat(start).hasMessageThat().isEqualTo("segments[1].start: not a finite number")
    assertThat(assertCorrupt(AnalysisFixtures.malformed(Malformed.WORDS_NOT_AN_ARRAY)))
      .hasMessageThat().isEqualTo("segments[0].words: not an array")
  }

  @Test
  fun theCompactRenditionIsTheSameAnalysisGzipped() {
    val plain = parse(Variant.RULES_3)
    val compact = parse(AnalysisFixtures.bytes(Variant.RULES_3, gzip = true), gzip = true)
    assertSameClip(compact, plain)
    // a plain file read as gzip is corrupt, and gzip read as plain is not JSON
    assertCorrupt(AnalysisFixtures.bytes(Variant.RULES_3), gzip = true)
    assertCorrupt(AnalysisFixtures.bytes(Variant.RULES_3, gzip = true), gzip = false)
  }

  @Test
  fun theDecodedSizeIsCapped() {
    val bytes = AnalysisFixtures.bytes(Variant.MK_ANALYSIS)
    parse(bytes, max = bytes.size.toLong())
    assertCorrupt(bytes, max = bytes.size - 1L)
    // a gzip bomb: a few KB that inflate far past the cap
    val bomb = AnalysisFixtures.gzip(("{\"segments\": [" + " ".repeat(4_000_000) + "]}").toByteArray())
    assertThat(bomb.size).isLessThan(64 * 1024)
    assertCorrupt(bomb, gzip = true, max = 1_000_000)
    parse(bomb, gzip = true, max = 5_000_000)
  }

  @Test
  fun theStreamIsReadToTheEndAndNeverClosed() {
    for (gzip in listOf(false, true)) {
      val stream = Tracking(ByteArrayInputStream(AnalysisFixtures.bytes(Variant.RULES_3, gzip)))
      AnalysisParser.parse(JOB, REVISION, stream, gzip)
      assertThat(stream.closed).isFalse()
      assertThat(stream.read()).isEqualTo(-1)
    }
  }

  @Test
  fun aReadErrorIsCorrupt() {
    val failing = object : InputStream() {
      private var left = 100
      override fun read(): Int = if (left-- > 0) '{'.code else throw IOException("disk gone")
    }
    val error = runCatching { AnalysisParser.parse(JOB, REVISION, failing, gzip = false) }.exceptionOrNull()
    assertThat(error).isInstanceOf(ClipDataException::class.java)
    assertThat(error!!.cause).isInstanceOf(IOException::class.java)
  }

  private class Tracking(input: InputStream) : java.io.FilterInputStream(input) {
    var closed = false
    override fun close() {
      closed = true
      super.close()
    }
  }
}
