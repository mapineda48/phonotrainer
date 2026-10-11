package com.mapineda48.phonotrainer.core.content

import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.core.content.json.ContentFormatException
import com.mapineda48.phonotrainer.core.content.json.decodeReference
import com.mapineda48.phonotrainer.core.content.json.decodeReferenceOrNull
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.Register
import com.mapineda48.phonotrainer.core.domain.model.familyFor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertThrows
import org.junit.Test

/** The bundled reference snapshot (architecture 15.4) and the reader every replacement goes through. */
class ReferenceSnapshotTest {
  private val text = SourceAssets.reference
  private val reference = decodeReference(text)

  @Test
  fun theSnapshotHasSixteenLabels() {
    assertThat(reference.labels).hasSize(16)
    assertThat(reference.descriptions.keys).containsExactlyElementsIn(reference.labels.keys)
    assertThat(reference.labels.getValue(ChangeLabel("nt_reduction"))).isEqualTo("nt → n")
  }

  @Test
  fun theSnapshotHasTheFourFamilies() {
    assertThat(reference.families.map { it.family })
      .containsExactly(Family.REDUCTION, Family.TD, Family.ASSIMILATION, Family.BOUNDARY).inOrder()
    reference.families.forEach { family ->
      assertThat(reference.labels.keys).containsAtLeastElementsIn(family.members)
      assertThat(family.memberLabels).hasSize(family.members.size)
      family.members.forEach { assertThat(reference.familyOf[it]).isEqualTo(family.family) }
    }
    assertThat(reference.familyOf).hasSize(14)
  }

  @Test
  fun everyLabelHasPracticeAdvice() {
    assertThat(reference.practice.keys).containsExactlyElementsIn(reference.labels.keys)
    reference.practice.values.forEach { assertThat(it.why).isNotEmpty() }
    assertThat(reference.practice.getValue(ChangeLabel("flapping")).advice).isEqualTo(Advice.PRODUCE)
    assertThat(reference.practice.getValue(ChangeLabel("th_stopping")).advice).isEqualTo(Advice.UNDERSTAND)
    assertThat(reference.practice.getValue(ChangeLabel("th_stopping")).register).isEqualTo(Register.MARKED)
    assertThat(reference.lexicalPractice.getValue("gonna").advice).isEqualTo(Advice.PRODUCE)
  }

  @Test
  fun everyLabelWearsTheMarkOfItsFamily() {
    assertThat(reference.familyFor(ChangeLabel("flapping"))).isEqualTo(Family.TD)
    assertThat(reference.familyFor(ChangeLabel("contraction_lex"))).isEqualTo(Family.LEXICAL)
    assertThat(reference.familyFor(ChangeLabel("word_elision"))).isEqualTo(Family.OTHER)
  }

  @Test
  fun theSnapshotIsForAComputerThePhoneAccepts() {
    // Server-contract 1, rule 3: the phone requires api_version 6 or newer.
    assertThat(reference.apiVersion).isAtLeast(6)
  }

  @Test
  fun theIpaTokensAreLongestFirstAndTheMetricsCarryTheirRanges() {
    assertThat(reference.ipaTokens.map { -it.length }).isInOrder()
    assertThat(reference.ipaTokens).containsAtLeast("aɪə", "tʃ", "ɾ̃")
    val deviate = reference.metricsReference.getValue("deviate")
    assertThat(deviate.low).isEqualTo(60.0)
    assertThat(deviate.high).isNull()
    assertThat(reference.metricLabels).containsKey("deviate")
    assertThat(reference.linkTypes).contains("consonant")
  }

  @Test
  fun fieldsThePhoneDoesNotReadAreIgnored() {
    val edited = text.editJson { put("a_future_field", JsonPrimitive(true)) }

    assertThat(decodeReference(edited)).isEqualTo(reference)
  }

  @Test
  fun aFamilyThePhoneDoesNotKnowHasNoHue() {
    val edited = text.editJson {
      editObject("family_of") { put("flapping", JsonPrimitive("rhythm")) }
      put(
        "families",
        JsonArray(
          getValue("families").jsonArray + JsonObject(
            mapOf(
              "key" to JsonPrimitive("rhythm"),
              "label" to JsonPrimitive("Rhythm"),
              "members" to JsonArray(listOf(JsonPrimitive("flapping"))),
              "member_labels" to JsonArray(listOf(JsonPrimitive("flapping"))),
            ),
          ),
        ),
      )
    }
    val decoded = decodeReference(edited)

    assertThat(decoded.familyOf[ChangeLabel("flapping")]).isEqualTo(Family.OTHER)
    assertThat(decoded.families.last().family).isEqualTo(Family.OTHER)
  }

  @Test
  fun aBodyWithoutAFieldThePhoneReadsIsRefused() {
    val edited = text.editJson { remove("practice") }

    assertThrows(SerializationException::class.java) { decodeReference(edited) }
    assertThat(decodeReferenceOrNull(edited)).isNull()
  }

  @Test
  fun aBadgeOrRegisterOutsideTheClosedSetsIsRefused() {
    val badge = text.editJson {
      editObject("practice") { editObject("flapping") { put("practice", JsonPrimitive("imitate")) } }
    }
    val register = text.editJson {
      editObject("lexical_practice") { editObject("gonna") { put("register", JsonPrimitive("regional")) } }
    }

    assertThat(assertThrows(ContentFormatException::class.java) { decodeReference(badge) })
      .hasMessageThat().isEqualTo("practice.flapping: unknown practice \"imitate\"")
    assertThat(decodeReferenceOrNull(register)).isNull()
  }

  @Test
  fun aBodyThatIsNotJsonIsRefused() {
    assertThat(decodeReferenceOrNull("<html>")).isNull()
    assertThat(decodeReferenceOrNull("""{"api_version": 6, "families": [], "phenomena": []}""")).isNull()
  }
}
