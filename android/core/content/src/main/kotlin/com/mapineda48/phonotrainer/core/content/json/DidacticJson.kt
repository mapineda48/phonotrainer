package com.mapineda48.phonotrainer.core.content.json

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// didactic.json schema 1, field by field (android/tools/didactic/schema.mjs is the exact definition), and its mapping
// to the domain types of work-plan Appendix A.8.

internal const val DIDACTIC_SCHEMA = 1

/**
 * didactic.json is read strictly: an unknown field, a missing one or another schema fails, so the extractor and these
 * readers can never disagree silently (the JVM decode test of architecture 15.2). The asset is bundled, so a failure
 * is a build defect, never data from outside.
 */
internal val DidacticJsonFormat = Json {
  ignoreUnknownKeys = false
  classDiscriminator = "kind"
  explicitNulls = true
}

@Serializable
internal data class DidacticJson(
  val schema: Int,
  val sources: List<SourceJson>,
  val glossary: Map<String, GlossaryEntryJson>,
  val symbolPhenomenon: Map<String, String>,
  val lessons: Map<String, LessonJson>,
  val ipaLayout: IpaLayoutJson,
  val phones: List<PhoneJson>,
)

@Serializable
internal data class SourceJson(val path: String, val sha256: String)

@Serializable
internal data class GlossaryEntryJson(
  val title: String,
  val body: String,
  val example: String?,
  val learnMore: LinkTargetJson?,
  val learnMoreLabel: String?,
)

/** A link target as the extractor writes it: an object whose `kind` names the destination. */
@Serializable
internal sealed interface LinkTargetJson {
  @Serializable
  @SerialName("learn")
  data object Learn : LinkTargetJson

  @Serializable
  @SerialName("phenomenon")
  data class Phenomenon(val label: String) : LinkTargetJson

  @Serializable
  @SerialName("ipa")
  data class Ipa(val symbol: String?) : LinkTargetJson

  @Serializable
  @SerialName("practice")
  data class Practice(val focus: String?) : LinkTargetJson

  @Serializable
  @SerialName("insights")
  data object Insights : LinkTargetJson
}

@Serializable
internal data class LessonJson(
  val listenFor: String,
  val demo: MouthDemoJson?,
  val spanishTip: String?,
  val note: String?,
)

@Serializable
internal data class MouthDemoJson(
  val word: String,
  val dictionary: List<String>,
  val said: List<String>,
  val from: String,
  val to: String?,
)

@Serializable
internal data class IpaLayoutJson(
  val places: List<IpaColumnJson>,
  val consonantRows: List<IpaGridRowJson>,
  val allophonePlaces: List<IpaColumnJson>,
  val allophoneRows: List<IpaGridRowJson>,
  val syllabics: List<String>,
  val vowelColumns: List<IpaColumnJson>,
  val vowelRows: List<IpaGridRowJson>,
  val reducedVowels: List<String>,
  val glidingGroups: List<IpaGroupJson>,
  val placed: List<String>,
  val unplaced: List<String>,
)

@Serializable
internal data class IpaColumnJson(val key: String, val label: String, val detail: String)

@Serializable
internal data class IpaGridRowJson(val label: String, val detail: String, val cells: Map<String, List<String>>)

@Serializable
internal data class IpaGroupJson(val label: String, val detail: String, val symbols: List<String>)

@Serializable
internal data class PhoneJson(
  val symbol: String,
  val name: String,
  val example: String,
  val cue: String,
  val manner: String,
  val voiced: Boolean,
  val gestures: List<GestureJson>,
  val carry: Map<String, Double>?,
)

@Serializable
internal data class GestureJson(val at: Double, val pose: Map<String, Double>)

/** Decodes [text] strictly and refuses a schema this app does not read. */
internal fun decodeDidactic(text: String): DidacticJson {
  val decoded = DidacticJsonFormat.decodeFromString(DidacticJson.serializer(), text)
  if (decoded.schema != DIDACTIC_SCHEMA) {
    throw ContentFormatException("didactic.json schema ${decoded.schema}; this app reads schema $DIDACTIC_SCHEMA")
  }
  return decoded
}

internal fun GlossaryEntryJson.toDomain(key: String): GlossaryEntry =
  GlossaryEntry(key, title, body, example, learnMore?.toDomain(), learnMoreLabel)

internal fun LinkTargetJson.toDomain(): LinkTarget = when (this) {
  LinkTargetJson.Learn -> LinkTarget.Learn
  is LinkTargetJson.Phenomenon -> LinkTarget.Phenomenon(ChangeLabel(label))
  is LinkTargetJson.Ipa -> LinkTarget.Ipa(symbol)
  is LinkTargetJson.Practice -> LinkTarget.Practice(focus?.let(::ChangeLabel))
  LinkTargetJson.Insights -> LinkTarget.Insights
}

internal fun LessonJson.toDomain(change: String): LessonContent = LessonContent(
  change = ChangeLabel(change),
  listenFor = listenFor,
  demo = demo?.let { MouthDemo(it.word, it.dictionary, it.said, it.from, it.to) },
  spanishTip = spanishTip,
  note = note,
)

internal fun IpaLayoutJson.toDomain(): IpaLayout = IpaLayout(
  places = places.map { it.toDomain() },
  consonantRows = consonantRows.map { it.toDomain() },
  allophonePlaces = allophonePlaces.map { it.toDomain() },
  allophoneRows = allophoneRows.map { it.toDomain() },
  syllabics = syllabics,
  vowelColumns = vowelColumns.map { it.toDomain() },
  vowelRows = vowelRows.map { it.toDomain() },
  reducedVowels = reducedVowels,
  glidingGroups = glidingGroups.map { IpaGroup(it.label, it.detail, it.symbols) },
  placed = placed,
  unplaced = unplaced,
)

private fun IpaColumnJson.toDomain() = IpaColumn(key, label, detail)

private fun IpaGridRowJson.toDomain() = IpaGridRow(label, detail, cells)

internal fun PhoneJson.toDomain(): PhoneArticulation = PhoneArticulation(
  symbol = symbol,
  name = name,
  example = example,
  cue = cue,
  manner = manner,
  voiced = voiced,
  gestures = gestures.map { Gesture(it.at, it.pose) },
  carry = carry,
)
