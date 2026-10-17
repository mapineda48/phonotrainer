package com.mapineda48.phonotrainer.core.requests.store

import com.mapineda48.phonotrainer.core.domain.model.RequestId
import com.mapineda48.phonotrainer.core.domain.model.Site
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** The device queue's background work (architecture 7.2): WorkManager in the app, a recorder in tests. */
internal interface RequestWork {
  /** The expedited `intake-share-<id>`, enqueued while the share sheet is visible (Android review H2a). */
  fun startShareIntake(id: RequestId)

  /** `intake-<id>`: a picked file, or the copy already on the phone; restarts after process death (F-19-AC4). */
  fun startFileIntake(id: RequestId)

  /** Stops both intake works of [id]. */
  fun stopIntake(id: RequestId)

  /**
   * The unique `uploads` work. [userVisible]: started from the foreground, so it may run as a visible transfer (7.2);
   * otherwise background work under the size rule of 8.5.
   */
  fun scheduleUploads(userVisible: Boolean)
}

/** Whether the app is in the foreground (`ProcessLifecycleOwner`), for the visible transfer of 7.2. */
internal fun interface AppVisibility {
  fun visible(): Boolean
}

/** The few words the queue itself writes (a request's title before the computer names it, spec 8.2 rule 5). */
internal interface RequestWords {
  /** "YouTube link", "YouTube Shorts link", "X link", "Website link". */
  fun linkTitle(site: Site?): String

  /** "Video" or "Audio file", for a file whose name the provider did not give. */
  fun fileTitle(video: Boolean): String
}

/** Uploads in progress, so "Stop sending" and "Remove" stop the bytes at once (D-46). */
@Singleton
internal class ActiveUploads @Inject constructor() {
  private val jobs = ConcurrentHashMap<String, Job>()

  fun register(id: RequestId, job: Job) {
    jobs[id.value] = job
  }

  fun unregister(id: RequestId, job: Job) {
    jobs.remove(id.value, job)
  }

  fun isActive(id: RequestId): Boolean = jobs.containsKey(id.value)

  fun cancel(id: RequestId) {
    jobs.remove(id.value)?.cancel(StoppedByLearner())
  }
}

/** The learner stopped this upload (it is not the worker that was stopped). */
internal class StoppedByLearner : CancellationException("stopped by the learner")
