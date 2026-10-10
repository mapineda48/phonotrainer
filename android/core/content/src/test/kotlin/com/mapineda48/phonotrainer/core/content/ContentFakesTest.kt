package com.mapineda48.phonotrainer.core.content

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.content.json.decodeReference
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeReferenceRepository
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleDidactic
import com.mapineda48.phonotrainer.core.testing.fake.content.SampleReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The fakes of `:core:testing-jvm` (`testing/fake/content/`) stay honest: their samples hold facts of the bundled
 * content, and FakeDidacticContent searches by the real rules, so a screen test on the fake behaves as the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentFakesTest {
  private val bundled: Reference = decodeReference(SourceAssets.reference)
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val real = DidacticContentImpl(SourceAssets(), scope, UnconfinedTestDispatcher(scope.testScheduler))

  @Test
  fun theSampleReferenceHoldsTheFactsOfTheBundledSnapshot() {
    val sample = SampleReference.reference

    assertThat(sample.apiVersion).isEqualTo(bundled.apiVersion)
    assertThat(sample.labels).containsExactlyEntriesIn(bundled.labels).inOrder()
    assertThat(sample.families).containsExactlyElementsIn(bundled.families).inOrder()
    assertThat(sample.familyOf).containsExactlyEntriesIn(bundled.familyOf)
    assertThat(sample.practice.mapValues { it.value.advice to it.value.register })
      .containsExactlyEntriesIn(bundled.practice.mapValues { it.value.advice to it.value.register })
    assertThat(sample.descriptions.keys).containsExactlyElementsIn(bundled.descriptions.keys)
    assertThat(sample.ipaTokens).containsExactlyElementsIn(bundled.ipaTokens).inOrder()
    assertThat(sample.linkTypes).containsExactlyElementsIn(bundled.linkTypes).inOrder()
    assertThat(sample.metricLabels).containsExactlyEntriesIn(bundled.metricLabels)
    assertThat(bundled.metricsReference).containsAtLeastEntriesIn(sample.metricsReference)
    assertThat(bundled.lexicalPractice.keys).containsAtLeastElementsIn(sample.lexicalPractice.keys)
  }

  @Test
  fun everySampleOfTheDidacticContentExistsInTheBundledOne() {
    SampleDidactic.glossary.forEach { (key, entry) ->
      assertThat(real.glossary(key)?.learnMore).isEqualTo(entry.learnMore)
    }
    SampleDidactic.lessons.forEach { (change, lesson) -> assertThat(real.lesson(change)?.demo).isEqualTo(lesson.demo) }
    SampleDidactic.phones.forEach { (symbol, phone) ->
      assertThat(real.phone(symbol)?.let { it.manner to it.voiced }).isEqualTo(phone.manner to phone.voiced)
    }
    assertThat(real.ipaLayout().placed).containsAtLeastElementsIn(SampleDidactic.ipaLayout.placed)
  }

  @Test
  fun theFakeSearchesByTheRealRules() {
    val fake = FakeDidacticContent()
    val sampled = SampleDidactic.glossary.keys

    listOf("", "  ", "schwa", "SCHWA ", "vowel", "dictionary", "xyzzy").forEach { query ->
      assertThat(fake.search(query).map { it.key })
        .containsExactlyElementsIn(real.search(query).map { it.key }.filter { it in sampled })
        .inOrder()
    }
    assertThat(fake.search("schwa").first().snippet).isEqualTo(DidacticIndex.firstSentence(SampleDidactic.schwa.body))
    assertThat(fake.phone("ˈɾ")).isEqualTo(SampleDidactic.phoneTap)
    assertThat(fake.searches).contains("xyzzy")
  }

  @Test
  fun theFakeReferenceRecordsAndAppliesReplacements() = runTest {
    val fake = FakeReferenceRepository()

    fake.replace("{}", SampleReference.API_VERSION + 1)
    fake.onReplace = { _, _ -> null }
    fake.replace("<html>", SampleReference.API_VERSION + 2)

    assertThat(fake.replaced.map { it.apiVersion })
      .containsExactly(SampleReference.API_VERSION + 1, SampleReference.API_VERSION + 2).inOrder()
    assertThat(fake.reference.value.apiVersion).isEqualTo(SampleReference.API_VERSION + 1)
    assertThat(fake.reference.value.labels).isEqualTo(SampleReference.labels)
  }
}
