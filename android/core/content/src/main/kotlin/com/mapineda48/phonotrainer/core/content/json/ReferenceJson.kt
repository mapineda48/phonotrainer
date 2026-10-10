package com.mapineda48.phonotrainer.core.content.json

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FamilyInfo
import com.mapineda48.phonotrainer.core.domain.model.MetricReference
import com.mapineda48.phonotrainer.core.domain.model.PracticeAdvice
import com.mapineda48.phonotrainer.core.domain.model.Reference
import com.mapineda48.phonotrainer.core.domain.model.Register
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The body of GET /api/reference (and /api/phone/reference, byte for byte; server-contract 6.8), snake_case as the
// server writes it, and its mapping to the domain Reference of work-plan Appendix A.8.

/**
 * The body carries more than the phone reads (verdicts, options, review, recording), and a newer computer may add
 * fields (server-contract 1: additive only), so unknown keys are ignored. Every field the phone reads is required: a
 * body without one is refused rather than replacing a complete snapshot with a partial one.
 */
internal val ReferenceJsonFormat = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ReferenceJson(
  @SerialName("api_version") val apiVersion: Int,
  val families: List<FamilyJson>,
  @SerialName("family_of") val familyOf: Map<String, String>,
  val labels: Map<String, String>,
  val descriptions: Map<String, String>,
  val practice: Map<String, PracticeJson>,
  @SerialName("lexical_practice") val lexicalPractice: Map<String, PracticeJson>,
  @SerialName("link_types") val linkTypes: List<String>,
  @SerialName("ipa_tokens") val ipaTokens: List<String>,
  @SerialName("metrics_reference") val metricsReference: Map<String, MetricJson>,
  @SerialName("metric_labels") val metricLabels: Map<String, String>,
)

@Serializable
internal data class FamilyJson(
  val key: String,
  val label: String,
  val members: List<String>,
  @SerialName("member_labels") val memberLabels: List<String>,
)

@Serializable
internal data class PracticeJson(val practice: String, val register: String, val why: String)

@Serializable
internal data class MetricJson(
  val low: Double?,
  val high: Double?,
  val display: String,
  val cite: String,
  val source: String,
  val note: String,
)

/** Decodes and maps a reference body; throws [IllegalArgumentException] (a [ContentFormatException] or a
 *  `SerializationException`) when it is not one this app can read. */
internal fun decodeReference(text: String): Reference =
  ReferenceJsonFormat.decodeFromString(ReferenceJson.serializer(), text).toDomain()

/** [decodeReference], or null when [text] is not a reference body this app can read. */
internal fun decodeReferenceOrNull(text: String): Reference? = try {
  decodeReference(text)
} catch (_: IllegalArgumentException) {
  null
}

internal fun ReferenceJson.toDomain(): Reference = Reference(
  apiVersion = apiVersion,
  families = families.map { FamilyInfo(familyOf(it.key), it.label, it.members.map(::ChangeLabel), it.memberLabels) },
  familyOf = familyOf.entries.associate { (label, key) -> ChangeLabel(label) to familyOf(key) },
  labels = labels.mapKeys { ChangeLabel(it.key) },
  descriptions = descriptions.mapKeys { ChangeLabel(it.key) },
  practice = practice.entries.associate { (label, advice) -> ChangeLabel(label) to advice.toDomain("practice.$label") },
  lexicalPractice = lexicalPractice.mapValues { (form, advice) -> advice.toDomain("lexical_practice.$form") },
  linkTypes = linkTypes,
  ipaTokens = ipaTokens,
  metricsReference = metricsReference.mapValues { (_, m) ->
    MetricReference(m.low, m.high, m.display, m.cite, m.source, m.note)
  },
  metricLabels = metricLabels,
)

/** The server's family keys; a family a newer computer adds has no hue until the app knows it (OTHER). */
private fun familyOf(key: String): Family = when (key) {
  "reduction" -> Family.REDUCTION
  "td" -> Family.TD
  "assimilation" -> Family.ASSIMILATION
  "boundary" -> Family.BOUNDARY
  else -> Family.OTHER
}

/** The badge and the register are closed sets the UI words; a value outside them refuses the whole body. */
private fun PracticeJson.toDomain(where: String): PracticeAdvice = PracticeAdvice(
  advice = when (practice) {
    "produce" -> Advice.PRODUCE
    "understand" -> Advice.UNDERSTAND
    else -> throw ContentFormatException("$where: unknown practice \"$practice\"")
  },
  register = when (register) {
    "universal" -> Register.UNIVERSAL
    "casual" -> Register.CASUAL
    "marked" -> Register.MARKED
    else -> throw ContentFormatException("$where: unknown register \"$register\"")
  },
  why = why,
)
