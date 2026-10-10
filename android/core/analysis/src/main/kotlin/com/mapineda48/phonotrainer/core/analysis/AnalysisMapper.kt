package com.mapineda48.phonotrainer.core.analysis

import com.mapineda48.phonotrainer.core.analysis.AnalysisParser.corrupt
import com.mapineda48.phonotrainer.core.domain.model.AnalysisModels
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipData
import com.mapineda48.phonotrainer.core.domain.model.ClipDataSummary
import com.mapineda48.phonotrainer.core.domain.model.ClipMeta
import com.mapineda48.phonotrainer.core.domain.model.DialogueSeparation
import com.mapineda48.phonotrainer.core.domain.model.F0Stats
import com.mapineda48.phonotrainer.core.domain.model.F0Track
import com.mapineda48.phonotrainer.core.domain.model.IntonationUnit
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Phrase
import com.mapineda48.phonotrainer.core.domain.model.Rhythm
import com.mapineda48.phonotrainer.core.domain.model.TimedPhone
import com.mapineda48.phonotrainer.core.domain.model.Word
import com.mapineda48.phonotrainer.core.domain.model.WordForm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The mapping rules of [AnalysisParser], from a decoded JSON tree into the `:core:domain` clip types (WP-12 shape
 * notes, section 1):
 * - an optional key that is absent, `null` or of an unexpected JSON type reads as absent: strings → null (an empty
 *   string too), flags → false, counts → 0, `diff_cost` → 0.0, lists → empty, objects → null; a non-finite number
 *   reads as absent;
 * - only the skeleton is required: a top-level object, `segments` as an array of objects, finite numeric `start` and
 *   `end` on every segment and on every item of its `words` (when present, `words` must be an array of objects);
 *   anything else there is `AppError.Content.Corrupt` with a path-only message; an `end` smaller than its `start` is
 *   raised to it;
 * - phrases and words keep their file positions; intonation units and phone rows may be dropped when malformed.
 */
internal class AnalysisMapper(private val jobId: JobId, private val revision: String) {
  fun clip(root: JsonElement): ClipData {
    val top = root as? JsonObject ?: throw corrupt("top level: not an object")
    val segments = top["segments"] as? JsonArray ?: throw corrupt("segments: not an array")
    val phrases = segments.mapIndexed { i, element -> phrase(i, element) }
    val meta = meta(top["meta"] as? JsonObject, phrases)
    val summary = summary(top["summary"] as? JsonObject, phrases)
    return ClipData(jobId, revision, meta, phrases, summary)
  }

  // --- meta ---------------------------------------------------------------------------------------------------------

  private fun meta(meta: JsonObject?, phrases: List<Phrase>): ClipMeta {
    val duration = meta?.get("duration").finite()?.takeIf { it >= 0.0 }
      ?: phrases.maxOfOrNull { it.endS }
      ?: 0.0
    val dialogue = (meta?.get("dialogue_separation") as? JsonObject)?.let { separation ->
      DialogueSeparation(
        applied = separation["applied"].flag() ?: return@let null,
        model = separation["model"].text(),
        seconds = separation["seconds"].finite(),
        error = separation["error"].text(),
      )
    }
    val models = (meta?.get("models") as? JsonObject)?.let {
      AnalysisModels(asr = it["asr"].text(), phones = it["phones"].text(), alignment = it["alignment"].text())
    }
    return ClipMeta(
      durationS = duration,
      language = meta?.get("language").text(),
      rulesVersion = meta?.get("rules_version").integer(),
      phoneEngine = meta?.get("phone_engine").text(),
      dialogueSeparation = dialogue?.applied,
      phonotrainerVersion = meta?.get("phonotrainer_version").text(),
      models = models,
      dialogue = dialogue,
    )
  }

  // --- summary ------------------------------------------------------------------------------------------------------

  private fun summary(summary: JsonObject?, phrases: List<Phrase>): ClipDataSummary {
    val counts = (summary?.get("phenomena_counts") as? JsonObject)?.let { written ->
      buildMap {
        for ((label, value) in written) {
          val count = value.integer() ?: continue
          if (count >= 0 && label.isNotEmpty()) put(ChangeLabel(label), count)
        }
      }
    } ?: countChanges(phrases)
    val metrics = (summary?.get("metrics") as? JsonObject)?.let(::flatten).orEmpty()
    return ClipDataSummary(changeCounts = counts, metrics = metrics)
  }

  /** The server's `count_phenomena`: one per word and label. */
  private fun countChanges(phrases: List<Phrase>): Map<ChangeLabel, Int> {
    val counts = LinkedHashMap<ChangeLabel, Int>()
    for (phrase in phrases) {
      for (word in phrase.words) {
        for (label in word.changes) counts[label] = (counts[label] ?: 0) + 1
      }
    }
    return counts
  }

