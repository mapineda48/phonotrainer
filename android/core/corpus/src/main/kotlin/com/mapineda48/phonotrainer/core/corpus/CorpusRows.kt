package com.mapineda48.phonotrainer.core.corpus

import com.mapineda48.phonotrainer.core.analysis.DerivedShort
import com.mapineda48.phonotrainer.core.analysis.OccurrenceRecord
import com.mapineda48.phonotrainer.core.database.library.dao.OccurrenceRow
import com.mapineda48.phonotrainer.core.database.library.dao.ShortRow
import com.mapineda48.phonotrainer.core.database.library.entity.OccurrenceEntity
import com.mapineda48.phonotrainer.core.database.library.entity.ShortEntity
import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.ShortId
import com.mapineda48.phonotrainer.core.domain.model.ShortInfo
import com.mapineda48.phonotrainer.core.domain.model.Spotlight
import com.mapineda48.phonotrainer.core.domain.model.WordRef

// The one mapping between :core:analysis's derivations, the `short` and `occurrence` rows and the domain types
// (architecture 4.2, 9). Rows are written only by the indexer, so an enum stored by name always reads back; a value
// this build does not know (an older build's row) reads as the cautious one.

/** A derived short of [jobId] as its `short` row, made with [ruleVersion]. */
internal fun DerivedShort.toEntity(jobId: JobId, ruleVersion: Int): ShortEntity = ShortEntity(
  jobId = jobId.value,
  firstSegment = bounds.firstSegment,
  ruleVersion = ruleVersion,
  ordinal = ordinal,
  lastSegment = bounds.lastSegment,
  startMs = bounds.startMs,
  endMs = bounds.endMs,
  playStartMs = bounds.playStartMs,
  playEndMs = bounds.playEndMs,
  defaultSpotSegment = defaultSpotlight?.word?.segment,
  defaultSpotWord = defaultSpotlight?.word?.word,
  defaultSpotChange = defaultSpotlight?.change?.value,
  defaultSpotAdvice = defaultSpotlight?.advice?.name,
  defaultSpotCrosses = defaultSpotlight?.crossesBoundary,
  markedWords = markedWords,
)

/** An extracted occurrence as its `occurrence` row. */
internal fun OccurrenceRecord.toEntity(): OccurrenceEntity = OccurrenceEntity(
  jobId = word.jobId.value,
  segment = word.segment,
  word = word.word,
  change = change.value,
  shortFirstSegment = shortFirstSegment,
  wordText = wordText,
  wordKey = wordKey,
  dictIpa = dictIpa,
  realizedIpa = realizedIpa,
  startMs = startMs,
  endMs = endMs,
  diffCost = diffCost,
  lowConfidence = lowConfidence,
  eligible = eligible,
  crossesBoundary = crossesBoundary,
  nextWordText = nextWordText,
  advice = advice.name,
  family = family.name,
)

/** A stored row back as the record `:core:analysis`'s rules (spotlight, examples) work on. */
internal fun OccurrenceEntity.toRecord(): OccurrenceRecord = OccurrenceRecord(
  word = WordRef(JobId(jobId), segment, word),
  change = ChangeLabel(change),
  shortFirstSegment = shortFirstSegment,
  wordText = wordText,
  wordKey = wordKey,
  dictIpa = dictIpa,
  realizedIpa = realizedIpa,
  startMs = startMs,
  endMs = endMs,
  diffCost = diffCost,
  lowConfidence = lowConfidence,
  eligible = eligible,
  crossesBoundary = crossesBoundary,
  nextWordText = nextWordText,
  advice = adviceOf(advice),
  family = familyOf(family),
)

/** The domain occurrence of a row: its clip's title and its word's other labels, in alphabetical order. */
internal fun OccurrenceRow.toOccurrence(): Occurrence = occurrence.toRecord().toOccurrence(
  clipTitle = clipTitle,
  otherChanges = otherChanges.orEmpty().split(' ').filter { it.isNotEmpty() }.sorted().map(::ChangeLabel),
)

/** The short a row stores, with [spotlight] (by default its precomputed default spotlight, rules 3 to 5). */
internal fun ShortRow.toInfo(spotlight: Spotlight? = short.defaultSpotlight()): ShortInfo = ShortInfo(
  id = shortId,
  ordinal = short.ordinal,
  ofTotal = ofTotal,
  lastSegment = short.lastSegment,
  playStartMs = short.playStartMs,
  playEndMs = short.playEndMs,
  spotlight = spotlight,
  markedWords = short.markedWords,
)

internal val ShortRow.shortId: ShortId get() = ShortId(JobId(short.jobId), short.firstSegment)

/** The `default_spot_*` columns as a spotlight; null for a short without a candidate. */
internal fun ShortEntity.defaultSpotlight(): Spotlight? {
  val segment = defaultSpotSegment ?: return null
  val word = defaultSpotWord ?: return null
  val change = defaultSpotChange ?: return null
  return Spotlight(
    word = WordRef(JobId(jobId), segment, word),
    change = ChangeLabel(change),
    advice = adviceOf(defaultSpotAdvice),
    crossesBoundary = defaultSpotCrosses ?: false,
  )
}

private fun adviceOf(name: String?): Advice = Advice.entries.firstOrNull { it.name == name } ?: Advice.UNDERSTAND

private fun familyOf(name: String): Family = Family.entries.firstOrNull { it.name == name } ?: Family.OTHER
