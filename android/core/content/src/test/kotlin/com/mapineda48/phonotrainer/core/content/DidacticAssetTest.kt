package com.mapineda48.phonotrainer.core.content

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.content.json.ContentFormatException
import com.mapineda48.phonotrainer.core.content.json.decodeDidactic
import com.mapineda48.phonotrainer.core.content.json.decodeReference
import com.mapineda48.phonotrainer.core.content.json.toDomain
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The JVM decode test of architecture 15.2: the bundled didactic.json decodes strictly with the Kotlin model, so a
 * schema change on either side (the extractor or these readers) fails here.
 */
class DidacticAssetTest {
  private val text = SourceAssets.didactic

  @Test
  fun theBundledAssetDecodesStrictly() {
    val json = decodeDidactic(text)

    assertThat(json.schema).isEqualTo(1)
    assertThat(json.glossary).hasSize(117)
    assertThat(json.lessons).hasSize(16)
    assertThat(json.phones).hasSize(70)
    assertThat(json.sources.map { it.path }).contains("web/src/features/learn/content.ts")
    assertThat(json.sources.map { it.sha256 }.all { it.matches(Regex("[0-9a-f]{64}")) }).isTrue()
  }

  @Test
  fun theGlossaryHasTheTermsMeasuresAndSymbolsOfTheSpec() {
    val keys = decodeDidactic(text).glossary.keys

    // Spec F-14: 27 general terms, 12 metrics, about 78 IPA keys.
    assertThat(keys.count { ':' !in it }).isEqualTo(27)
    assertThat(keys.count { it.startsWith("metric:") }).isEqualTo(12)
    assertThat(keys.count { it.startsWith("ipa:") }).isEqualTo(78)
    assertThat(keys).containsAtLeast("schwa", "weak-form", "metric:deviate", "ipa:ɾ", "ipa:iː")
  }

  @Test
  fun thereIsALessonForEveryLabelOfTheReference() {
    val lessons = decodeDidactic(text).lessons.keys
    val labels = decodeReference(SourceAssets.reference).labels.keys.map(ChangeLabel::value)

    assertThat(lessons).containsExactlyElementsIn(labels)
  }

  @Test
  fun everyGestureHoldsTheElevenArticulators() {
    val articulators = setOf(
      "jaw", "lipOpen", "lipRound", "lipTuck", "body", "height", "tip", "tipFront", "root", "velum", "voice",
    )
    val phones = decodeDidactic(text).phones

    phones.flatMap { it.gestures }.forEach { assertThat(it.pose.keys).containsExactlyElementsIn(articulators) }
    // Only /h/ and /ʔ/ keep the shape around them: no gesture of their own, and what they impose instead.
    assertThat(phones.filter { it.gestures.isEmpty() }.map { it.symbol }).containsExactly("ʔ", "h")
    assertThat(phones.filter { it.carry != null }.map { it.symbol }).containsExactly("ʔ", "h")
  }

  @Test
  fun everyLessonDemoUsesPhonesOfTheTable() {
    val json = decodeDidactic(text)
    val symbols = json.phones.map { it.symbol }.toSet()

    json.lessons.values.mapNotNull { it.demo }.forEach { demo ->
      assertThat(symbols).containsAtLeastElementsIn((demo.dictionary + demo.said).toSet())
      assertThat(demo.dictionary).contains(demo.from)
    }
  }

  @Test
  fun theChartPlacesEveryPhoneOfTheTable() {
    val json = decodeDidactic(text)

    assertThat(json.ipaLayout.placed + json.ipaLayout.unplaced)
      .containsExactlyElementsIn(json.phones.map { it.symbol })
  }

  @Test
  fun everyKindOfLinkTargetDecodes() {
    val edited = text.editJson {
      editObject("glossary") {
        editObject("schwa") { put("learnMore", link("practice", "focus" to JsonPrimitive("flapping"))) }
        editObject("weak-form") { put("learnMore", link("practice", "focus" to JsonPrimitive(null as String?))) }
        editObject("phone") { put("learnMore", link("insights")) }
      }
    }
    val glossary = decodeDidactic(edited).glossary

    assertThat(glossary.getValue("schwa").learnMore?.toDomain())
      .isEqualTo(LinkTarget.Practice(ChangeLabel("flapping")))
    assertThat(glossary.getValue("weak-form").learnMore?.toDomain()).isEqualTo(LinkTarget.Practice(null))
    assertThat(glossary.getValue("phone").learnMore?.toDomain()).isEqualTo(LinkTarget.Insights)
    // The kinds the web uses today.
    val today = decodeDidactic(text).glossary
    assertThat(today.getValue("ipa:ɾ").learnMore?.toDomain())
      .isEqualTo(LinkTarget.Phenomenon(ChangeLabel("flapping")))
    assertThat(today.getValue("metric:deviate").learnMore?.toDomain()).isEqualTo(LinkTarget.Learn)
    assertThat(today.getValue("ipa").learnMore?.toDomain()).isEqualTo(LinkTarget.Ipa(null))
  }

  @Test
  fun anUnknownFieldFailsTheDecode() {
    val atTheTop = text.editJson { put("extra", JsonPrimitive(1)) }
    val inAnEntry = text.editJson {
      editObject("glossary") { editObject("schwa") { put("audio", JsonPrimitive("x")) } }
    }

    assertThrows(SerializationException::class.java) { decodeDidactic(atTheTop) }
    assertThrows(SerializationException::class.java) { decodeDidactic(inAnEntry) }
  }

  @Test
  fun aMissingFieldFailsTheDecodeEvenWhenItsValueWouldBeNull() {
    val noExample = text.editJson { editObject("glossary") { editObject("schwa") { remove("example") } } }
    val noLayout = text.editJson { remove("ipaLayout") }

    assertThrows(SerializationException::class.java) { decodeDidactic(noExample) }
    assertThrows(SerializationException::class.java) { decodeDidactic(noLayout) }
  }

  @Test
  fun anUnknownLinkKindFailsTheDecode() {
    val edited = text.editJson { editObject("glossary") { editObject("schwa") { put("learnMore", link("library")) } } }

    assertThrows(SerializationException::class.java) { decodeDidactic(edited) }
  }

  @Test
  fun anotherSchemaIsRefused() {
    val edited = text.editJson { put("schema", JsonPrimitive(2)) }

    val error = assertThrows(ContentFormatException::class.java) { decodeDidactic(edited) }
    assertThat(error).hasMessageThat().contains("schema 2")
  }

  private fun link(kind: String, vararg fields: Pair<String, JsonPrimitive>) =
    JsonObject(mapOf("kind" to JsonPrimitive(kind)) + fields)
}
