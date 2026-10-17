package com.mapineda48.phonotrainer.core.testing.computer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test
import java.io.File

/**
 * `GET /api/phone/reference` (server-contract 6.8): by default the fake serves the reference snapshot the app bundles,
 * which is the server's `/api/reference` body, so a sync pass against the fake stores a reference the phone's
 * `ReferenceRepositoryImpl` accepts (wave-1 review, WP-05a item 2).
 */
class ReferenceBodyTest {
  private val computer = FakeComputer(clock = FakeComputerClock.frozen()).start()
  private val phone = TestPhone(computer, computer.pairDevice())

  @After
  fun tearDown() = computer.close()

  @Test
  fun theDefaultBodyIsTheSnapshotTheAppBundles() {
    val served = phone.get("/api/phone/reference")

    assertThat(served.code).isEqualTo(200)
    assertThat(Json.parseToJsonElement(served.text)).isEqualTo(Json.parseToJsonElement(bundledSnapshot().readText()))
  }

  @Test
  fun theDefaultBodyHasEveryFieldThePhoneReads() {
    val reference = PHONE_FORMAT.decodeFromString(PhoneReference.serializer(), phone.get("/api/phone/reference").text)

    assertThat(reference.apiVersion).isEqualTo(6)
    assertThat(reference.families.map { it.key }).containsExactly("reduction", "td", "assimilation", "boundary")
    assertThat(reference.labels).isNotEmpty()
    assertThat(reference.familyOf.keys).containsAtLeastElementsIn(reference.families.flatMap { it.members })
    assertThat(reference.descriptions.keys).containsAtLeastElementsIn(reference.labels.keys)
    val advice = reference.practice.values + reference.lexicalPractice.values
    assertThat(advice.map { it.practice }.toSet()).containsAnyOf("produce", "understand")
    assertThat(advice.map { it.practice }.toSet() - setOf("produce", "understand")).isEmpty()
    assertThat(advice.map { it.register }.toSet() - setOf("universal", "casual", "marked")).isEmpty()
    assertThat(reference.ipaTokens).isNotEmpty()
    assertThat(reference.linkTypes).isNotEmpty()
    assertThat(reference.metricsReference).isNotEmpty()
    assertThat(reference.metricLabels).isNotEmpty()
  }

  @Test
  fun theDefaultBodyDeclaresTheApiVersionTheListingAnnounces() {
    computer.enableCompleteTier()

    val listing = phone.get("/api/phone/library").json
    val served = phone.get("/api/phone/reference")

    assertThat(served.headers["ETag"]).isEqualTo("\"api-7\"")
    assertThat(served.json["api_version"]!!.prim().int).isEqualTo(7)
    assertThat(served.json["api_version"]).isEqualTo(listing["api_version"])
    assertThat(served.json.keys).isEqualTo(Json.parseToJsonElement(bundledSnapshot().readText()).jsonObject.keys)
  }

  @Test
  fun aBodyATestSetsIsServedAsItIs() {
    computer.referenceJson = """{"api_version": 6, "families": []}"""

    assertThat(phone.get("/api/phone/reference").text).isEqualTo("""{"api_version": 6, "families": []}""")
    assertThat(computer.referenceJson).isEqualTo("""{"api_version": 6, "families": []}""")
  }

  /** The required fields of the phone's reference decoder (`:core:content`'s `ReferenceJson`), unknown keys ignored. */
  @Serializable
  private data class PhoneReference(
    @SerialName("api_version") val apiVersion: Int,
    val families: List<FamilyBody>,
    @SerialName("family_of") val familyOf: Map<String, String>,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    val practice: Map<String, PracticeBody>,
    @SerialName("lexical_practice") val lexicalPractice: Map<String, PracticeBody>,
    @SerialName("link_types") val linkTypes: List<String>,
    @SerialName("ipa_tokens") val ipaTokens: List<String>,
    @SerialName("metrics_reference") val metricsReference: Map<String, MetricBody>,
    @SerialName("metric_labels") val metricLabels: Map<String, String>,
  )

  @Serializable
  private data class FamilyBody(
    val key: String,
    val label: String,
    val members: List<String>,
    @SerialName("member_labels") val memberLabels: List<String>,
  )

  @Serializable
  private data class PracticeBody(val practice: String, val register: String, val why: String)

  @Serializable
  private data class MetricBody(
    val low: Double?,
    val high: Double?,
    val display: String,
    val cite: String,
    val source: String,
    val note: String,
  )

  private companion object {
    val PHONE_FORMAT = Json { ignoreUnknownKeys = true }

    const val SNAPSHOT = "core/content/src/main/assets/reference/reference.json"

    /** `:core:content`'s bundled snapshot, found from the module directory the test runs in. */
    fun bundledSnapshot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .map { File(it, SNAPSHOT) }
      .firstOrNull { it.isFile }
      ?: error("the bundled reference snapshot ($SNAPSHOT) was not found above ${System.getProperty("user.dir")}")
  }
}