  /** Every finite number leaf of `summary.metrics`, keyed by its dotted path; arrays are not entered. */
  private fun flatten(metrics: JsonObject): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    fun visit(prefix: String, node: JsonObject) {
      for ((key, value) in node) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when (value) {
          is JsonObject -> visit(path, value)
          is JsonPrimitive -> value.finite()?.let { out[path] = it }
          is JsonArray -> Unit
        }
      }
    }
    visit("", metrics)
    return out
  }

  // --- phrases ------------------------------------------------------------------------------------------------------

  private fun phrase(index: Int, element: JsonElement): Phrase {
    val path = "segments[$index]"
    val segment = element as? JsonObject ?: throw corrupt("$path: not an object")
    val start = segment["start"].required("$path.start")
    val end = maxOf(start, segment["end"].required("$path.end"))
    val words = when (val raw = segment["words"]) {
      null, JsonNull -> emptyList()
      is JsonArray -> raw.mapIndexed { j, item -> word(j, item, "$path.words[$j]") }
      else -> throw corrupt("$path.words: not an array")
    }
    val stats = segment["f0_stats"] as? JsonObject
    val f0Stats = stats?.let {
      F0Stats(
        meanHz = it["mean"].finite(),
        rangeHz = it["range"].finite(),
        finalContour = it["final_contour"].text(),
        rangeSt = it["range_st"].finite(),
        finalSlopeSt = it["final_slope_st"].finite(),
        floorHz = it["f0_floor"].finite(),
        ceilingHz = it["f0_ceiling"].finite(),
      )
    }
    return Phrase(
      index = index,
      startS = start,
      endS = end,
      text = segment["text"].text()?.trim().orEmpty(),
      words = words,
      f0 = f0Track(segment["f0_track"]),
      finalContour = f0Stats?.finalContour,
      emphasisWord = segment["emphasis_word_idx"].integer()?.takeIf { it in words.indices },
      prominence = prominence(segment["prominence"], words.size),
      wordClasses = wordClasses(segment["word_classes"], words.size),
      intonationUnits = intonationUnits(segment["intonation_units"], words.size),
      f0Stats = f0Stats,
      rhythm = rhythm(segment["rhythm"] as? JsonObject),
    )
  }

  /** `[[t, Hz], …]`: pairs that are not two finite numbers are dropped; null when none is left. */
  private fun f0Track(raw: JsonElement?): F0Track? {
    val pairs = raw as? JsonArray ?: return null
    val times = FloatArray(pairs.size)
    val hz = FloatArray(pairs.size)
    var n = 0
    for (pair in pairs) {
      val values = pair as? JsonArray ?: continue
      if (values.size < 2) continue
      val t = values[0].finite() ?: continue
      val f = values[1].finite() ?: continue
      times[n] = t.toFloat()
      hz[n] = f.toFloat()
      n += 1
    }
    if (n == 0) return null
    return if (n == pairs.size) F0Track(times, hz) else F0Track(times.copyOf(n), hz.copyOf(n))
  }

  /** One number per word, or nothing (the web's `ProminenceStrip` rule). */
  private fun prominence(raw: JsonElement?, words: Int): FloatArray? {
    val values = raw as? JsonArray ?: return null
    if (values.size != words) return null
    val out = FloatArray(words)
    for (i in 0 until words) out[i] = values[i].finite()?.toFloat() ?: return null
    return out
  }

  /** One class per word, as written, or nothing. */
  private fun wordClasses(raw: JsonElement?, words: Int): List<String>? {
    val values = raw as? JsonArray ?: return null
    if (values.size != words) return null
    return values.map { it.rawString() ?: return null }
  }

  private fun intonationUnits(raw: JsonElement?, words: Int): List<IntonationUnit> {
    val units = raw as? JsonArray ?: return emptyList()
    return units.mapNotNull { element ->
      val unit = element as? JsonObject ?: return@mapNotNull null
      val start = unit["start"].finite() ?: return@mapNotNull null
      val end = unit["end"].finite() ?: return@mapNotNull null
      val range = unit["words"] as? JsonArray ?: return@mapNotNull null
      if (range.size != 2) return@mapNotNull null
      val first = range[0].integer() ?: return@mapNotNull null
      val last = range[1].integer() ?: return@mapNotNull null
      if (first < 0 || first > last || last >= words) return@mapNotNull null
      IntonationUnit(
        startS = start,
        endS = maxOf(start, end),
        firstWord = first,
        lastWord = last,
        type = unit["type"].text(),
        finalContour = unit["final_contour"].text(),
        finalSlopeSt = unit["final_slope_st"].finite(),
        uptalk = unit["uptalk"].flag(),
        text = unit["text"].text().orEmpty(),
        expectedContour = unit["expected_contour"].text(),
        matchesExpected = unit["matches_expected"].flag(),
      )
    }
  }

  private fun rhythm(raw: JsonObject?): Rhythm? {
    if (raw == null) return null
    return Rhythm(
      npvi = raw["npvi"].finite() ?: return null,
      varco = raw["varco"].finite(),
      intervals = raw["n_intervals"].integer() ?: return null,
      pairs = raw["n_pairs"].integer() ?: return null,
      meanMs = raw["mean_ms"].finite() ?: return null,
      sdMs = raw["sd_ms"].finite() ?: return null,
      approximate = raw["approximate"].flag() ?: true,
      method = raw["method"].text(),
    )
  }

  // --- words --------------------------------------------------------------------------------------------------------

  private fun word(index: Int, element: JsonElement, path: String): Word {
    val word = element as? JsonObject ?: throw corrupt("$path: not an object")
    val start = word["start"].required("$path.start")
    val end = maxOf(start, word["end"].required("$path.end"))
    return Word(
      index = index,
      text = word["word"].text().orEmpty(),
      startS = start,
      endS = end,
      dictIpa = word["dict_ipa"].text(),
      canonicalIpa = word["canonical_ipa"].text(),
      realizedIpa = word["realized_ipa"].text(),
      canonicalPhones = phones(word["canonical_aligned"]),
      realizedPhones = phones(word["realized_aligned"]),
      changes = labels(word["phenomena"]),
      diffCost = word["diff_cost"].finite() ?: 0.0,
      lowConfidence = word["low_confidence"].flag() ?: false,
      boundaryLinkNext = word["boundary_link_next"].flag() ?: false,
      lexicalForm = word["lexical_form"].text(),
      oov = word["oov"].flag() ?: false,
      boundaryLinkType = word["boundary_link_type"].text(),
      lexicalExpansion = word["lexical_expansion"].text(),
      noCanonical = word["no_canonical"].flag() ?: false,
      canonicalText = word["canonical_text"].text(),
      attractedCount = word["attracted_count"].integer()?.coerceAtLeast(0) ?: 0,
      alignmentFallback = word["alignment_fallback"].flag() ?: false,
      form = form(word["form"] as? JsonObject),
      variantLabels = labels(word["variant_labels"]),
    )
  }

  /** `[[phone, start, end], …]`: rows that are not a non-empty string and two finite numbers are dropped. */
  private fun phones(raw: JsonElement?): List<TimedPhone> {
    val rows = raw as? JsonArray ?: return emptyList()
    val out = ArrayList<TimedPhone>(rows.size)
    for (row in rows) {
      val cells = row as? JsonArray ?: continue
      if (cells.size < 3) continue
      val symbol = cells[0].rawString()?.takeIf { it.isNotEmpty() } ?: continue
      val start = cells[1].finite() ?: continue
      val end = cells[2].finite() ?: continue
      out += TimedPhone(symbol, start, maxOf(start, end))
    }
    return out
  }

  /** Labels in order, without repeats; items that are not non-empty strings are skipped. Unknown labels are kept. */
  private fun labels(raw: JsonElement?): List<ChangeLabel> {
    val items = raw as? JsonArray ?: return emptyList()
    val seen = LinkedHashSet<String>()
    for (item in items) item.rawString()?.takeIf { it.isNotEmpty() }?.let(seen::add)
    return seen.map(::ChangeLabel)
  }

  private fun form(raw: JsonObject?): WordForm? {
    if (raw == null) return null
    return WordForm(
      ipa = raw["ipa"].rawString() ?: return null,
      strongIpa = raw["strong_ipa"].rawString() ?: return null,
      weak = raw["weak"].flag() ?: return null,
      weakMargin = raw["weak_margin"].finite(),
      hDropped = raw["h_dropped"].flag(),
      hDropMargin = raw["h_drop_margin"].finite(),
    )
  }

  // --- JSON value readers -------------------------------------------------------------------------------------------

  private fun JsonElement?.required(path: String): Double = finite() ?: throw corrupt("$path: not a finite number")

  private companion object {
    /** A JSON number (not a string, flag or null), possibly NaN or infinite. */
    fun JsonElement?.number(): Double? {
      val primitive = this as? JsonPrimitive ?: return null
      if (primitive.isString || primitive is JsonNull) return null
      val content = primitive.content
      if (content == "true" || content == "false") return null
      return content.toDoubleOrNull()
    }

    fun JsonElement?.finite(): Double? = number()?.takeIf { it.isFinite() }

    /** A whole number in the `Int` range ("3", "3.0"). */
    fun JsonElement?.integer(): Int? {
      val value = finite() ?: return null
      if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
      return value.toInt()
    }

    fun JsonElement?.flag(): Boolean? {
      val primitive = this as? JsonPrimitive ?: return null
      if (primitive.isString) return null
      return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
      }
    }

    /** A JSON string as written, the empty string included. */
    fun JsonElement?.rawString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An optional text: a non-empty JSON string, else null. */
    fun JsonElement?.text(): String? = rawString()?.takeIf { it.isNotEmpty() }
  }
}
