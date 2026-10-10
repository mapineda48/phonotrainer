@file:UseSerializers(JobIdSerializer::class, ChangeLabelSerializer::class)

package com.mapineda48.phonotrainer.core.navigation

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Where the feed starts: [WatchKey.start] (architecture 16.1, work-plan Appendix A.9). */
@Serializable
sealed interface FeedStart {
  @Serializable
  data object TodaySet : FeedStart

  @Serializable
  data object Resume : FeedStart

  @Serializable
  data class InOrder(val jobId: JobId, val firstSegment: Int?, val fromStart: Boolean) : FeedStart

  @Serializable
  data class ChangeMode(val label: ChangeLabel, val onlyClip: JobId?) : FeedStart

  @Serializable
  data object SavedMode : FeedStart

  @Serializable
  data class AtWord(val jobId: JobId, val segment: Int, val word: Int, val openLesson: Boolean) : FeedStart
}
