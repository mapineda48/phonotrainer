@file:UseSerializers(JobIdSerializer::class, RequestIdSerializer::class)

package com.mapineda48.phonotrainer.core.navigation

import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Internal navigation targets (spec 5.4, architecture 16.4), delivered only through the non-exported activity alias
 * `.InternalEntry`: notifications and the share sheet. They carry server ids only, never addresses or credentials.
 */
@Serializable
sealed interface NavTarget {
  @Serializable
  data class ClipFeed(val jobId: JobId) : NavTarget

  @Serializable
  data class RequestDetail(val requestId: RequestId) : NavTarget

  @Serializable
  data object Requests : NavTarget

  @Serializable
  data object Home : NavTarget

  @Serializable
  data class WordLesson(val jobId: JobId, val segment: Int, val word: Int) : NavTarget
}
