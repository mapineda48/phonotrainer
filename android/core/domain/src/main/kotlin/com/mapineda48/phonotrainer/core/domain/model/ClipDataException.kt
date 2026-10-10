package com.mapineda48.phonotrainer.core.domain.model

/**
 * Why a clip's [ClipData] could not be produced, as a typed exception carrying the [AppError] value (architecture
 * 19: errors cross modules as `AppError` values, never as raw exceptions). Thrown by `AnalysisParser.parse` (always
 * [AppError.Content.Corrupt]) and by `ClipContentRepository.clipData` ([AppError.Content.Corrupt] or
 * [AppError.Content.Missing]); the indexer maps it to `IndexOutcome.Corrupt` / `IndexOutcome.Missing`, and a corrupt
 * clip becomes "Needs downloading again".
 *
 * [message] names only a JSON path and a reason ("segments[3].words[2].start: not a number"), never file paths or
 * transcript text (architecture 20.4).
 */
class ClipDataException(
  val error: AppError.Content,
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message ?: error.toString(), cause)
