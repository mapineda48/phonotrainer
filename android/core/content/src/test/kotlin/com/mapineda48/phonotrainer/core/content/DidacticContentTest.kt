package com.mapineda48.phonotrainer.core.content

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryHit
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** DidacticContent over the bundled asset (architecture 15.3; spec F-14, F-15, F-16). */
@OptIn(ExperimentalCoroutinesApi::class)
class DidacticContentTest {
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val content = DidacticContentImpl(SourceAssets(), scope, UnconfinedTestDispatcher(scope.testScheduler))

  @Test
  fun searchingSchwaFindsTheEntry() {
    // F-14-AC3.
    val hits = content.search("schwa")

    assertThat(hits).contains(
      GlossaryHit("schwa", "Schwa", "The short, relaxed vowel [ə], as in the first sound of about."),
    )
    assertThat(hits.first().key).isEqualTo("schwa")
  }

  @Test
  fun searchingXyzzyFindsNothing() {
    // F-14-AC4: the page then shows "No term matches “xyzzy”."
    assertThat(content.search("xyzzy")).isEmpty()
  }

  @Test
  fun searchIgnoresCaseAndSurroundingSpace() {
    assertThat(content.search("  SCHWA ")).isEqualTo(content.search("schwa"))
  }

  @Test
  fun searchLooksInTheExplanationsToo() {
    assertThat(content.search("fundamental frequency").map { it.key }).containsExactly("f0")
  }

  @Test
  fun aBlankSearchListsEveryTermThenEveryMeasureEachByTitle() {
    val all = content.search("")
    val terms = all.takeWhile { ':' !in it.key }
    val measures = all.drop(terms.size)

    assertThat(content.search("   ")).isEqualTo(all)
    // The "{n} of {total} terms" count of "Words we use": 27 terms and 12 measures, no IPA symbol.
    assertThat(terms).hasSize(27)
    assertThat(measures).hasSize(12)
    assertThat(measures.all { it.key.startsWith("metric:") }).isTrue()
    assertThat(terms.map { it.title.lowercase() }).isInOrder()
    assertThat(measures.map { it.title.lowercase() }).isInOrder()
  }

  @Test
  fun aHitShowsTheFirstSentenceOfTheExplanation() {
    val weakForm = content.search("weak form").first { it.key == "weak-form" }

    assertThat(weakForm.snippet)
      .isEqualTo("Small grammar words (to, of, and, can, you…) have a strong form and a weak form.")
    assertThat(DidacticIndex.firstSentence("One sound")).isEqualTo("One sound")
    assertThat(DidacticIndex.firstSentence("A [ə]. Then more")).isEqualTo("A [ə].")
  }

  @Test
  fun theGlossaryServesTermsSymbolsAndMeasures() {
    val tap = content.glossary("ipa:ɾ")

    assertThat(tap?.title).isEqualTo("[ɾ] alveolar tap")
    assertThat(tap?.learnMore).isEqualTo(LinkTarget.Phenomenon(ChangeLabel("flapping")))
    assertThat(tap?.learnMoreLabel).isEqualTo("See the change it belongs to")
    assertThat(content.glossary("schwa")?.example).isEqualTo("banana → bənænə")
    assertThat(content.glossary("metric:deviate")?.learnMore).isEqualTo(LinkTarget.Learn)
    assertThat(content.glossary("no-such-term")).isNull()
  }

  @Test
  fun aLessonCarriesItsDemoAndTipWhereTheWebGivesThem() {
    val flapping = content.lesson(ChangeLabel("flapping"))

    assertThat(flapping?.demo?.word).isEqualTo("water")
    assertThat(flapping?.demo?.from).isEqualTo("t")
    assertThat(flapping?.demo?.to).isEqualTo("ɾ")
    // F-15-AC3: "For Spanish speakers" only where the report gives one.
    assertThat(content.lesson(ChangeLabel("vowel_reduction"))?.spanishTip).isNotNull()
    assertThat(content.lesson(ChangeLabel("linking"))?.spanishTip).isNull()
    assertThat(content.lesson(ChangeLabel("word_elision"))?.demo).isNull()
    assertThat(content.lesson(ChangeLabel("no_such_change"))).isNull()
  }

  @Test
  fun aPhoneIsFoundWithoutItsStressAndLengthMarks() {
    val tap = content.phone("ɾ")

    assertThat(tap?.name).isEqualTo("alveolar tap")
    assertThat(content.phone("ˈɾ")).isEqualTo(tap)
    assertThat(content.phone("iː")?.symbol).isEqualTo("i")
    assertThat(content.phone("ˌt̚")?.symbol).isEqualTo("t̚")
    assertThat(content.phone("q")).isNull()
  }

  @Test
  fun theIpaLayoutHasEveryGrid() {
    val layout = content.ipaLayout()

    assertThat(layout.places.map { it.key }).containsAtLeast("bilabial", "alveolar", "velar", "glottal")
    assertThat(layout.consonantRows.first().cells.getValue("alveolar")).containsExactly("t", "d").inOrder()
    assertThat(layout.allophonePlaces.map { it.key }).isNotEmpty()
    assertThat(layout.vowelColumns.map { it.detail }.toSet()).containsExactly("")
    assertThat(layout.reducedVowels).contains("ə")
    assertThat(layout.glidingGroups.first().detail).isNotEmpty()
    assertThat(layout.placed).hasSize(70)
  }

  @Test
  fun decodingStartsOnTheDispatcherAndHappensOnce() = runTest {
    val assets = SourceAssets()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val lazyContent = DidacticContentImpl(assets, this, dispatcher)

    assertThat(assets.reads).isEqualTo(0)
    advanceUntilIdle()
    assertThat(assets.reads).isEqualTo(1)
    lazyContent.search("schwa")
    lazyContent.phone("t")
    assertThat(assets.reads).isEqualTo(1)
  }

  @Test
  fun aCallBeforeTheWarmUpDecodesOnceForBoth() = runTest {
    val assets = SourceAssets()
    val early = DidacticContentImpl(assets, this, StandardTestDispatcher(testScheduler))

    assertThat(early.glossary("schwa")).isNotNull()
    advanceUntilIdle()
    assertThat(assets.reads).isEqualTo(1)
  }

  @Test
  fun anAssetThatDoesNotDecodeFailsAtFirstUseNotAtStart() = runTest {
    val broken = DidacticContentImpl({ "{}" }, this, StandardTestDispatcher(testScheduler))

    advanceUntilIdle()
    assertThrows(IllegalArgumentException::class.java) { broken.glossary("schwa") }
  }
}
