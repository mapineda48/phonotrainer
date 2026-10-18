package com.mapineda48.phonotrainer.feature.learn

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.GlossaryEntry
import com.mapineda48.phonotrainer.core.domain.model.IpaColumn
import com.mapineda48.phonotrainer.core.domain.model.IpaGridRow
import com.mapineda48.phonotrainer.core.domain.model.IpaGroup
import com.mapineda48.phonotrainer.core.domain.model.IpaLayout
import com.mapineda48.phonotrainer.core.domain.model.LessonContent
import com.mapineda48.phonotrainer.core.domain.model.LinkTarget
import com.mapineda48.phonotrainer.core.domain.model.MouthDemo
import com.mapineda48.phonotrainer.core.domain.model.PhoneArticulation
import com.mapineda48.phonotrainer.core.testing.fake.content.FakeDidacticContent
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's real bundled didactic content (`:core:content`'s `didactic.json`, extracted from the web sources) as a
 * [FakeDidacticContent], so the screen tests check Learn against what ships: the 16 lessons with their Spanish-speaker
 * tips (F-15-AC3), the whole IPA layout (F-16-AC1), every phone name (F-16-AC2) and every term of "Words we use"
 * (F-14-AC3, AC4). `:core:content`'s own decoder is internal to it, so this reads the same file with the JSON
 * library on the classpath; `:core:content`'s `DidacticAssetTest` keeps the file and its decoder in step.
 */
internal object BundledContent {
  private const val ASSET = "core/content/src/main/assets/didactic/didactic.json"

  private val root: JsonObject by lazy { Json.parseToJsonElement(file().readText()).jsonObject }

  /** A fresh fake over the bundled content (tests may change it). */
  fun didactic(): FakeDidacticContent = FakeDidacticContent(entries = glossary, lessons = lessons, layout = layout, phones = phones)

  val glossary: Map<String, GlossaryEntry> by lazy {
    root.getValue("glossary").jsonObject.mapValues { (key, value) ->
      val entry = value.jsonObject
      GlossaryEntry(
        key = key,
        title = entry.string("title")!!,
        body = entry.string("body")!!,
        example = entry.string("example"),
        learnMore = entry["learnMore"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::link),
        learnMoreLabel = entry.string("learnMoreLabel"),
      )
    }
  }

  val lessons: Map<ChangeLabel, LessonContent> by lazy {
    root.getValue("lessons").jsonObject.entries.associate { (change, value) ->
      val lesson = value.jsonObject
      ChangeLabel(change) to LessonContent(
        change = ChangeLabel(change),
        listenFor = lesson.string("listenFor")!!,
        demo = lesson["demo"]?.takeUnless { it is JsonNull }?.jsonObject?.let { demo ->
          MouthDemo(
            word = demo.string("word")!!,
            dictionary = demo.strings("dictionary"),
            said = demo.strings("said"),
            from = demo.string("from")!!,
            to = demo.string("to"),
          )
        },
        spanishTip = lesson.string("spanishTip"),
        note = lesson.string("note"),
      )
    }
  }

  val layout: IpaLayout by lazy {
    val ipa = root.getValue("ipaLayout").jsonObject
    IpaLayout(
      places = ipa.columns("places"),
      consonantRows = ipa.rows("consonantRows"),
      allophonePlaces = ipa.columns("allophonePlaces"),
      allophoneRows = ipa.rows("allophoneRows"),
      syllabics = ipa.strings("syllabics"),
      vowelColumns = ipa.columns("vowelColumns"),
      vowelRows = ipa.rows("vowelRows"),
      reducedVowels = ipa.strings("reducedVowels"),
      glidingGroups = ipa.getValue("glidingGroups").jsonArray.map {
        val group = it.jsonObject
        IpaGroup(group.string("label")!!, group.string("detail").orEmpty(), group.strings("symbols"))
      },
      placed = ipa.strings("placed"),
      unplaced = ipa.strings("unplaced"),
    )
  }

  val phones: Map<String, PhoneArticulation> by lazy {
    root.getValue("phones").jsonArray.associate {
      val phone = it.jsonObject
      val symbol = phone.string("symbol")!!
      symbol to PhoneArticulation(
        symbol = symbol,
        name = phone.string("name")!!,
        example = phone.string("example")!!,
        cue = phone.string("cue")!!,
        manner = phone.string("manner")!!,
        voiced = phone.getValue("voiced").jsonPrimitive.boolean,
        gestures = emptyList(),
        carry = null,
      )
    }
  }

  /** The "Words we use" rows: every term and measure (not the `ipa:` keys). */
  val wordsWeUse: Int by lazy { glossary.keys.count { ':' !in it || it.startsWith("metric:") } }

  private fun link(target: JsonObject): LinkTarget = when (val kind = target.string("kind")) {
    "learn" -> LinkTarget.Learn
    "phenomenon" -> LinkTarget.Phenomenon(ChangeLabel(target.string("label")!!))
    "ipa" -> LinkTarget.Ipa(target.string("symbol"))
    "practice" -> LinkTarget.Practice(target.string("focus")?.let(::ChangeLabel))
    "insights" -> LinkTarget.Insights
    else -> error("unknown link kind $kind")
  }

  private fun JsonObject.string(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

  private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

  private fun JsonObject.columns(key: String): List<IpaColumn> = getValue(key).jsonArray.map {
    val column = it.jsonObject
    IpaColumn(column.string("key")!!, column.string("label")!!, column.string("detail").orEmpty())
  }

  private fun JsonObject.rows(key: String): List<IpaGridRow> = getValue(key).jsonArray.map {
    val row = it.jsonObject
    IpaGridRow(
      label = row.string("label")!!,
      detail = row.string("detail").orEmpty(),
      cells = row.getValue("cells").jsonObject.mapValues { (_, symbols: JsonElement) -> symbols.jsonArray.map { s -> s.jsonPrimitive.content } },
    )
  }

  /** The asset, found from the module's directory (Gradle runs tests there) up to the android/ root. */
  private fun file(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, ASSET)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    error("$ASSET not found above ${System.getProperty("user.dir")}")
  }
}
