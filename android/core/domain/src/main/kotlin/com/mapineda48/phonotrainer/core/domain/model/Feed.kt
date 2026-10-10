package com.mapineda48.phonotrainer.core.domain.model

/** The four feed modes of spec 5.3 (architecture 3.4). */
sealed interface FeedMode {
  data class Mix(val setId: Long) : FeedMode

  data class InOrder(val jobId: JobId) : FeedMode

  data class Change(val label: ChangeLabel, val onlyClip: JobId? = null) : FeedMode

  data object Saved : FeedMode
}

data class Spotlight(val word: WordRef, val change: ChangeLabel, val advice: Advice, val crossesBoundary: Boolean)

data class ShortInfo(
  val id: ShortId,
  val ordinal: Int,
  val ofTotal: Int,
  val lastSegment: Int,
  val playStartMs: Long,
  val playEndMs: Long,
  val spotlight: Spotlight?,
  val markedWords: Int,
)

/** One page of the feed: the short and the media it plays (work-plan 4.2, `FeedPlayer`). */
data class FeedItem(val short: ShortInfo, val media: ClipMedia)

enum class LoopMode { OFF, PHRASE, SHORT }
