package com.mapineda48.phonotrainer.shell

import com.mapineda48.phonotrainer.core.domain.api.ClipRepository
import com.mapineda48.phonotrainer.core.domain.api.RequestRepository
import com.mapineda48.phonotrainer.core.domain.api.UserMessages
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.UserMessageKind
import com.mapineda48.phonotrainer.core.navigation.FeedStart
import com.mapineda48.phonotrainer.core.navigation.HomeKey
import com.mapineda48.phonotrainer.core.navigation.LibraryKey
import com.mapineda48.phonotrainer.core.navigation.NavTarget
import com.mapineda48.phonotrainer.core.navigation.RequestDetailKey
import com.mapineda48.phonotrainer.core.navigation.RequestsKey
import com.mapineda48.phonotrainer.core.navigation.Tab
import com.mapineda48.phonotrainer.core.navigation.WatchKey
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Resolves an internal target (spec 5.4, architecture 16.4): the place it names when it still exists, else its
 * nearest parent with the spec's notice: a clip target's "That clip is no longer on this phone." (CLIP_GONE), a word
 * target's "That clip is no longer on your computer or this phone." (WORD_CLIP_GONE).
 */
class TargetResolver @Inject constructor(
  private val clips: ClipRepository,
  private val requests: RequestRepository,
  private val messages: UserMessages,
) {
  internal suspend fun resolve(target: NavTarget, navigator: AppNavigator) {
    when (target) {
      NavTarget.Home -> navigator.replace(Tab.HOME, listOf(HomeKey))
      NavTarget.Requests -> navigator.replace(Tab.HOME, listOf(HomeKey, RequestsKey))
      is NavTarget.RequestDetail ->
        if (requests.request(target.requestId).first() != null) {
          navigator.replace(Tab.HOME, listOf(HomeKey, RequestsKey, RequestDetailKey(target.requestId)))
        } else {
          navigator.replace(Tab.HOME, listOf(HomeKey, RequestsKey))
          notice(UserMessageKind.REQUEST_REMOVED)
        }
      is NavTarget.ClipFeed ->
        if (exists(target.jobId)) {
          navigator.navigate(WatchKey(FeedStart.InOrder(target.jobId, firstSegment = null, fromStart = false)))
        } else {
          navigator.replace(Tab.HOME, listOf(HomeKey))
          notice(UserMessageKind.CLIP_GONE)
        }
      is NavTarget.WordLesson ->
        if (exists(target.jobId)) {
          navigator.navigate(WatchKey(FeedStart.AtWord(target.jobId, target.segment, target.word, openLesson = true)))
        } else {
          navigator.replace(Tab.HOME, listOf(HomeKey, LibraryKey))
          notice(UserMessageKind.WORD_CLIP_GONE)
        }
    }
  }

  private suspend fun exists(id: JobId): Boolean = clips.clip(id).first() != null

  private fun notice(kind: UserMessageKind) {
    messages.post(kind)
  }
}
